/*
 * Copyright 2019 Spotify AB.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.spotify.scio.bigquery.syntax

import com.google.api.services.bigquery.model.TableReference
import com.spotify.scio.ScioContext
import com.spotify.scio.bigquery.{
  BigQuerySelect,
  BigQueryStorage,
  BigQueryTable,
  BigQueryType,
  BigQueryTyped,
  Table,
  TableRow,
  TableRowJsonIO
}
import com.spotify.scio.bigquery.types.BigQueryType.HasAnnotation
import com.spotify.scio.coders.Coder
import com.spotify.scio.values._

import scala.language.implicitConversions
import scala.reflect.ClassTag
import scala.reflect.runtime.universe._

/** Enhanced version of [[ScioContext]] with BigQuery methods. */
final class ScioContextOps(private val self: ScioContext) extends AnyVal {

  /**
   * Get an SCollection for a BigQuery SELECT query.
   * Both [[https://cloud.google.com/bigquery/docs/reference/legacy-sql Legacy SQL]] and
   * [[https://cloud.google.com/bigquery/docs/reference/standard-sql/ Standard SQL]] dialects are
   * supported. By default the query dialect will be automatically detected. To override this
   * behavior, start the query string with `#legacysql` or `#standardsql`.
   */
  def bigQuerySelect(
    sqlQuery: String,
    flattenResults: Boolean = BigQuerySelect.ReadParam.DefaultFlattenResults
  ): SCollection[TableRow] =
    self.read(BigQuerySelect(sqlQuery))(BigQuerySelect.ReadParam(flattenResults))

  /**
   * Get an SCollection for a BigQuery table.
   */
  @deprecated(
    "this method will be removed; use bigQueryTable(Table.Ref(table)) instead",
    "Scio 0.8"
  )
  def bigQueryTable(table: TableReference): SCollection[TableRow] =
    bigQueryTable(Table.Ref(table))

  /**
   * Get an SCollection for a BigQuery table.
   */
  @deprecated(
    "this method will be removed; use bigQueryTable(Table.Spec(table)) instead",
    "Scio 0.8"
  )
  def bigQueryTable(tableSpec: String): SCollection[TableRow] =
    bigQueryTable(Table.Spec(tableSpec))

  /**
   * Get an SCollection for a BigQuery table.
   */
  def bigQueryTable(table: Table): SCollection[TableRow] =
    self.read(BigQueryTable(table))

  /**
   * Get an SCollection for a BigQuery table using the storage API.
   *
   * @param selectedFields names of the fields in the table that should be read. If empty, all
   *                       fields will be read. If the specified field is a nested field, all the
   *                       sub-fields in the field will be selected.
   * @param rowRestriction SQL text filtering statement, similar ti a WHERE clause in a query.
   *                       Currently, we support combinations of predicates that are a comparison
   *                       between a column and a constant value in SQL statement. Aggregates are
   *                       not supported. For example:
   *
   * {{{
   * "a > DATE '2014-09-27' AND (b > 5 AND c LIKE 'date')"
   * }}}
   */
  def bigQueryStorage(
    table: Table,
    selectedFields: List[String] = Nil,
    rowRestriction: String = null
  ): SCollection[TableRow] =
    self.read(BigQueryStorage(table))(
      BigQueryStorage.ReadParam(selectedFields, rowRestriction)
    )

  /**
   * Get a typed SCollection for a BigQuery SELECT query, table or storage.
   *
   * Note that `T` must be annotated with
   * [[com.spotify.scio.bigquery.types.BigQueryType.fromSchema BigQueryType.fromSchema]],
   * [[com.spotify.scio.bigquery.types.BigQueryType.fromSchema BigQueryType.fromStorage]],
   * [[com.spotify.scio.bigquery.types.BigQueryType.fromTable BigQueryType.fromTable]],
   * [[com.spotify.scio.bigquery.types.BigQueryType.fromQuery BigQueryType.fromQuery]], or
   * [[com.spotify.scio.bigquery.types.BigQueryType.toTable BigQueryType.toTable]].
   *
   * By default the source (table or query) specified in the annotation will be used, but it can
   * be overridden with the `newSource` parameter. For example:
   *
   * {{{
   * @BigQueryType.fromTable("publicdata:samples.gsod")
   * class Row
   *
   * // Read from [publicdata:samples.gsod] as specified in the annotation.
   * sc.typedBigQuery[Row]()
   *
   * // Read from [myproject:samples.gsod] instead.
   * sc.typedBigQuery[Row]("myproject:samples.gsod")
   *
   * // Read from a query instead.
   * sc.typedBigQuery[Row]("SELECT * FROM [publicdata:samples.gsod] LIMIT 1000")
   * }}}
   *
   * Both [[https://cloud.google.com/bigquery/docs/reference/legacy-sql Legacy SQL]] and
   * [[https://cloud.google.com/bigquery/docs/reference/standard-sql/ Standard SQL]] dialects are
   * supported. By default the query dialect will be automatically detected. To override this
   * behavior, start the query string with `#legacysql` or `#standardsql`.
   */
  def typedBigQuery[T <: HasAnnotation: ClassTag: TypeTag: Coder](
    newSource: String = null
  ): SCollection[T] = {
    val bqt = BigQueryType[T]
    if (bqt.isStorage) {
      typedBigQueryStorage(newSource)
    } else {
      self.read(BigQueryTyped.dynamic[T](newSource))
    }
  }

  /**
   * Get a typed SCollection for a BigQuery storage API.
   *
   * Note that `T` must be annotated with
   * [[com.spotify.scio.bigquery.types.BigQueryType.fromSchema BigQueryType.fromStorage]].
   *
   * Similar to [[typedBigQuery]] but allows `rowRestriction` to be overridden.
   */
  def typedBigQueryStorage[T <: HasAnnotation: ClassTag: TypeTag: Coder](
    newSource: String = null,
    rowRestriction: String = null
  ): SCollection[T] = {
    val bqt = BigQueryType[T]
    val table = if (newSource != null) newSource else bqt.table.get
    val rr = if (rowRestriction != null) rowRestriction else bqt.rowRestriction.get
    val params = BigQueryTyped.Storage.ReadParam(bqt.selectedFields.get, rr)
    self.read(BigQueryTyped.Storage[T](Table.Spec(table)))(params)
  }

  /**
   * Get an SCollection for a BigQuery TableRow JSON file.
   */
  def tableRowJsonFile(path: String): SCollection[TableRow] =
    self.read(TableRowJsonIO(path))

}

trait ScioContextSyntax {
  implicit def bigQueryScioContextOps(sc: ScioContext): ScioContextOps = new ScioContextOps(sc)
}
