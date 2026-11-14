package pgtop.heuristics

import pgtop.domain.{Finding, Snapshot}
import pgtop.heuristics.rules.*

/** An ordered collection of rules, evaluated independently over one snapshot. */
final case class RuleRegistry(rules: Vector[Rule]):
  def ids: Vector[String] = rules.map(_.id)

  def evaluate(snapshot: Snapshot): Vector[Finding] = rules.flatMap(_.evaluate(snapshot))

  def withRule(rule: Rule): RuleRegistry =
    if rules.exists(_.id == rule.id) then this else copy(rules = rules :+ rule)

object RuleRegistry:
  def default(thresholds: Thresholds = Thresholds.default): RuleRegistry =
    RuleRegistry(
      Vector(
        MissingIndexRule(thresholds),
        UnusedIndexRule(thresholds),
        VacuumDebtRule(thresholds),
        DuplicateIndexRule(thresholds),
        ExpensiveStatementRule(thresholds)
      )
    )
