package pgtop.domain

import java.time.{Duration, Instant}

class SnapshotDiffSuite extends munit.FunSuite:
  private val morning = Instant.parse("2026-11-20T08:00:00Z")
  private val evening = Instant.parse("2026-11-20T20:00:00Z")

  private def table(
    name: String,
    sequentialScans: Long,
    indexScans: Long = 0,
    deadTuples: Long = 0,
    inserted: Long = 0
  ): TableStats =
    TableStats(
      relation = RelationName("public", name),
      sequentialScans = sequentialScans,
      indexScans = indexScans,
      liveTuples = 0,
      deadTuples = deadTuples,
      modificationsSinceAnalyze = 0,
      inserted = inserted,
      updated = 0,
      deleted = 0,
      lastVacuum = None,
      lastAutovacuum = None,
      lastAnalyze = None,
      lastAutoanalyze = None,
      totalBytes = 0,
      tableBytes = 0,
      indexesBytes = 0
    )

  private def index(tableName: String, indexName: String, scans: Long, tuplesRead: Long = 0): IndexStats =
    IndexStats(
      relation = RelationName("public", tableName),
      name = IndexName("public", indexName),
      method = "btree",
      isUnique = false,
      isPrimary = false,
      isValid = true,
      definition = s"CREATE INDEX $indexName ON public.$tableName (id)",
      keyColumns = Vector("id"),
      includeColumns = Vector.empty,
      predicate = None,
      scans = scans,
      tuplesRead = tuplesRead,
      sizeBytes = 8L * 1024 * 1024,
      constraintBacked = false
    )

  private def statement(queryId: Long, query: String, calls: Long, totalExecMs: Double): StatementStats =
    StatementStats(
      queryId = queryId,
      query = query,
      calls = calls,
      totalExecMs = totalExecMs,
      meanExecMs = if calls > 0 then totalExecMs / calls else 0.0,
      rows = 0,
      sharedBlocksHit = 0,
      sharedBlocksRead = 0,
      tempBlocksWritten = 0
    )

  private def snapshot(
    at: Instant,
    tables: Vector[TableStats] = Vector.empty,
    indexes: Vector[IndexStats] = Vector.empty,
    statements: Vector[StatementStats] = Vector.empty,
    statisticsResetAt: Option[Instant] = None,
    database: String = "app"
  ): Snapshot =
    Snapshot(
      capturedAt = at,
      cluster = ClusterInfo("PostgreSQL 17.2", database, None, statisticsResetAt, None),
      tables = tables,
      indexes = indexes,
      statements = statements,
      activity = Vector.empty,
      warnings = Vector.empty
    )

  test("orients by capture time, whichever order the arguments come in"):
    val earlier = snapshot(morning)
    val later = snapshot(evening)

    assertEquals(SnapshotDiff.between(later, earlier).before.capturedAt, morning)
    assertEquals(SnapshotDiff.between(earlier, later).before.capturedAt, morning)
    assertEquals(SnapshotDiff.between(earlier, later).window, Duration.ofHours(12))

  test("turns counters into deltas and per-hour rates"):
    val before = snapshot(morning, tables = Vector(table("orders", sequentialScans = 100, deadTuples = 20)))
    val after = snapshot(evening, tables = Vector(table("orders", sequentialScans = 160, deadTuples = 1220)))
    val orders = SnapshotDiff.between(before, after).tables.head

    assertEquals(orders.sequentialScansDelta, Some(60L))
    assertEquals(orders.deadTuplesDelta, Some(1200L))
    assertEquals(orders.sequentialScansPerHour, Some(5.0))
    assertEquals(orders.deadTuplesPerHour, Some(100.0))

  test("a window with no length has no rates"):
    val still = snapshot(morning, tables = Vector(table("orders", sequentialScans = 5)))

    assertEquals(SnapshotDiff.between(still, still).tables.head.sequentialScansPerHour, None)

  test("tables that exist on one side only are marked, not paired"):
    val before = snapshot(morning, tables = Vector(table("sessions", 10)))
    val after = snapshot(evening, tables = Vector(table("audit_log", 10)))
    val diff = SnapshotDiff.between(before, after)

    assertEquals(diff.tables.length, 2)
    assert(diff.tables.exists(row => row.relation.name == "sessions" && row.dropped))
    assert(diff.tables.exists(row => row.relation.name == "audit_log" && row.appeared))

  test("an index is untouched only when neither scans nor tuple reads moved"):
    val before = snapshot(morning, indexes = Vector(index("orders", "idx_id", scans = 4)))
    val after = snapshot(evening, indexes = Vector(index("orders", "idx_id", scans = 4)))
    val moved = snapshot(evening, indexes = Vector(index("orders", "idx_id", scans = 4, tuplesRead = 40)))

    assertEquals(SnapshotDiff.between(before, after).indexes.head.usedInWindow, Some(false))
    assertEquals(SnapshotDiff.between(before, moved).indexes.head.usedInWindow, Some(true))

  test("flags a statistics reset between the two snapshots"):
    val before = snapshot(morning, statisticsResetAt = Some(morning))
    val after = snapshot(evening, statisticsResetAt = Some(Instant.parse("2026-11-20T09:00:00Z")))
    val sameReset = snapshot(evening, statisticsResetAt = Some(morning))

    assert(SnapshotDiff.between(before, after).countersReset)
    assert(!SnapshotDiff.between(before, sameReset).countersReset)

  test("flags snapshots from different databases"):
    assert(SnapshotDiff.between(snapshot(morning), snapshot(evening, database = "other")).databasesDiffer)

  test("statements pair by queryid and prefer the later query text"):
    val before = snapshot(morning, statements = Vector(statement(7, "SELECT * FROM t", 10, 100)))
    val after = snapshot(evening, statements = Vector(statement(7, "SELECT count(*) FROM t", 30, 300)))
    val diff = SnapshotDiff.between(before, after).statements.head

    assertEquals(diff.query, "SELECT count(*) FROM t")
    assertEquals(diff.callsDelta, Some(20L))
    assertEquals(diff.totalExecMsDelta, Some(200.0))
    assertEquals(diff.totalExecMsPerHour.map(rate => math.round(rate * 10)), Some(167L))

  test("duplicate queryids within one snapshot keep the busiest copy"):
    val before =
      snapshot(morning, statements = Vector(statement(7, "SELECT 1", 2, 50), statement(7, "SELECT 1", 10, 100)))
    val after = snapshot(evening, statements = Vector(statement(7, "SELECT 1", 20, 200)))
    val diff = SnapshotDiff.between(before, after).statements

    assertEquals(diff.length, 1)
    assertEquals(diff.head.callsDelta, Some(10L))

  test("indexes pair by relation and name together"):
    val before = snapshot(morning, indexes = Vector(index("orders", "idx_id", 1)))
    val after = snapshot(evening, indexes = Vector(index("events", "idx_id", 1)))

    assertEquals(SnapshotDiff.between(before, after).indexes.length, 2)
