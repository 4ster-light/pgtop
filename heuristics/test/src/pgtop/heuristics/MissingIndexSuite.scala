package pgtop.heuristics

import pgtop.domain.Severity
import pgtop.heuristics.rules.MissingIndexRule

class MissingIndexSuite extends munit.FunSuite:
  private val rule = MissingIndexRule(Thresholds.default)

  private val scannedTable = Fixtures.table(
    "orders",
    sequentialScans = 50_000,
    indexScans = 10,
    liveTuples = 1_000_000,
    totalBytes = 500L * 1024 * 1024
  )

  test("suggests a ready-to-run CREATE INDEX CONCURRENTLY from the inferred filter column"):
    val snapshot = Fixtures.snapshot(
      tables = Vector(scannedTable),
      statements = Vector(
        Fixtures.statement("SELECT * FROM public.orders WHERE customer_id = $1", calls = 4_000)
      )
    )

    val findings = rule.evaluate(snapshot)

    assertEquals(findings.length, 1)
    val finding = findings.head
    assertEquals(finding.rule, "missing-index")
    assertEquals(finding.severity, Severity.Critical)
    assert(
      finding.fix.sql.contains("CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_orders_customer_id"),
      finding.fix.sql
    )
    assert(finding.fix.sql.contains("ON public.orders (customer_id)"), finding.fix.sql)
    assert(finding.evidence.exists(_.label == "inferred filter column"))

  test("ignores tables that are already read through indexes"):
    val indexed = scannedTable.copy(sequentialScans = 100, indexScans = 90_000)
    val snapshot = Fixtures.snapshot(tables = Vector(indexed))

    assertEquals(rule.evaluate(snapshot), Vector.empty)

  test("ignores small tables where a sequential scan is the right plan"):
    val small = scannedTable.copy(liveTuples = 200, totalBytes = 64 * 1024)
    val snapshot = Fixtures.snapshot(tables = Vector(small))

    assertEquals(rule.evaluate(snapshot), Vector.empty)

  test("falls back to a diagnostic query when no filter column can be inferred"):
    val snapshot = Fixtures.snapshot(
      tables = Vector(scannedTable),
      statements = Vector(Fixtures.statement("SELECT count(*) FROM public.orders"))
    )

    val findings = rule.evaluate(snapshot)

    assertEquals(findings.length, 1)
    assert(findings.head.fix.sql.contains("FROM pg_stat_statements"), findings.head.fix.sql)

  test("asks for pg_stat_statements when the extension is missing"):
    val snapshot = Fixtures.snapshot(tables = Vector(scannedTable), clusterInfo = Fixtures.withoutStatements)

    val findings = rule.evaluate(snapshot)

    assertEquals(findings.length, 1)
    assert(findings.head.fix.sql.contains("CREATE EXTENSION IF NOT EXISTS pg_stat_statements"))
