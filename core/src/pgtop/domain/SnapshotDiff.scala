package pgtop.domain

import java.time.Duration

/** One table as both snapshots saw it. A missing side means the table did not exist then. */
final case class TableDelta(
  relation: RelationName,
  before: Option[TableStats],
  after: Option[TableStats],
  window: Duration
):
  def appeared: Boolean = before.isEmpty && after.isDefined

  def dropped: Boolean = before.isDefined && after.isEmpty

  private def change(pick: TableStats => Long): Option[Long] =
    for beforeStats <- before; afterStats <- after yield pick(afterStats) - pick(beforeStats)

  def sequentialScansDelta: Option[Long] = change(_.sequentialScans)

  def indexScansDelta: Option[Long] = change(_.indexScans)

  def deadTuplesDelta: Option[Long] = change(_.deadTuples)

  def writesDelta: Option[Long] = change(_.writes)

  def sequentialScansPerHour: Option[Double] = sequentialScansDelta.flatMap(delta =>
    Rate.over(window, delta.toDouble)
  )

  def indexScansPerHour: Option[Double] = indexScansDelta.flatMap(delta => Rate.over(window, delta.toDouble))

  def deadTuplesPerHour: Option[Double] = deadTuplesDelta.flatMap(delta => Rate.over(window, delta.toDouble))

  def writesPerHour: Option[Double] = writesDelta.flatMap(delta => Rate.over(window, delta.toDouble))

/** One index as both snapshots saw it, keyed by relation and index name. */
final case class IndexDelta(
  relation: RelationName,
  name: IndexName,
  before: Option[IndexStats],
  after: Option[IndexStats],
  window: Duration
):
  def appeared: Boolean = before.isEmpty && after.isDefined

  def dropped: Boolean = before.isDefined && after.isEmpty

  private def change(pick: IndexStats => Long): Option[Long] =
    for beforeStats <- before; afterStats <- after yield pick(afterStats) - pick(beforeStats)

  def scansDelta: Option[Long] = change(_.scans)

  def tuplesReadDelta: Option[Long] = change(_.tuplesRead)

  /** False when not one scan or tuple read moved during the window. */
  def usedInWindow: Option[Boolean] =
    for beforeStats <- before; afterStats <- after
    yield afterStats.scans > beforeStats.scans || afterStats.tuplesRead > beforeStats.tuplesRead

  def sizeBytes: Option[Long] = after.map(_.sizeBytes)

/** One statement as both snapshots saw it, keyed by queryid. */
final case class StatementDelta(
  queryId: Long,
  before: Option[StatementStats],
  after: Option[StatementStats],
  window: Duration
):
  /** The later text; pg_stat_statements can re-normalise a statement across upgrades. */
  def query: String = after.map(_.query).orElse(before.map(_.query)).getOrElse("")

  def appeared: Boolean = before.isEmpty && after.isDefined

  def dropped: Boolean = before.isDefined && after.isEmpty

  private def longChange(pick: StatementStats => Long): Option[Long] =
    for beforeStats <- before; afterStats <- after yield pick(afterStats) - pick(beforeStats)

  def callsDelta: Option[Long] = longChange(_.calls)

  def totalExecMsDelta: Option[Double] =
    for beforeStats <- before; afterStats <- after yield afterStats.totalExecMs - beforeStats.totalExecMs

  def rowsDelta: Option[Long] = longChange(_.rows)

  def tempBlocksWrittenDelta: Option[Long] = longChange(_.tempBlocksWritten)

  def totalExecMsPerHour: Option[Double] = totalExecMsDelta.flatMap(Rate.over(window, _))

/** Two snapshots of the same cluster, counters turned into deltas and per-hour rates. */
final case class SnapshotDiff(
  before: Snapshot,
  after: Snapshot,
  window: Duration,
  countersReset: Boolean,
  databasesDiffer: Boolean,
  tables: Vector[TableDelta],
  indexes: Vector[IndexDelta],
  statements: Vector[StatementDelta]
)

object SnapshotDiff:
  /** Pairs two snapshots by capture time: whichever was taken earlier becomes `before`. */
  def between(first: Snapshot, second: Snapshot): SnapshotDiff =
    val (before, after) =
      if second.capturedAt.isBefore(first.capturedAt) then (second, first) else (first, second)
    val window = Duration.between(before.capturedAt, after.capturedAt)

    val beforeTables = before.tables.map(table => table.relation -> table).toMap
    val afterTables = after.tables.map(table => table.relation -> table).toMap
    val tables = (beforeTables.keySet ++ afterTables.keySet).toVector.map { relation =>
      TableDelta(relation, beforeTables.get(relation), afterTables.get(relation), window)
    }.sortBy(_.relation.qualified)

    val beforeIndexes = before.indexes.map(index => (index.relation, index.name) -> index).toMap
    val afterIndexes = after.indexes.map(index => (index.relation, index.name) -> index).toMap
    val indexes = (beforeIndexes.keySet ++ afterIndexes.keySet).toVector.map { key =>
      IndexDelta(key._1, key._2, beforeIndexes.get(key), afterIndexes.get(key), window)
    }.sortBy(index => (index.relation.qualified, index.name.name))

    val beforeStatements = byQueryId(before.statements)
    val afterStatements = byQueryId(after.statements)
    val statements = (beforeStatements.keySet ++ afterStatements.keySet).toVector.map { queryId =>
      StatementDelta(queryId, beforeStatements.get(queryId), afterStatements.get(queryId), window)
    }.sortBy(_.queryId)

    SnapshotDiff(
      before = before,
      after = after,
      window = window,
      countersReset = before.cluster.statisticsResetAt != after.cluster.statisticsResetAt,
      databasesDiffer = before.cluster.database != after.cluster.database,
      tables = tables,
      indexes = indexes,
      statements = statements
    )

  /** pg_stat_statements reports one row per (database, user, queryid); a snapshot covers one
    * database, so several users can carry the same queryid. The busiest copy stands in.
    */
  private def byQueryId(statements: Vector[StatementStats]): Map[Long, StatementStats] =
    statements
      .groupBy(_.queryId)
      .view
      .mapValues(rows => rows.maxBy(_.totalExecMs))
      .toMap

/** Counter deltas as per-hour rates. A window with no length has no rates. */
private object Rate:
  def over(window: Duration, delta: Double): Option[Double] =
    val seconds = window.getSeconds.toDouble + window.getNano / 1e9
    if seconds <= 0.0 then None else Some(delta * 3600.0 / seconds)
