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

package org.apache.flink.api.table

import java.util.concurrent.atomic.AtomicInteger

import org.apache.calcite.plan.RelOptPlanner.CannotPlanException
import org.apache.calcite.plan.RelOptUtil
import org.apache.calcite.sql2rel.RelDecorrelator
import org.apache.calcite.tools.Programs
import org.apache.flink.api.common.io.InputFormat
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.api.table.expressions.Expression
import org.apache.flink.api.table.plan.PlanGenException
import org.apache.flink.api.table.plan.nodes.datastream.{DataStreamRel, DataStreamConvention}
import org.apache.flink.api.table.plan.rules.FlinkRuleSets
import org.apache.flink.api.table.plan.schema.DataStreamTable
import org.apache.flink.streaming.api.datastream.DataStream

/**
  * The base class for stream TableEnvironments.
  *
  * A TableEnvironment can be used to:
  * - convert [[DataStream]] to a [[Table]]
  * - register a [[DataStream]] as a table in the catalog
  * - register a [[Table]] in the catalog
  * - scan a registered table to obtain a [[Table]]
  * - specify a SQL query on registered tables to obtain a [[Table]]
  * - convert a [[Table]] into a [[DataStream]]
  *
  * @param config The configuration of the TableEnvironment.
  */
abstract class StreamTableEnvironment(config: TableConfig) extends TableEnvironment(config) {

  // a counter for unique table names
  private val nameCntr: AtomicInteger = new AtomicInteger(0)

  // the naming pattern for internally registered tables.
  private val internalNamePattern = "^_DataStreamTable_[0-9]+$".r

  /**
    * Checks if the chosen table name is valid.
    *
    * @param name The table name to check.
    */
  override protected def checkValidTableName(name: String): Unit = {
    val m = internalNamePattern.findFirstIn(name)
    m match {
      case Some(_) =>
        throw new TableException(s"Illegal Table name. " +
          s"Please choose a name that does not contain the pattern $internalNamePattern")
      case None =>
    }
  }

  /** Returns a unique table name according to the internal naming pattern. */
  protected def createUniqueTableName(): String = "_DataStreamTable_" + nameCntr.getAndIncrement()

  /**
    * Ingests a registered table and returns the resulting [[Table]].
    *
    * The table to ingest must be registered in the [[TableEnvironment]]'s catalog.
    *
    * @param tableName The name of the table to ingest.
    * @throws TableException if no table is registered under the given name.
    * @return The ingested table.
    */
  @throws[TableException]
  def ingest(tableName: String): Table = {

    if (isRegistered(tableName)) {
      relBuilder.scan(tableName)
      new Table(relBuilder.build(), this)
    }
    else {
      throw new TableException(s"Table \'$tableName\' was not found in the registry.")
    }
  }

  /**
    * Evaluates a SQL query on registered tables and retrieves the result as a [[Table]].
    *
    * All tables referenced by the query must be registered in the TableEnvironment.
    *
    * @param query The SQL query to evaluate.
    * @return The result of the query as Table.
    */
  override def sql(query: String): Table = {

    ???
  }

  /**
    * Registers a [[DataStream]] as a table under a given name in the [[TableEnvironment]]'s
    * catalog.
    *
    * @param name The name under which the table is registered in the catalog.
    * @param dataStream The [[DataStream]] to register as table in the catalog.
    * @tparam T the type of the [[DataStream]].
    */
  protected def registerDataStreamInternal[T](
    name: String,
    dataStream: DataStream[T]): Unit = {

    val (fieldNames, fieldIndexes) = getFieldInfo[T](dataStream.getType)
    val dataStreamTable = new DataStreamTable[T](
      dataStream,
      fieldIndexes,
      fieldNames
    )
    registerTableInternal(name, dataStreamTable)
  }

  /**
    * Registers a [[DataStream]] as a table under a given name with field names as specified by
    * field expressions in the [[TableEnvironment]]'s catalog.
    *
    * @param name The name under which the table is registered in the catalog.
    * @param dataStream The [[DataStream]] to register as table in the catalog.
    * @param fields The field expressions to define the field names of the table.
    * @tparam T The type of the [[DataStream]].
    */
  protected def registerDataStreamInternal[T](
    name: String,
    dataStream: DataStream[T],
    fields: Array[Expression]): Unit = {

    val (fieldNames, fieldIndexes) = getFieldInfo[T](dataStream.getType, fields.toArray)
    val dataStreamTable = new DataStreamTable[T](
      dataStream,
      fieldIndexes.toArray,
      fieldNames.toArray
    )
    registerTableInternal(name, dataStreamTable)
  }

  /**
    * Translates a [[Table]] into a [[DataStream]].
    *
    * The transformation involves optimizing the relational expression tree as defined by
    * Table API calls and / or SQL queries and generating corresponding [[DataStream]] operators.
    *
    * @param table The root node of the relational expression tree.
    * @param tpe The [[TypeInformation]] of the resulting [[DataStream]].
    * @tparam A The type of the resulting [[DataStream]].
    * @return The [[DataStream]] that corresponds to the translated [[Table]].
    */
  protected def translate[A](table: Table)(implicit tpe: TypeInformation[A]): DataStream[A] = {

    val relNode = table.relNode

    // decorrelate
    val decorPlan = RelDecorrelator.decorrelateQuery(relNode)

    // optimize the logical Flink plan
    val optProgram = Programs.ofRules(FlinkRuleSets.DATASTREAM_OPT_RULES)
    val flinkOutputProps = relNode.getTraitSet.replace(DataStreamConvention.INSTANCE).simplify()

    val dataStreamPlan = try {
      optProgram.run(getPlanner, decorPlan, flinkOutputProps)
    }
    catch {
      case e: CannotPlanException =>
        throw new PlanGenException(
          s"Cannot generate a valid execution plan for the given query: \n\n" +
            s"${RelOptUtil.toString(relNode)}\n" +
            "Please consider filing a bug report.", e)
    }

    dataStreamPlan match {
      case node: DataStreamRel =>
        node.translateToPlan(
          this,
          Some(tpe.asInstanceOf[TypeInformation[Any]])
        ).asInstanceOf[DataStream[A]]
      case _ => ???
    }

  }

  /**
    * Creates a [[Row]] [[DataStream]] from an [[InputFormat]].
    *
    * @param inputFormat [[InputFormat]] from which the [[DataStream]] is created.
    * @param typeInfo [[TypeInformation]] of the type of the [[DataStream]].
    * @return A [[Row]] [[DataStream]] created from the [[InputFormat]].
    */
  private[flink] def createDataStreamSource(
      inputFormat: InputFormat[Row, _],
      typeInfo: TypeInformation[Row]): DataStream[Row]

}
