package pgtop.heuristics

import pgtop.domain.{Evidence, Finding, Severity, SuggestedFix}

class RankingSuite extends munit.FunSuite:
  private def finding(severity: Severity, weight: Long, subject: String): Finding =
    Finding(
      rule = "test",
      severity = severity,
      title = s"finding for $subject",
      subject = subject,
      explanation = "explanation",
      evidence = Vector(Evidence("metric", "value")),
      fix = SuggestedFix("summary", "SELECT 1;", Vector.empty),
      weight = weight
    )

  test("ranked orders by severity, then by descending impact"):
    val findings = Vector(
      finding(Severity.Low, 10, "low"),
      finding(Severity.Critical, 1, "critical-small"),
      finding(Severity.Critical, 99, "critical-big"),
      finding(Severity.Medium, 50, "medium")
    )

    assertEquals(
      Finding.ranked(findings).map(_.subject),
      Vector("critical-big", "critical-small", "medium", "low")
    )

  test("Analysis.top keeps the ranking and truncates"):
    val analysis = pgtop.domain.Analysis(
      pgtop.domain.ClusterInfo.unknown,
      Fixtures.capturedAt,
      findings,
      Vector.empty
    )

    assertEquals(analysis.top(2).findings.map(_.subject), Vector("critical-big", "critical-small"))
    assertEquals(analysis.atLeast(Severity.High).findings.map(_.subject).toSet, Set("critical-big", "critical-small"))

  private val findings = Vector(
    finding(Severity.Low, 10, "low"),
    finding(Severity.Critical, 1, "critical-small"),
    finding(Severity.Critical, 99, "critical-big"),
    finding(Severity.Medium, 50, "medium")
  )
