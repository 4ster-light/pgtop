package pgtop.heuristics

import java.time.Instant

import pgtop.domain.*

/** Builders for the snapshot fixtures the rule tests reason about. */
object Fixtures:
  val capturedAt: Instant = Instant.parse("2026-11-13T10:00:00Z")
  val statisticsResetAt: Instant = Instant.parse("2026-11-01T00:00:00Z")

  val cluster: ClusterInfo = ClusterInfo(
    serverVersion = "PostgreSQL 17.2 on x86_64-pc-linux-gnu",
    database = "app",
    postmasterStartedAt = Some(Instant.parse("2026-11-02T08:00:00Z")),
    statisticsResetAt = Some(statisticsResetAt),
    pgStatStatementsVersion = Some("1.11")
  )

  val withoutStatements: ClusterInfo = cluster.copy(pgStatStatementsVersion = None)

  def relation(name: String, schema: String = "public"): RelationName = RelationName(schema, name)

  def table(
    name: String,
    sequentialScans: Long = 0,
    indexScans: Long = 0,
    liveTuples: Long = 0,
    deadTuples: Long = 0,
    totalBytes: Long = 8L * 1024 * 1024,
    tableBytes: Long = 8L * 1024 * 1024,
    indexesBytes: Long = 0,
    inserted: Long = 0,
    updated: Long = 0,
    deleted: Long = 0,
    modificationsSinceAnalyze: Long = 0,
    lastVacuum: Option[Instant] = None,
    lastAutovacuum: Option[Instant] = Some(statisticsResetAt),
    lastAnalyze: Option[Instant] = None,
    lastAutoanalyze: Option[Instant] = Some(statisticsResetAt),
    schema: String = "public"
  ): TableStats =
    TableStats(
      relation = relation(name, schema),
      sequentialScans = sequentialScans,
      indexScans = indexScans,
      liveTuples = liveTuples,
      deadTuples = deadTuples,
      modificationsSinceAnalyze = modificationsSinceAnalyze,
      inserted = inserted,
      updated = updated,
      deleted = deleted,
      lastVacuum = lastVacuum,
      lastAutovacuum = lastAutovacuum,
      lastAnalyze = lastAnalyze,
      lastAutoanalyze = lastAutoanalyze,
      totalBytes = totalBytes,
      tableBytes = tableBytes,
      indexesBytes = indexesBytes
    )

  def index(
    tableName: String,
    indexName: String,
    keyColumns: Vector[String] = Vector("id"),
    scans: Long = 0,
    sizeBytes: Long = 8L * 1024 * 1024,
    isUnique: Boolean = false,
    isPrimary: Boolean = false,
    isValid: Boolean = true,
    constraintBacked: Boolean = false,
    includeColumns: Vector[String] = Vector.empty,
    predicate: Option[String] = None,
    method: String = "btree",
    schema: String = "public"
  ): IndexStats =
    val columns = keyColumns.mkString(", ")
    IndexStats(
      relation = relation(tableName, schema),
      name = IndexName(schema, indexName),
      method = method,
      isUnique = isUnique,
      isPrimary = isPrimary,
      isValid = isValid,
      definition = s"CREATE INDEX $indexName ON $schema.$tableName USING $method ($columns)" +
        predicate.fold("")(p => s" WHERE $p"),
      keyColumns = keyColumns,
      includeColumns = includeColumns,
      predicate = predicate,
      scans = scans,
      tuplesRead = scans * 10,
      sizeBytes = sizeBytes,
      constraintBacked = constraintBacked
    )

  def statement(
    query: String,
    calls: Long = 100,
    totalMs: Double = 5_000,
    meanMs: Double = 50,
    rows: Long = 10,
    sharedBlocksRead: Long = 0,
    tempBlocksWritten: Long = 0,
    queryId: Long = 1
  ): StatementStats =
    StatementStats(
      queryId = queryId,
      query = query,
      calls = calls,
      totalExecMs = totalMs,
      meanExecMs = meanMs,
      rows = rows,
      sharedBlocksHit = 0,
      sharedBlocksRead = sharedBlocksRead,
      tempBlocksWritten = tempBlocksWritten
    )

  def snapshot(
    tables: Vector[TableStats] = Vector.empty,
    indexes: Vector[IndexStats] = Vector.empty,
    statements: Vector[StatementStats] = Vector.empty,
    activity: Vector[ActivitySample] = Vector.empty,
    clusterInfo: ClusterInfo = cluster,
    warnings: Vector[String] = Vector.empty
  ): Snapshot =
    Snapshot(
      capturedAt = capturedAt,
      cluster = clusterInfo,
      tables = tables,
      indexes = indexes,
      statements = statements,
      activity = activity,
      warnings = warnings
    )
