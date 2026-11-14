package pgtop.heuristics

import pgtop.domain.{Analysis, Snapshot}

/** Runs a registry of rules over one snapshot. Pure: no IO happens here. */
object Engine:
  def analyse(snapshot: Snapshot, registry: RuleRegistry = RuleRegistry.default()): Analysis =
    Analysis(
      cluster = snapshot.cluster,
      capturedAt = snapshot.capturedAt,
      findings = registry.evaluate(snapshot),
      warnings = snapshot.warnings
    )
