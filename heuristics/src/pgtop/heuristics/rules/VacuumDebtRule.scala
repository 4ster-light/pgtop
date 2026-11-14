package pgtop.heuristics.rules

import pgtop.domain.{Evidence, Finding, Format, Severity, Snapshot, SuggestedFix, TableStats}
import pgtop.heuristics.{Rule, SqlName, Thresholds}

/** Tables carrying dead tuples that autovacuum has not cleaned up: they waste
  * space and slow every sequential scan.
  */
final case class VacuumDebtRule(thresholds: Thresholds) extends Rule:
  def id: String = "vacuum-debt"
  def description: String = "Dead tuples accumulating past the vacuum thresholds"

  def evaluate(snapshot: Snapshot): Vector[Finding] =
    snapshot.tables.filter(qualifies).map(finding)

  private def qualifies(table: TableStats): Boolean =
    table.deadTuples >= thresholds.vacuumDebtMinDeadTuples &&
      table.deadTupleRatio >= thresholds.vacuumDebtRatio

  private def finding(table: TableStats): Finding =
    val severity =
      if table.deadTupleRatio >= thresholds.vacuumDebtHighRatio then Severity.High
      else if table.deadTuples >= thresholds.vacuumDebtMinDeadTuples * 10 then Severity.High
      else Severity.Medium
    val averageRowBytes = if table.liveTuples > 0 then table.tableBytes / table.liveTuples else 0L
    val deadBytes = averageRowBytes * table.deadTuples
    val evidence = Vector(
      Evidence("dead tuples", Format.count(table.deadTuples)),
      Evidence("live tuples", Format.count(table.liveTuples)),
      Evidence("dead tuple ratio", Format.percent(table.deadTupleRatio)),
      Evidence("estimated dead bytes", Format.bytes(deadBytes)),
      Evidence("modifications since analyze", Format.count(table.modificationsSinceAnalyze)),
      Evidence("last vacuum", table.lastVacuumOrAutovacuum.fold("never")(Format.instant)),
      Evidence("last analyze", table.lastAnalyze.orElse(table.lastAutoanalyze).fold("never")(Format.instant))
    )
    Finding(
      rule = id,
      severity = severity,
      title = s"Vacuum debt on ${table.relation.qualified}",
      subject = table.relation.qualified,
      explanation =
        s"${Format.count(table.deadTuples)} dead tuples (${Format.percent(table.deadTupleRatio)} of the table) " +
          s"are still referenced by the visibility map, wasting about ${Format.bytes(deadBytes)} and forcing " +
          s"every scan to step over them. Autovacuum has not brought this down to the configured thresholds.",
      evidence = evidence,
      fix = SuggestedFix(
        summary = s"Vacuum ${table.relation.qualified} now, and lower its autovacuum thresholds so it keeps up.",
        sql =
          s"VACUUM (ANALYZE, VERBOSE) ${SqlName.qualified(table.relation)};\n" +
            s"ALTER TABLE ${SqlName.qualified(table.relation)} SET (autovacuum_vacuum_scale_factor = 0.02, autovacuum_vacuum_threshold = 100);",
        caveats = Vector(
          "Plain VACUUM reclaims space for reuse but does not return it to the operating system.",
          "VACUUM FULL rewrites the table under an ACCESS EXCLUSIVE lock; use pg_repack if you need the space back online.",
          "Check for long-running transactions or abandoned replication slots holding back the xmin horizon first."
        )
      ),
      weight = deadBytes
    )
