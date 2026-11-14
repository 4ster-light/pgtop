package pgtop.heuristics

import pgtop.domain.Severity
import pgtop.heuristics.rules.ExpensiveStatementRule

class ExpensiveStatementSuite extends munit.FunSuite:
  private val rule = ExpensiveStatementRule(Thresholds.default)

  private val expensive = Fixtures.statement(
    "SELECT * FROM public.orders WHERE status = $1 ORDER BY created_at DESC",
    calls = 9_000,
    totalMs = 900_000,
    meanMs = 100,
    queryId = 42
  )

  test("ranks the statement by cumulative time and asks for a plan"):
    val findings = rule.evaluate(Fixtures.snapshot(statements = Vector(expensive)))

    assertEquals(findings.length, 1)
    val finding = findings.head
    assertEquals(finding.rule, "expensive-statement")
    assertEquals(finding.severity, Severity.Critical)
    assertEquals(finding.weight, 900_000L)
    assert(finding.fix.sql.startsWith("EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT) SELECT"), finding.fix.sql)
    assert(finding.fix.caveats.exists(_.contains("parameterised")), finding.fix.caveats.toString)

  test("keeps only the most expensive statements, in order"):
    val second = expensive.copy(queryId = 43, totalExecMs = 120_000, query = "SELECT 1")
    val findings = rule.evaluate(Fixtures.snapshot(statements = Vector(second, expensive)))

    assertEquals(findings.map(_.weight), Vector(900_000L, 120_000L))

  test("ignores statements below the cumulative time threshold"):
    val cheap = expensive.copy(totalExecMs = 1_000)
    assertEquals(rule.evaluate(Fixtures.snapshot(statements = Vector(cheap))), Vector.empty)

  test("mentions temporary blocks when the plan spills to disk"):
    val spilled = expensive.copy(tempBlocksWritten = 4_096)
    val findings = rule.evaluate(Fixtures.snapshot(statements = Vector(spilled)))

    assertEquals(findings.length, 1)
    assert(findings.head.evidence.exists(_.label == "temporary blocks written"))
    assert(findings.head.fix.caveats.exists(_.contains("work_mem")))
