package pgtop.heuristics.rules

import pgtop.domain.{Evidence, Finding, Format, Severity, Snapshot, StatementStats, SuggestedFix}
import pgtop.heuristics.{Rule, Thresholds}

/** The statements that consume the most total execution time. */
final case class ExpensiveStatementRule(thresholds: Thresholds) extends Rule:
  def id: String = "expensive-statement"
  def description: String = "Statements with the largest cumulative execution time"

  def evaluate(snapshot: Snapshot): Vector[Finding] =
    val totalMs = snapshot.statements.map(_.totalExecMs).sum
    snapshot.statements
      .filter(_.totalExecMs >= thresholds.expensiveStatementMinTotalMs)
      .sortBy(statement => -statement.totalExecMs)
      .take(thresholds.expensiveStatementLimit)
      .map(statement => finding(statement, totalMs))

  private def finding(statement: StatementStats, totalMs: Double): Finding =
    val severity =
      if statement.totalExecMs >= 600_000 then Severity.Critical
      else if statement.totalExecMs >= 120_000 then Severity.High
      else Severity.Medium
    val share = if totalMs > 0 then statement.totalExecMs / totalMs else 0.0
    val evidence = Vector(
      Evidence("total execution time", Format.milliseconds(statement.totalExecMs)),
      Evidence("mean execution time", Format.milliseconds(statement.meanExecMs)),
      Evidence("calls", Format.count(statement.calls)),
      Evidence("rows returned", Format.count(statement.rows)),
      Evidence("share of statement time", Format.percent(share)),
      Evidence("shared blocks read", Format.count(statement.sharedBlocksRead))
    ) ++
      (if statement.tempBlocksWritten > 0 then
         Vector(Evidence("temporary blocks written", Format.count(statement.tempBlocksWritten)))
       else Vector.empty)
    Finding(
      rule = id,
      severity = severity,
      title = s"Expensive statement: ${StatementLabel.of(statement)}",
      subject = StatementLabel.of(statement),
      explanation =
        s"${Format.milliseconds(statement.totalExecMs)} of cumulative execution time over " +
          s"${Format.count(statement.calls)} calls, ${Format.percent(share)} of all statement time in this database. " +
          s"Mean ${Format.milliseconds(statement.meanExecMs)} per call returning ${Format.count(statement.rows)} rows.",
      evidence = evidence,
      fix = SuggestedFix(
        summary =
          "Look at the plan before changing anything; the fix depends on whether the time is spent scanning or sorting.",
        sql = s"EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT) ${StatementLabel.withoutSemicolon(statement.query)};",
        caveats = Vector(
          "EXPLAIN ANALYZE executes the statement; run it on a replica or with representative parameters.",
          "Replace $n placeholders with real values, otherwise the plan is not representative."
        ) ++
          (if statement.hasParameters then Vector("This statement is parameterised; pgtop cannot bind values for you.")
           else Vector.empty) ++
          (if statement.tempBlocksWritten > 0 then
             Vector("Temporary blocks were written: the plan spills to disk, look at work_mem and the sort keys.")
           else Vector.empty)
      ),
      weight = statement.totalExecMs.toLong
    )

/** A short, stable label for a statement, for titles and report subjects. */
object StatementLabel:
  private val MaxLength = 80

  def withoutSemicolon(query: String): String =
    val trimmed = query.trim
    if trimmed.endsWith(";") then trimmed.dropRight(1) else trimmed

  def of(statement: StatementStats): String =
    val normalised = statement.normalisedQuery
    val head = if normalised.length > MaxLength then normalised.take(MaxLength) + "..." else normalised
    s"$head [${statement.queryId}]"
