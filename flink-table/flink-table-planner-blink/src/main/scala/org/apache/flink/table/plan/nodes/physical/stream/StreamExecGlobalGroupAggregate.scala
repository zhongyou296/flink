/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.flink.table.plan.nodes.physical.stream

import org.apache.flink.streaming.api.transformations.OneInputTransformation
import org.apache.flink.table.api.{StreamTableEnvironment, TableConfig, TableConfigOptions, TableException}
import org.apache.flink.table.calcite.FlinkTypeFactory
import org.apache.flink.table.codegen.agg.AggsHandlerCodeGenerator
import org.apache.flink.table.codegen.{CodeGeneratorContext, EqualiserCodeGenerator}
import org.apache.flink.table.dataformat.BaseRow
import org.apache.flink.table.generated.GeneratedAggsHandleFunction
import org.apache.flink.table.plan.PartialFinalType
import org.apache.flink.table.plan.nodes.exec.{ExecNode, StreamExecNode}
import org.apache.flink.table.plan.rules.physical.stream.StreamExecRetractionRules
import org.apache.flink.table.plan.util._
import org.apache.flink.table.runtime.aggregate.MiniBatchGlobalGroupAggFunction
import org.apache.flink.table.runtime.bundle.KeyedMapBundleOperator
import org.apache.flink.table.types.DataType
import org.apache.flink.table.types.LogicalTypeDataTypeConverter.fromDataTypeToLogicalType
import org.apache.flink.table.typeutils.BaseRowTypeInfo
import org.apache.calcite.plan.{RelOptCluster, RelTraitSet}
import org.apache.calcite.rel.`type`.RelDataType
import org.apache.calcite.rel.{RelNode, RelWriter}
import org.apache.calcite.tools.RelBuilder
import java.util

import org.apache.flink.api.dag.Transformation

import scala.collection.JavaConversions._

/**
  * Stream physical RelNode for unbounded global group aggregate.
  *
  * @see [[StreamExecGroupAggregateBase]] for more info.
  */
class StreamExecGlobalGroupAggregate(
    cluster: RelOptCluster,
    traitSet: RelTraitSet,
    inputRel: RelNode,
    val inputRowType: RelDataType,
    outputRowType: RelDataType,
    val grouping: Array[Int],
    val localAggInfoList: AggregateInfoList,
    val globalAggInfoList: AggregateInfoList,
    val partialFinalType: PartialFinalType)
  extends StreamExecGroupAggregateBase(cluster, traitSet, inputRel)
  with StreamExecNode[BaseRow] {

  override def producesUpdates = true

  override def needsUpdatesAsRetraction(input: RelNode) = true

  override def consumesRetractions = true

  override def producesRetractions: Boolean = false

  override def requireWatermark: Boolean = false

  override def deriveRowType(): RelDataType = outputRowType

  override def copy(traitSet: RelTraitSet, inputs: java.util.List[RelNode]): RelNode = {
    new StreamExecGlobalGroupAggregate(
      cluster,
      traitSet,
      inputs.get(0),
      inputRowType,
      outputRowType,
      grouping,
      localAggInfoList,
      globalAggInfoList,
      partialFinalType)
  }

  override def explainTerms(pw: RelWriter): RelWriter = {
    super.explainTerms(pw)
      .itemIf("groupBy",
        RelExplainUtil.fieldToString(grouping, inputRel.getRowType), grouping.nonEmpty)
      .itemIf("partialFinalType", partialFinalType, partialFinalType != PartialFinalType.NONE)
      .item("select", RelExplainUtil.streamGroupAggregationToString(
        inputRel.getRowType,
        getRowType,
        globalAggInfoList,
        grouping,
        isGlobal = true))
  }

  //~ ExecNode methods -----------------------------------------------------------

  override def getInputNodes: util.List[ExecNode[StreamTableEnvironment, _]] = {
    getInputs.map(_.asInstanceOf[ExecNode[StreamTableEnvironment, _]])
  }

  override def replaceInputNode(
      ordinalInParent: Int,
      newInputNode: ExecNode[StreamTableEnvironment, _]): Unit = {
    replaceInput(ordinalInParent, newInputNode.asInstanceOf[RelNode])
  }

  override protected def translateToPlanInternal(
      tableEnv: StreamTableEnvironment): Transformation[BaseRow] = {
    val tableConfig = tableEnv.getConfig

    if (grouping.length > 0 && tableConfig.getMinIdleStateRetentionTime < 0) {
      LOG.warn("No state retention interval configured for a query which accumulates state. " +
        "Please provide a query configuration with valid retention interval to prevent excessive " +
        "state size. You may specify a retention time of 0 to not clean up the state.")
    }

    val inputTransformation = getInputNodes.get(0).translateToPlan(tableEnv)
      .asInstanceOf[Transformation[BaseRow]]

    val outRowType = FlinkTypeFactory.toLogicalRowType(outputRowType)

    val generateRetraction = StreamExecRetractionRules.isAccRetract(this)

    val localAggsHandler = generateAggsHandler(
      "LocalGroupAggsHandler",
      localAggInfoList,
      mergedAccOffset = grouping.length,
      mergedAccOnHeap = true,
      localAggInfoList.getAccTypes,
      tableConfig,
      tableEnv.getRelBuilder,
      // the local aggregate result will be buffered, so need copy
      inputFieldCopy = true)

    val globalAggsHandler = generateAggsHandler(
      "GlobalGroupAggsHandler",
      globalAggInfoList,
      mergedAccOffset = 0,
      mergedAccOnHeap = true,
      localAggInfoList.getAccTypes,
      tableConfig,
      tableEnv.getRelBuilder,
      // if global aggregate result will be put into state, then not need copy
      // but this global aggregate result will be put into a buffered map first,
      // then multiput to state, so it need copy
      inputFieldCopy = true)

    val indexOfCountStar = globalAggInfoList.getIndexOfCountStar
    val globalAccTypes = globalAggInfoList.getAccTypes.map(fromDataTypeToLogicalType)
    val globalAggValueTypes = globalAggInfoList
      .getActualValueTypes
      .map(fromDataTypeToLogicalType)
    val recordEqualiser = new EqualiserCodeGenerator(globalAggValueTypes)
      .generateRecordEqualiser("GroupAggValueEqualiser")

    val operator = if (tableConfig.getConf.contains(
      TableConfigOptions.SQL_EXEC_MINIBATCH_ALLOW_LATENCY)) {
      val aggFunction = new MiniBatchGlobalGroupAggFunction(
        localAggsHandler,
        globalAggsHandler,
        recordEqualiser,
        globalAccTypes,
        indexOfCountStar,
        generateRetraction)

      new KeyedMapBundleOperator(
        aggFunction,
        AggregateUtil.createMiniBatchTrigger(tableConfig))
    } else {
      throw new TableException("Local-Global optimization is only worked in miniBatch mode")
    }

    val inputTypeInfo = inputTransformation.getOutputType.asInstanceOf[BaseRowTypeInfo]
    val selector = KeySelectorUtil.getBaseRowSelector(grouping, inputTypeInfo)

    // partitioned aggregation
    val ret = new OneInputTransformation(
      inputTransformation,
      "GlobalGroupAggregate",
      operator,
      BaseRowTypeInfo.of(outRowType),
      getResource.getParallelism)

    if (getResource.getMaxParallelism > 0) {
      ret.setMaxParallelism(getResource.getMaxParallelism)
    }

    // set KeyType and Selector for state
    ret.setStateKeySelector(selector)
    ret.setStateKeyType(selector.getProducedType)
    ret
  }

  def generateAggsHandler(
      name: String,
      aggInfoList: AggregateInfoList,
      mergedAccOffset: Int,
      mergedAccOnHeap: Boolean,
      mergedAccExternalTypes: Array[DataType],
      config: TableConfig,
      relBuilder: RelBuilder,
      inputFieldCopy: Boolean): GeneratedAggsHandleFunction = {

    val generator = new AggsHandlerCodeGenerator(
      CodeGeneratorContext(config),
      relBuilder,
      FlinkTypeFactory.toLogicalRowType(inputRowType).getChildren,
      inputFieldCopy)

    generator
      .needAccumulate()
      .needMerge(mergedAccOffset, mergedAccOnHeap, mergedAccExternalTypes)
      .generateAggsHandler(name, aggInfoList)
  }


}
