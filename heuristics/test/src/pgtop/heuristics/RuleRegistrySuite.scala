package pgtop.heuristics

import pgtop.domain.Severity
import pgtop.heuristics.rules.UnusedIndexRule

class RuleRegistrySuite extends munit.FunSuite:
  test("the default registry carries every shipped rule, in order"):
    val ids = RuleRegistry.default().ids

    assertEquals(
      ids,
      Vector("missing-index", "unused-index", "vacuum-debt", "duplicate-index", "expensive-statement")
    )

  test("rules can be added without touching existing ones"):
    val extra = new Rule:
      def id: String = "custom"
      def description: String = "test rule"
      def evaluate(snapshot: pgtop.domain.Snapshot): Vector[pgtop.domain.Finding] = Vector.empty

    val registry = RuleRegistry(Vector(UnusedIndexRule(Thresholds.default))).withRule(extra)

    assertEquals(registry.ids, Vector("unused-index", "custom"))

  test("adding a rule twice keeps the first registration"):
    val registry = RuleRegistry.default().withRule(UnusedIndexRule(Thresholds.default))

    assertEquals(registry.ids.length, 5)

  test("the engine analyses a snapshot and keeps its warnings"):
    val snapshot = Fixtures.snapshot(warnings = Vector("pg_stat_statements missing"))
    val analysis = Engine.analyse(snapshot)

    assertEquals(analysis.capturedAt, Fixtures.capturedAt)
    assertEquals(analysis.warnings, Vector("pg_stat_statements missing"))
    assert(analysis.findings.isEmpty)

  test("the engine turns a bloated table into a ranked finding"):
    val snapshot = Fixtures.snapshot(
      tables = Vector(Fixtures.table("events", liveTuples = 10, deadTuples = 5_000, tableBytes = 64L * 1024 * 1024))
    )

    val analysis = Engine.analyse(snapshot)

    assertEquals(analysis.findings.length, 1)
    assertEquals(analysis.worstSeverity, Some(Severity.High))
