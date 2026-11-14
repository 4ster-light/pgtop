package pgtop.heuristics

import pgtop.domain.Severity
import pgtop.heuristics.rules.UnusedIndexRule

class UnusedIndexSuite extends munit.FunSuite:
  private val rule = UnusedIndexRule(Thresholds.default)

  private val unused = Fixtures.index(
    tableName = "orders",
    indexName = "idx_orders_email",
    keyColumns = Vector("email"),
    scans = 0,
    sizeBytes = 200L * 1024 * 1024
  )

  test("recommends DROP INDEX CONCURRENTLY for a large index nothing reads"):
    val snapshot = Fixtures.snapshot(
      tables = Vector(Fixtures.table("orders", liveTuples = 1_000_000)),
      indexes = Vector(unused)
    )

    val findings = rule.evaluate(snapshot)

    assertEquals(findings.length, 1)
    val finding = findings.head
    assertEquals(finding.rule, "unused-index")
    assertEquals(finding.severity, Severity.High)
    assertEquals(finding.subject, "public.idx_orders_email")
    assert(finding.fix.sql.contains("DROP INDEX CONCURRENTLY IF EXISTS public.idx_orders_email"), finding.fix.sql)
    assert(finding.fix.caveats.exists(_.contains("pg_stat_reset")))

  test("ignores indexes that back a constraint"):
    val unique = unused.copy(constraintBacked = true, isUnique = true)
    assertEquals(rule.evaluate(Fixtures.snapshot(indexes = Vector(unique))), Vector.empty)

  test("ignores indexes below the size threshold"):
    val small = unused.copy(sizeBytes = 512 * 1024)
    assertEquals(rule.evaluate(Fixtures.snapshot(indexes = Vector(small))), Vector.empty)

  test("ignores indexes that are actually used"):
    val used = unused.copy(scans = 12_000)
    assertEquals(rule.evaluate(Fixtures.snapshot(indexes = Vector(used))), Vector.empty)

  test("ignores invalid indexes: they are not maintained at all"):
    val invalid = unused.copy(isValid = false)
    assertEquals(rule.evaluate(Fixtures.snapshot(indexes = Vector(invalid))), Vector.empty)
