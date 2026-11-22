package pgtop.report

import java.time.Instant

import pgtop.domain.*

class DiffReportSuite extends munit.FunSuite:
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

  test("terminal shows the window, deltas and rates"):
    val before = snapshot(
      morning,
      tables = Vector(table("orders", sequentialScans = 100)),
      indexes = Vector(index("orders", "idx_id", scans = 3))
    )
    val after = snapshot(
      evening,
      tables = Vector(table("orders", sequentialScans = 160, deadTuples = 1200, inserted = 2400)),
      indexes = Vector(index("orders", "idx_id", scans = 3))
    )
    val rendered =
      DiffReport.terminal(DiffInput(SnapshotDiff.between(before, after), "0.1.0"), RenderOptions(color = false))

    assert(rendered.contains("database app"))
    assert(rendered.contains(s"from $morning to $evening"))
    assert(rendered.contains("window 12h 0m"))
    assert(rendered.contains("seq scans +60 (5.0/h)"))
    assert(rendered.contains("dead tuples +1.2K (100/h)"))
    assert(rendered.contains("writes +2.4K"))
    assert(rendered.contains("indexes untouched in the window"))
    assert(rendered.contains("public.idx_id"))
    assert(!rendered.contains("\u001b["))

  test("markdown shows the same movement as tables"):
    val before = snapshot(morning, tables = Vector(table("orders", sequentialScans = 100)))
    val after = snapshot(evening, tables = Vector(table("orders", sequentialScans = 160, deadTuples = 1200)))
    val rendered =
      DiffReport.markdown(DiffInput(SnapshotDiff.between(before, after), "0.1.0"), RenderOptions(color = false))

    assert(rendered.startsWith("# pgtop diff"))
    assert(rendered.contains("## Scan traffic"))
    assert(rendered.contains("| `public.orders` | +60 (5.0/h) | 0 | +1.2K (100/h) | 0 |"))

  test("a reset and a database mismatch are flagged in both formats"):
    val before = snapshot(morning, statisticsResetAt = Some(morning))
    val after =
      snapshot(
        evening,
        statisticsResetAt = Some(Instant.parse("2026-11-20T09:00:00Z")),
        database = "other"
      )
    val diff = SnapshotDiff.between(before, after)

    val terminal = DiffReport.terminal(DiffInput(diff, "0.1.0"), RenderOptions(color = false))
    val markdown = DiffReport.markdown(DiffInput(diff, "0.1.0"), RenderOptions(color = false))

    assert(terminal.contains("counters were reset between the snapshots"))
    assert(terminal.contains("different databases (app, other)"))
    assert(markdown.contains("Counters were reset between the snapshots"))
    assert(markdown.contains("The snapshots come from different databases (`app`, `other`)"))

  test("identical snapshots say so, in both formats"):
    val still = snapshot(morning)
    val diff = SnapshotDiff.between(still, still)

    val terminal = DiffReport.terminal(DiffInput(diff, "0.1.0"), RenderOptions(color = false))
    val markdown = DiffReport.markdown(DiffInput(diff, "0.1.0"), RenderOptions(color = false))

    assert(terminal.contains("Nothing changed between the snapshots."))
    assert(markdown.contains("Nothing changed between the snapshots."))

  test("created and dropped objects are listed"):
    val before =
      snapshot(
        morning,
        tables = Vector(table("sessions", 10)),
        indexes = Vector(index("sessions", "idx_sessions_id", scans = 1))
      )
    val after =
      snapshot(
        evening,
        tables = Vector(table("audit_log", 10)),
        indexes = Vector(index("audit_log", "idx_audit_id", scans = 1))
      )
    val rendered =
      DiffReport.terminal(DiffInput(SnapshotDiff.between(before, after), "0.1.0"), RenderOptions(color = false))

    assert(rendered.contains("created during the window"))
    assert(rendered.contains("table public.audit_log"))
    assert(rendered.contains("index public.idx_audit_id"))
    assert(rendered.contains("dropped during the window"))
    assert(rendered.contains("table public.sessions"))
    assert(rendered.contains("index public.idx_sessions_id"))

  test("statements are ranked by added execution time, new ones labelled"):
    val before = snapshot(morning, statements = Vector(statement(7, "SELECT 1", 10, 100)))
    val after =
      snapshot(
        evening,
        statements = Vector(statement(7, "SELECT 1", 30, 300), statement(9, "SELECT now()", 5, 5))
      )
    val rendered =
      DiffReport.terminal(DiffInput(SnapshotDiff.between(before, after), "0.1.0"), RenderOptions(color = false))

    assert(rendered.contains("statements, ranked by added execution time"))
    assert(rendered.indexOf("SELECT 1") < rendered.indexOf("SELECT now()"))
    assert(rendered.contains("calls +20"))
    assert(rendered.contains("exec +200.0 ms"))
    assert(rendered.contains("new in the window"))

  test("the limit caps each section and says how much was hidden"):
    val before =
      snapshot(morning, tables = Vector.tabulate(30)(position => table(s"t$position", sequentialScans = 100)))
    val after =
      snapshot(evening, tables = Vector.tabulate(30)(position => table(s"t$position", sequentialScans = 160)))
    val rendered =
      DiffReport.terminal(
        DiffInput(SnapshotDiff.between(before, after), "0.1.0"),
        RenderOptions(limit = 5, color = false)
      )

    assert(rendered.contains("+ 25 more changed tables"))
