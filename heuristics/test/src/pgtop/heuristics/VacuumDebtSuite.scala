package pgtop.heuristics

import pgtop.domain.Severity
import pgtop.heuristics.rules.VacuumDebtRule

class VacuumDebtSuite extends munit.FunSuite:
  private val rule = VacuumDebtRule(Thresholds.default)

  private val bloated = Fixtures.table(
    "events",
    liveTuples = 40_000,
    deadTuples = 60_000,
    tableBytes = 200L * 1024 * 1024,
    totalBytes = 220L * 1024 * 1024,
    modificationsSinceAnalyze = 80_000
  )

  test("reports dead tuples with a VACUUM and autovacuum tuning fix"):
    val findings = rule.evaluate(Fixtures.snapshot(tables = Vector(bloated)))

    assertEquals(findings.length, 1)
    val finding = findings.head
    assertEquals(finding.rule, "vacuum-debt")
    assertEquals(finding.severity, Severity.High)
    assert(finding.fix.sql.contains("VACUUM (ANALYZE, VERBOSE) public.events;"), finding.fix.sql)
    assert(finding.fix.sql.contains("autovacuum_vacuum_scale_factor"), finding.fix.sql)
    assert(finding.explanation.contains("60.0%"), finding.explanation)

  test("ignores tables with a small number of dead tuples"):
    val healthy = bloated.copy(deadTuples = 20, liveTuples = 100_000)
    assertEquals(rule.evaluate(Fixtures.snapshot(tables = Vector(healthy))), Vector.empty)

  test("ignores tables below the ratio threshold"):
    val fewDead = bloated.copy(liveTuples = 1_000_000, deadTuples = 5_000)
    assertEquals(rule.evaluate(Fixtures.snapshot(tables = Vector(fewDead))), Vector.empty)
