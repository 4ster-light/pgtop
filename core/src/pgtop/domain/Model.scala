package pgtop.domain

import java.time.{Duration, Instant}

/** Fully qualified relation name, as `schema.table`. */
final case class RelationName(schema: String, name: String):
  def qualified: String = s"$schema.$name"

/** Fully qualified index name. Indexes live in the schema of their table. */
final case class IndexName(schema: String, name: String):
  def qualified: String = s"$schema.$name"

/** Cluster identity plus the freshness of the statistics we are reading. */
final case class ClusterInfo(
  serverVersion: String,
  database: String,
  postmasterStartedAt: Option[Instant],
  statisticsResetAt: Option[Instant],
  pgStatStatementsVersion: Option[String]
)

object ClusterInfo:
  val unknown: ClusterInfo = ClusterInfo(
    serverVersion = "unknown",
    database = "unknown",
    postmasterStartedAt = None,
    statisticsResetAt = None,
    pgStatStatementsVersion = None
  )

/** Cumulative per-table counters from `pg_stat_user_tables`, plus sizes. */
final case class TableStats(
  relation: RelationName,
  sequentialScans: Long,
  indexScans: Long,
  liveTuples: Long,
  deadTuples: Long,
  modificationsSinceAnalyze: Long,
  inserted: Long,
  updated: Long,
  deleted: Long,
  lastVacuum: Option[Instant],
  lastAutovacuum: Option[Instant],
  lastAnalyze: Option[Instant],
  lastAutoanalyze: Option[Instant],
  totalBytes: Long,
  tableBytes: Long,
  indexesBytes: Long
):
  def writes: Long = inserted + updated + deleted

  def scans: Long = sequentialScans + indexScans

  def deadTupleRatio: Double =
    val accounted = liveTuples + deadTuples
    if accounted <= 0 then 0.0 else deadTuples.toDouble / accounted.toDouble

  def sequentialScanRatio: Double =
    val total = scans
    if total <= 0 then 0.0 else sequentialScans.toDouble / total.toDouble

  def lastVacuumOrAutovacuum: Option[Instant] = (lastVacuum.toList ++ lastAutovacuum.toList).maxOption

/** Cumulative per-index counters from `pg_stat_user_indexes`, plus size and shape. */
final case class IndexStats(
  relation: RelationName,
  name: IndexName,
  method: String,
  isUnique: Boolean,
  isPrimary: Boolean,
  isValid: Boolean,
  definition: String,
  keyColumns: Vector[String],
  includeColumns: Vector[String],
  predicate: Option[String],
  scans: Long,
  tuplesRead: Long,
  sizeBytes: Long,
  constraintBacked: Boolean
):
  def signature: IndexSignature = IndexSignature(
    relation = relation,
    method = method,
    unique = isUnique,
    keyColumns = keyColumns.map(IndexSignature.normalise),
    predicate = predicate.map(IndexSignature.normalise).filter(_.nonEmpty),
    includeColumns = includeColumns.map(IndexSignature.normalise)
  )

/** Two indexes with the same signature serve the same accesses; one of them is dead weight. */
final case class IndexSignature(
  relation: RelationName,
  method: String,
  unique: Boolean,
  keyColumns: Vector[String],
  predicate: Option[String],
  includeColumns: Vector[String]
)

object IndexSignature:
  /** Collapses the quoting and whitespace differences in `pg_get_indexdef` renderings. */
  def normalise(expression: String): String =
    expression.toLowerCase.replace("\"", "").replaceAll("\\s+", " ").trim

/** One row of `pg_stat_statements`, as reported by the extension. */
final case class StatementStats(
  queryId: Long,
  query: String,
  calls: Long,
  totalExecMs: Double,
  meanExecMs: Double,
  rows: Long,
  sharedBlocksHit: Long,
  sharedBlocksRead: Long,
  tempBlocksWritten: Long
):
  def hasParameters: Boolean = StatementStats.ParameterPattern.findFirstIn(query).isDefined

  def normalisedQuery: String = query.replaceAll("\\s+", " ").trim

object StatementStats:
  private val ParameterPattern = "\\$[0-9]+".r

/** One client backend from `pg_stat_activity`. */
final case class ActivitySample(
  pid: Int,
  user: Option[String],
  applicationName: Option[String],
  state: Option[String],
  waitEventType: Option[String],
  waitEvent: Option[String],
  query: Option[String],
  runningFor: Option[Duration],
  backendType: Option[String]
)

/** Everything the heuristics need, captured at a single point in time. */
final case class Snapshot(
  capturedAt: Instant,
  cluster: ClusterInfo,
  tables: Vector[TableStats],
  indexes: Vector[IndexStats],
  statements: Vector[StatementStats],
  activity: Vector[ActivitySample],
  warnings: Vector[String]
):
  def tableOf(relation: RelationName): Option[TableStats] = tables.find(_.relation == relation)

  def indexesOf(relation: RelationName): Vector[IndexStats] = indexes.filter(_.relation == relation)

object Snapshot:
  def empty(capturedAt: Instant): Snapshot = Snapshot(
    capturedAt = capturedAt,
    cluster = ClusterInfo.unknown,
    tables = Vector.empty,
    indexes = Vector.empty,
    statements = Vector.empty,
    activity = Vector.empty,
    warnings = Vector.empty
  )
