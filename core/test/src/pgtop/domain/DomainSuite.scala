package pgtop.domain

import java.time.Instant

class DomainSuite extends munit.FunSuite:
  test("severity parses its labels case-insensitively"):
    assertEquals(Severity.parse("HIGH"), Some(Severity.High))
    assertEquals(Severity.parse(" info "), Some(Severity.Info))
    assertEquals(Severity.parse("urgent"), None)

  test("severity levels order critical first"):
    assertEquals(Severity.atLeast(Severity.Medium), Vector(Severity.Critical, Severity.High, Severity.Medium))
    assert(Severity.Critical.level < Severity.Info.level)

  test("dead tuple ratio accounts for live and dead tuples only"):
    val table = TableStats(
      relation = RelationName("public", "orders"),
      sequentialScans = 10,
      indexScans = 20,
      liveTuples = 75,
      deadTuples = 25,
      modificationsSinceAnalyze = 0,
      inserted = 0,
      updated = 0,
      deleted = 0,
      lastVacuum = None,
      lastAutovacuum = None,
      lastAnalyze = None,
      lastAutoanalyze = None,
      totalBytes = 1024,
      tableBytes = 1024,
      indexesBytes = 0
    )

    assertEquals(table.deadTupleRatio, 0.25)
    assertEquals(table.scans, 30L)
    assertEquals(table.sequentialScanRatio, 10.0 / 30.0)

  test("an empty table has no ratios to divide by zero"):
    val empty = Snapshot.empty(Instant.EPOCH).tables
    assertEquals(empty, Vector.empty)
    assertEquals(RelationName("public", "orders").qualified, "public.orders")

  test("index signatures normalise quoting and whitespace"):
    assertEquals(IndexSignature.normalise("  LOWER(\"Email\")  "), "lower(email)")
    assertEquals(IndexSignature.normalise("customer_id"), "customer_id")

  test("findings sort by severity then by impact"):
    val base = Finding(
      rule = "test",
      severity = Severity.Low,
      title = "title",
      subject = "subject",
      explanation = "explanation",
      evidence = Vector.empty,
      fix = SuggestedFix("summary", "SELECT 1;", Vector.empty),
      weight = 1
    )

    val ranked = Finding.ranked(Vector(base.copy(weight = 10), base.copy(severity = Severity.Critical)))

    assertEquals(ranked.head.severity, Severity.Critical)
    assertEquals(ranked.last.weight, 10L)

  test("formatting helpers use binary units and compact counts"):
    assertEquals(Format.bytes(2048), "2 KiB")
    assertEquals(Format.count(999), "999")
    assertEquals(Format.count(1500), "1.5K")
    assertEquals(Format.percent(0.6), "60.0%")
    assertEquals(Format.milliseconds(90_000), "1.5 min")

  test("a suggested fix refuses empty SQL"):
    intercept[IllegalArgumentException](SuggestedFix("summary", "", Vector.empty))
