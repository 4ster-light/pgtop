package pgtop.heuristics

import pgtop.domain.Severity
import pgtop.heuristics.rules.DuplicateIndexRule

class DuplicateIndexSuite extends munit.FunSuite:
  private val rule = DuplicateIndexRule(Thresholds.default)

  private val keeper = Fixtures.index(
    tableName = "orders",
    indexName = "idx_orders_customer_id",
    keyColumns = Vector("customer_id"),
    scans = 40_000,
    sizeBytes = 180L * 1024 * 1024
  )

  private val duplicate = Fixtures.index(
    tableName = "orders",
    indexName = "idx_orders_customer_id_old",
    keyColumns = Vector("customer_id"),
    scans = 0,
    sizeBytes = 200L * 1024 * 1024
  )

  test("drops the unused twin and keeps the index the planner uses"):
    val findings =
      rule.evaluate(Fixtures.snapshot(tables = Vector(Fixtures.table("orders")), indexes = Vector(keeper, duplicate)))

    assertEquals(findings.length, 1)
    val finding = findings.head
    assertEquals(finding.rule, "duplicate-index")
    assertEquals(finding.severity, Severity.Medium)
    assertEquals(finding.subject, "public.idx_orders_customer_id_old")
    assert(finding.fix.sql.contains("DROP INDEX CONCURRENTLY IF EXISTS public.idx_orders_customer_id_old"))

  test("keeps the constraint-backed index when one of the twins backs a constraint"):
    val uniqueKeeper = keeper.copy(isUnique = true)
    val constraintBacked = duplicate.copy(constraintBacked = true, isUnique = true)
    val findings = rule.evaluate(Fixtures.snapshot(indexes = Vector(uniqueKeeper, constraintBacked)))

    assertEquals(findings.length, 1)
    assertEquals(findings.head.subject, "public.idx_orders_customer_id")

  test("treats different predicates as different indexes"):
    val partial = duplicate.copy(predicate = Some("status = 'new'"))
    assertEquals(rule.evaluate(Fixtures.snapshot(indexes = Vector(keeper, partial))), Vector.empty)

  test("treats a different key order as a different index"):
    val reordered = duplicate.copy(keyColumns = Vector("status", "customer_id"))
    assertEquals(rule.evaluate(Fixtures.snapshot(indexes = Vector(keeper, reordered))), Vector.empty)

  test("ignores invalid duplicates"):
    val invalid = duplicate.copy(isValid = false)
    assertEquals(rule.evaluate(Fixtures.snapshot(indexes = Vector(keeper, invalid))), Vector.empty)
