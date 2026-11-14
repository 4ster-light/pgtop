package pgtop.heuristics

import pgtop.domain.{Finding, Snapshot}

/** One piece of analysis. Rules are pure: they read a snapshot and return
  * findings, nothing else. Adding a rule means adding an object and appending it
  * to `RuleRegistry.default`, no other file changes.
  */
trait Rule:
  /** Stable identifier, used in reports and to correlate findings over time. */
  def id: String

  /** One line describing what the rule looks for. */
  def description: String

  def evaluate(snapshot: Snapshot): Vector[Finding]
