package pgtop.heuristics.rules

import pgtop.domain.{Evidence, Finding, Format, IndexStats, Severity, Snapshot, SuggestedFix}
import pgtop.heuristics.{Rule, SqlName, Thresholds}

/** Indexes that cost writes and disk without being read. */
final case class UnusedIndexRule(thresholds: Thresholds) extends Rule:
  def id: String = "unused-index"
  def description: String = "Valid, non-constraint indexes with no or negligible scans"

  def evaluate(snapshot: Snapshot): Vector[Finding] =
    snapshot.indexes.filter(qualifies).map(index => finding(snapshot, index))

  private def qualifies(index: IndexStats): Boolean =
    index.isValid &&
      !index.constraintBacked &&
      !index.isPrimary &&
      index.scans <= thresholds.unusedIndexMaxScans &&
      index.sizeBytes >= thresholds.unusedIndexMinBytes

  private def finding(snapshot: Snapshot, index: IndexStats): Finding =
    val severity =
      if index.sizeBytes >= 1024L * 1024 * 1024 then Severity.Critical
      else if index.sizeBytes >= 100L * 1024 * 1024 then Severity.High
      else if index.sizeBytes >= 10L * 1024 * 1024 then Severity.Medium
      else Severity.Low
    val table = snapshot.tableOf(index.relation)
    val evidence = Vector(
      Evidence("index scans", Format.count(index.scans)),
      Evidence("index size", Format.bytes(index.sizeBytes)),
      Evidence("definition", index.definition)
    ) ++ table.map(t => Evidence("table size", Format.bytes(t.totalBytes))).toVector ++
      snapshot.cluster.statisticsResetAt.map(reset => Evidence("statistics reset at", Format.instant(reset))).toVector
    Finding(
      rule = id,
      severity = severity,
      title = s"Index ${index.name.qualified} has not been used",
      subject = index.name.qualified,
      explanation =
        s"${Format.count(index.scans)} scans since the statistics were last reset while occupying " +
          s"${Format.bytes(index.sizeBytes)}. Every write to ${index.relation.qualified} maintains it.",
      evidence = evidence,
      fix = SuggestedFix(
        summary = s"Drop ${index.name.qualified}; nothing reads it.",
        sql = s"DROP INDEX CONCURRENTLY IF EXISTS ${SqlName.qualified(index.name)};",
        caveats = Vector(
          "Counters reset with pg_stat_reset(); an index created after the reset has had little time to be used.",
          "Check for monthly or ad-hoc reports that run rarely before dropping.",
          "DROP INDEX CONCURRENTLY cannot run inside a transaction block."
        )
      ),
      weight = index.sizeBytes
    )
