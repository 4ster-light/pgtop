package pgtop.domain

import java.time.Instant

/** The whole result of one run: what we looked at, what we found, what went wrong. */
final case class Analysis(
  cluster: ClusterInfo,
  capturedAt: Instant,
  findings: Vector[Finding],
  warnings: Vector[String]
):
  def ranked: Vector[Finding] = Finding.ranked(findings)

  def severityCounts: Map[Severity, Int] =
    Severity.values.toVector.map(s => s -> findings.count(_.severity == s)).filter(_._2 > 0).toMap

  def worstSeverity: Option[Severity] =
    findings.map(_.severity).minByOption(_.level)

  def worst: Int = worstSeverity.fold(Int.MaxValue)(_.level)

  def atLeast(minimum: Severity): Analysis =
    copy(findings = findings.filter(_.severity.level <= minimum.level))

  def top(limit: Int): Analysis =
    copy(findings = ranked.take(limit.max(0)))

  def isEmpty: Boolean = findings.isEmpty

object Analysis:
  val empty: Analysis = Analysis(ClusterInfo.unknown, Instant.EPOCH, Vector.empty, Vector.empty)
