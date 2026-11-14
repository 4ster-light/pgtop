package pgtop.report

import pgtop.domain.{Analysis, Evidence, Finding, Format, PlanSummary, Severity, SuggestedFix}

/** Ranked, colored terminal output. */
object TerminalReport:
  def render(input: ReportInput, options: RenderOptions): String =
    val findings = visibleFindings(input.analysis, options)
    val body = Vector.newBuilder[String]
    body += header(input, options)
    body += Text.rule(options.width)
    body += summaryLine(input.analysis, findings.length, options)
    if input.analysis.warnings.nonEmpty then
      body += ""
      input.analysis.warnings.foreach(w => body += s"  note  ${Ansi.dim(w, options.color)}")
    if findings.isEmpty then body += "" += "  No findings above the current threshold."
    findings.zipWithIndex.foreach { case (finding, index) =>
      body += ""
      body ++= renderFinding(index + 1, finding, options)
    }
    if input.plans.nonEmpty then
      body += ""
      body ++= renderPlans(input.plans, options)
    body.result().mkString("\n") + "\n"

  def visibleFindings(analysis: Analysis, options: RenderOptions): Vector[Finding] =
    analysis.ranked.filter(_.severity.level <= options.minSeverity.level).take(math.max(options.limit, 0))

  private def header(input: ReportInput, options: RenderOptions): String =
    val cluster = input.analysis.cluster
    val parts = Vector(
      Ansi.bold(s"pgtop ${input.toolVersion}", options.color),
      s"database ${cluster.database}",
      cluster.serverVersion.split(" ").take(2).mkString(" "),
      s"snapshot ${Format.instant(input.analysis.capturedAt)}"
    )
    "  " + parts.mkString("  ·  ")

  private def summaryLine(analysis: Analysis, shown: Int, options: RenderOptions): String =
    val counts = Severity.values.toVector.reverse.flatMap { severity =>
      analysis.severityCounts.get(severity).map(count => s"${count} ${severity.label}")
    }
    val found = if analysis.findings.isEmpty then "no findings" else counts.mkString(", ")
    val shownNote = if shown == analysis.findings.length then "" else s" (showing $shown)"
    s"  ${Ansi.bold(s"$found$shownNote", options.color)}"

  private def renderFinding(index: Int, finding: Finding, options: RenderOptions): Vector[String] =
    val lines = Vector.newBuilder[String]
    lines += s"  ${Ansi.bold(index.toString, options.color).padTo(index.toString.length, ' ')}  " +
      s"${Ansi.severityTag(finding.severity, options.color)}  ${Ansi.bold(finding.title, options.color)}"
    lines += s"      ${Ansi.dim(s"rule ${finding.rule} · ${finding.subject}", options.color)}"
    lines += ""
    Text.wrap(finding.explanation, options.width - 6).foreach(line => lines += s"      $line")
    if finding.evidence.nonEmpty then
      lines += ""
      lines += "      evidence"
      finding.evidence.foreach(e => lines += s"        ${pad(e.label, 26)} ${e.value}")
    lines += ""
    lines ++= renderFix(finding.fix, options)
    lines.result()

  private def renderFix(fix: SuggestedFix, options: RenderOptions): Vector[String] =
    val lines = Vector.newBuilder[String]
    lines += s"      ${Ansi.bold("fix", options.color)}  ${fix.summary}"
    fix.sql.linesIterator.foreach(line => lines += s"        ${Ansi.dim(line, options.color)}")
    fix.caveats.foreach(caveat => lines += s"        ${Ansi.dim("·", options.color)} $caveat")
    lines.result()

  private def renderPlans(plans: Vector[PlanSummary], options: RenderOptions): Vector[String] =
    val lines = Vector.newBuilder[String]
    lines += s"  ${Ansi.bold("query plans", options.color)}"
    plans.foreach { plan =>
      lines += ""
      lines += s"    ${plan.queryLabel}"
      lines +=
        s"      execution ${Format.milliseconds(plan.executionMs)} · planning ${Format.milliseconds(plan.planningMs)}"
      val relation = plan.slowestNodeRelation.fold("")(name => s" on $name")
      lines += s"      slowest node ${plan.slowestNodeType}$relation at ${Format.milliseconds(plan.slowestNodeMs)}"
    }
    lines.result()

  private def pad(label: String, width: Int): String =
    if label.length >= width then label else label + (" " * (width - label.length))

  /** Re-exported for tests: the visible evidence of a finding, one per line. */
  def evidenceLines(evidence: Vector[Evidence]): Vector[String] =
    evidence.map(e => s"${pad(e.label, 26)} ${e.value}")
