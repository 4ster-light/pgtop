package pgtop.domain

/** How urgently a finding deserves attention. `level` orders them, 0 is the most severe. */
enum Severity(val level: Int, val label: String):
  case Critical extends Severity(0, "critical")
  case High extends Severity(1, "high")
  case Medium extends Severity(2, "medium")
  case Low extends Severity(3, "low")
  case Info extends Severity(4, "info")

object Severity:
  def parse(raw: String): Option[Severity] =
    values.find(_.label.equalsIgnoreCase(raw.trim))

  def atLeast(minimum: Severity): Vector[Severity] =
    values.toVector.filter(_.level <= minimum.level).sortBy(_.level)

/** One labelled number that justifies a finding. */
final case class Evidence(label: String, value: String)

/** The advice: what to do, the SQL to run, and what to check before running it. */
final case class SuggestedFix(summary: String, sql: String, caveats: Vector[String]):
  require(summary.nonEmpty, "a suggested fix needs a summary")
  require(sql.nonEmpty, "a suggested fix needs runnable SQL")

/** A single piece of advice, produced by exactly one rule.
  *
  * `weight` is the rule's own estimate of the impact in its native unit
  * (milliseconds saved, bytes reclaimed, ...). It only orders findings that
  * share a severity, so the units do not need to be comparable across rules.
  */
final case class Finding(
  rule: String,
  severity: Severity,
  title: String,
  subject: String,
  explanation: String,
  evidence: Vector[Evidence],
  fix: SuggestedFix,
  weight: Long
):
  def rankKey: (Int, Long) = (severity.level, -weight)

object Finding:
  given Ordering[Finding] = Ordering.by[Finding, (Int, Long)](_.rankKey)

  /** Most severe first, then largest estimated impact, then stable by subject. */
  def ranked(findings: Iterable[Finding]): Vector[Finding] =
    findings.toVector.sortBy(f => (f.severity.level, -f.weight, f.subject))
