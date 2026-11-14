package pgtop.report

import java.time.Instant

import pgtop.domain.*

class RenderSuite extends munit.FunSuite:
  private val capturedAt = Instant.parse("2026-11-13T10:00:00Z")

  private val cluster = ClusterInfo(
    serverVersion = "PostgreSQL 17.2 on x86_64-pc-linux-gnu",
    database = "app",
    postmasterStartedAt = Some(Instant.parse("2026-11-02T08:00:00Z")),
    statisticsResetAt = Some(Instant.parse("2026-11-01T00:00:00Z")),
    pgStatStatementsVersion = Some("1.11")
  )

  private val unusedIndex = Finding(
    rule = "unused-index",
    severity = Severity.High,
    title = "Index public.idx_orders_email has not been used",
    subject = "public.idx_orders_email",
    explanation = "Zero scans since the statistics were last reset while occupying 200 MiB.",
    evidence = Vector(Evidence("index size", "200.0 MiB")),
    fix = SuggestedFix(
      summary = "Drop public.idx_orders_email; nothing reads it.",
      sql = "DROP INDEX CONCURRENTLY IF EXISTS public.idx_orders_email;",
      caveats = Vector("Counters reset with pg_stat_reset().")
    ),
    weight = 200L * 1024 * 1024
  )

  private val vacuumDebt = Finding(
    rule = "vacuum-debt",
    severity = Severity.Medium,
    title = "Vacuum debt on public.events",
    subject = "public.events",
    explanation = "Dead tuples are piling up.",
    evidence = Vector(Evidence("dead tuples", "60.0K")),
    fix = SuggestedFix(
      summary = "Vacuum public.events now.",
      sql = "VACUUM (ANALYZE, VERBOSE) public.events;",
      caveats = Vector.empty
    ),
    weight = 90L * 1024 * 1024
  )

  private def analysis(findings: Vector[Finding]): Analysis =
    Analysis(cluster, capturedAt, findings, Vector("pg_stat_statements is not installed in this database"))

  private val plans = Vector(
    PlanSummary("SELECT * FROM orders WHERE customer_id = $1 [42]", 0.4, 812.5, "Seq Scan", Some("orders"), 790.0)
  )

  test("markdown export carries the advice, the SQL fence and the notes"):
    val rendered = MarkdownReport.render(
      ReportInput(analysis(Vector(unusedIndex)), Vector.empty, "0.1.0"),
      RenderOptions(color = false)
    )

    assert(rendered.startsWith("# pgtop report"))
    assert(rendered.contains("[HIGH] Index public.idx_orders_email has not been used"))
    assert(rendered.contains("```sql"))
    assert(rendered.contains("DROP INDEX CONCURRENTLY IF EXISTS public.idx_orders_email;"))
    assert(rendered.contains("- Counters reset with pg_stat_reset()."))
    assert(rendered.contains("## Notes"))
    assert(rendered.contains("pg_stat_statements is not installed"))

  test("markdown escapes pipes so table rows survive"):
    val withPipe = unusedIndex.copy(evidence = Vector(Evidence("definition", "a | b")))
    val rendered = MarkdownReport.render(
      ReportInput(analysis(Vector(withPipe)), Vector.empty, "0.1.0"),
      RenderOptions(color = false)
    )

    assert(rendered.contains("| definition | a \\| b |"))

  test("terminal output without color has no ANSI escapes"):
    val rendered = TerminalReport.render(
      ReportInput(analysis(Vector(unusedIndex)), Vector.empty, "0.1.0"),
      RenderOptions(color = false)
    )

    assert(!rendered.contains("\u001b["))
    assert(rendered.contains("HIGH"))
    assert(rendered.contains("DROP INDEX CONCURRENTLY IF EXISTS public.idx_orders_email;"))

  test("terminal output with color uses ANSI escapes"):
    val rendered = TerminalReport.render(
      ReportInput(analysis(Vector(unusedIndex)), Vector.empty, "0.1.0"),
      RenderOptions(color = true)
    )

    assert(rendered.contains("\u001b["))

  test("the limit keeps the highest ranked findings only"):
    val rendered = TerminalReport.render(
      ReportInput(analysis(Vector(vacuumDebt, unusedIndex)), Vector.empty, "0.1.0"),
      RenderOptions(limit = 1, color = false)
    )

    assert(rendered.contains("Index public.idx_orders_email has not been used"))
    assert(!rendered.contains("Vacuum debt on public.events"))

  test("min-severity hides findings below the threshold"):
    val rendered = TerminalReport.render(
      ReportInput(analysis(Vector(vacuumDebt)), Vector.empty, "0.1.0"),
      RenderOptions(minSeverity = Severity.High, color = false)
    )

    assert(rendered.contains("No findings above the current threshold."))

  test("an empty report says so instead of printing an empty table"):
    val rendered =
      TerminalReport.render(ReportInput(Analysis.empty, Vector.empty, "0.1.0"), RenderOptions(color = false))

    assert(rendered.contains("No findings above the current threshold."))

  test("plan summaries appear in both formats"):
    val terminal =
      TerminalReport.render(ReportInput(analysis(Vector(unusedIndex)), plans, "0.1.0"), RenderOptions(color = false))
    val markdown =
      MarkdownReport.render(ReportInput(analysis(Vector(unusedIndex)), plans, "0.1.0"), RenderOptions(color = false))

    assert(terminal.contains("query plans"))
    assert(terminal.contains("Seq Scan on orders"))
    assert(markdown.contains("## Query plans"))
    assert(markdown.contains("Seq Scan on orders"))
