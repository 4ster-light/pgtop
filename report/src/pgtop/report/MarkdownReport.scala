package pgtop.report

import pgtop.domain.{Analysis, Finding, Format, PlanSummary, Severity}

/** Markdown export: the same advice, suitable for a pull request or a ticket. */
object MarkdownReport:
  def render(input: ReportInput, options: RenderOptions): String =
    val findings = TerminalReport.visibleFindings(input.analysis, options)
    val lines = Vector.newBuilder[String]
    lines += s"# pgtop report"
    lines += ""
    lines ++= metadata(input.analysis)
    lines += ""
    lines ++= summary(input.analysis)
    findings.zipWithIndex.foreach { case (finding, index) =>
      lines += ""
      lines ++= findingSection(index + 1, finding)
    }
    if input.plans.nonEmpty then
      lines += ""
      lines ++= planSection(input.plans)
    if input.analysis.warnings.nonEmpty then
      lines += ""
      lines += "## Notes"
      lines += ""
      input.analysis.warnings.foreach(warning => lines += s"- $warning")
    lines.result().mkString("\n") + "\n"

  private def metadata(analysis: Analysis): Vector[String] =
    Vector(
      s"| | |",
      s"|---|---|",
      s"| Database | `${analysis.cluster.database}` |",
      s"| Server | ${analysis.cluster.serverVersion} |",
      s"| Captured | ${Format.instant(analysis.capturedAt)} |",
      s"| pg_stat_statements | ${analysis.cluster.pgStatStatementsVersion.getOrElse("not installed")} |",
      analysis.cluster.statisticsResetAt
        .map(reset => s"| Statistics reset | ${Format.instant(reset)} |")
        .getOrElse("| Statistics reset | never |")
    )

  private def summary(analysis: Analysis): Vector[String] =
    if analysis.findings.isEmpty then Vector("No findings above the current threshold.")
    else
      val counts = Severity.values.toVector.reverse.flatMap { severity =>
        analysis.severityCounts.get(severity).map(count => s"$count ${severity.label}")
      }
      Vector(s"**${analysis.findings.length} findings**: ${counts.mkString(", ")}")

  private def findingSection(index: Int, finding: Finding): Vector[String] =
    val lines = Vector.newBuilder[String]
    lines += s"## $index. [${finding.severity.label.toUpperCase}] ${finding.title}"
    lines += ""
    lines += s"**Rule** `${finding.rule}` · **Subject** `${finding.subject}`"
    lines += ""
    lines += finding.explanation
    if finding.evidence.nonEmpty then
      lines += ""
      lines += "### Evidence"
      lines += ""
      lines += "| Metric | Value |"
      lines += "|---|---|"
      finding.evidence.foreach(e => lines += s"| ${e.label} | ${escape(e.value)} |")
    lines += ""
    lines += "### Fix"
    lines += ""
    lines += finding.fix.summary
    lines += ""
    lines += "```sql"
    lines ++= finding.fix.sql.linesIterator
    lines += "```"
    if finding.fix.caveats.nonEmpty then
      lines += ""
      finding.fix.caveats.foreach(caveat => lines += s"- $caveat")
    lines.result()

  private def planSection(plans: Vector[PlanSummary]): Vector[String] =
    val lines = Vector.newBuilder[String]
    lines += "## Query plans"
    lines += ""
    lines += "| Query | Execution | Planning | Slowest node |"
    lines += "|---|---|---|---|"
    plans.foreach { plan =>
      val node = plan.slowestNodeRelation.fold(plan.slowestNodeType)(name => s"${plan.slowestNodeType} on $name")
      lines += s"| `${escape(plan.queryLabel)}` | ${Format.milliseconds(plan.executionMs)} | " +
        s"${Format.milliseconds(plan.planningMs)} | $node (${Format.milliseconds(plan.slowestNodeMs)}) |"
    }
    lines.result()

  /** Table cells cannot contain raw pipes or newlines. */
  private def escape(value: String): String = value.replace("|", "\\|").replace("\n", " ")
