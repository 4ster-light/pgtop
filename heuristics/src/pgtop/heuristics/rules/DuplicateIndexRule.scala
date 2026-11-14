package pgtop.heuristics.rules

import pgtop.domain.{Evidence, Finding, Format, IndexSignature, IndexStats, Severity, Snapshot, SuggestedFix}
import pgtop.heuristics.{Rule, SqlName, Thresholds}

/** Indexes with the same table, access method, key columns, predicate and
  * uniqueness: one of each pair is pure write overhead.
  */
final case class DuplicateIndexRule(thresholds: Thresholds) extends Rule:
  def id: String = "duplicate-index"
  def description: String = "Indexes that are interchangeable for the planner"

  def evaluate(snapshot: Snapshot): Vector[Finding] =
    snapshot.indexes
      .filter(index => index.isValid && index.sizeBytes >= thresholds.duplicateIndexMinBytes)
      .groupBy(_.signature)
      .toVector
      .flatMap { case (signature, members) => duplicatesOf(signature, members) }

  private def duplicatesOf(signature: IndexSignature, members: Vector[IndexStats]): Vector[Finding] =
    if members.lengthIs < 2 then Vector.empty
    else
      val keeper = members.minBy(keepPriority)
      members.filterNot(_ eq keeper).map(duplicate => finding(signature, keeper, duplicate)).toVector

  /** Prefer the index that also backs a constraint, then the one the planner uses most. */
  private def keepPriority(index: IndexStats): (Int, Int, Long, Long) =
    (
      if index.constraintBacked then 0 else 1,
      if index.isPrimary then 0 else 1,
      -index.scans,
      index.sizeBytes
    )

  private def finding(signature: IndexSignature, keeper: IndexStats, duplicate: IndexStats): Finding =
    val severity =
      if duplicate.sizeBytes >= 1024L * 1024 * 1024 then Severity.High
      else if duplicate.sizeBytes >= 100L * 1024 * 1024 then Severity.Medium
      else Severity.Low
    val includeNote =
      if signature.includeColumns.isEmpty then Vector.empty
      else Vector(s"Both indexes carry INCLUDE (${signature.includeColumns.mkString(", ")}).")
    Finding(
      rule = id,
      severity = severity,
      title = s"Duplicate index ${duplicate.name.qualified}",
      subject = duplicate.name.qualified,
      explanation =
        s"${duplicate.name.qualified} and ${keeper.name.qualified} cover the same key columns " +
          s"(${signature.keyColumns.mkString(", ")}) with the same method and predicate. " +
          s"Keeping ${keeper.name.qualified} and dropping the other removes ${Format.bytes(duplicate.sizeBytes)} " +
          s"and one index update per write.",
      evidence = Vector(
        Evidence("kept", keeper.definition),
        Evidence("dropped", duplicate.definition),
        Evidence("duplicate scans", Format.count(duplicate.scans)),
        Evidence("keeper scans", Format.count(keeper.scans)),
        Evidence("duplicate size", Format.bytes(duplicate.sizeBytes))
      ),
      fix = SuggestedFix(
        summary = s"Drop ${duplicate.name.qualified}; ${keeper.name.qualified} serves the same accesses.",
        sql = s"DROP INDEX CONCURRENTLY IF EXISTS ${SqlName.qualified(duplicate.name)};",
        caveats = Vector(
          "CONCURRENTLY cannot run inside a transaction block and takes longer than a plain DROP.",
          "Verify with EXPLAIN that the keeper is chosen for the queries that used the duplicate."
        ) ++ includeNote
      ),
      weight = duplicate.sizeBytes
    )
