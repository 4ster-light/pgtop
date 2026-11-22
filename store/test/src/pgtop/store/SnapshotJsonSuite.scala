package pgtop.store

import java.nio.file.{Files, Path}
import java.time.{Duration, Instant}

import pgtop.domain.*

class SnapshotJsonSuite extends munit.FunSuite:
  private val snapshot: Snapshot = Snapshot(
    capturedAt = Instant.parse("2026-11-20T08:00:00.117456Z"),
    cluster = ClusterInfo(
      serverVersion = "PostgreSQL 17.2",
      database = "app",
      postmasterStartedAt = Some(Instant.parse("2026-11-02T08:00:00Z")),
      statisticsResetAt = Some(Instant.parse("2026-11-01T00:00:00Z")),
      pgStatStatementsVersion = Some("1.11")
    ),
    tables = Vector(
      TableStats(
        relation = RelationName("public", "orders"),
        sequentialScans = 3_600,
        indexScans = 42,
        liveTuples = 1_000_000,
        deadTuples = 12_345,
        modificationsSinceAnalyze = 99,
        inserted = 10,
        updated = 20,
        deleted = 30,
        lastVacuum = None,
        lastAutovacuum = Some(Instant.parse("2026-11-19T01:00:00Z")),
        lastAnalyze = None,
        lastAutoanalyze = None,
        totalBytes = 8L * 1024 * 1024,
        tableBytes = 8L * 1024 * 1024,
        indexesBytes = 4L * 1024 * 1024
      )
    ),
    indexes = Vector(
      IndexStats(
        relation = RelationName("public", "orders"),
        name = IndexName("public", "idx_orders_status"),
        method = "btree",
        isUnique = false,
        isPrimary = false,
        isValid = true,
        definition = "CREATE INDEX idx_orders_status ON public.orders (status) WHERE status <> 'gone'",
        keyColumns = Vector("status"),
        includeColumns = Vector("created_at"),
        predicate = Some("status <> 'gone'"),
        scans = 0,
        tuplesRead = 0,
        sizeBytes = 8L * 1024 * 1024,
        constraintBacked = false
      )
    ),
    statements = Vector(
      StatementStats(
        queryId = -7_123_456_789L,
        query = "SELECT count(*)\nFROM orders\nWHERE status = 'new' AND total > $1",
        calls = 1_234,
        totalExecMs = 5_432.5,
        meanExecMs = 4.4,
        rows = 987,
        sharedBlocksHit = 1_000,
        sharedBlocksRead = 200,
        tempBlocksWritten = 3
      )
    ),
    activity = Vector(
      ActivitySample(
        pid = 4242,
        user = Some("app"),
        applicationName = None,
        state = Some("active"),
        waitEventType = None,
        waitEvent = None,
        query = Some("SELECT 1"),
        runningFor = Some(Duration.ofMillis(1_500)),
        backendType = Some("client backend")
      )
    ),
    warnings = Vector("pg_stat_statements is not installed in this database")
  )

  test("round-trips through JSON unchanged"):
    assertEquals(SnapshotJson.decode(SnapshotJson.encode(snapshot)), Right(snapshot))

  test("writes the envelope first, before the snapshot body"):
    val lines = SnapshotJson.encode(snapshot).linesIterator.toVector.take(3)

    assertEquals(lines(0), "{")
    assert(lines(1).contains("\"format\" : \"pgtop-snapshot\""), lines(1))
    assert(lines(2).contains("\"version\" : 1"), lines(2))

  test("refuses an unknown snapshot format"):
    val text = SnapshotJson.encode(snapshot).replace("\"pgtop-snapshot\"", "\"something-else\"")

    assert(SnapshotJson.decode(text).left.exists(_.contains("unknown snapshot format")))

  test("refuses an unsupported version"):
    val text = SnapshotJson.encode(snapshot).replaceFirst("\"version\" : 1", "\"version\" : 2")

    assert(SnapshotJson.decode(text).left.exists(_.contains("unsupported snapshot version: 2")))

  test("refuses files that are not JSON or not snapshots"):
    assert(SnapshotJson.decode("nope").left.exists(_.contains("not valid JSON")))
    assert(SnapshotJson.decode("{}").left.exists(_.contains("no \"format\" field")))
    assert(SnapshotJson.decode("""{"format":"pgtop-snapshot"}""").left.exists(_.contains("no \"version\" field")))

  test("reports a malformed field as an error, not a crash"):
    val text = SnapshotJson.encode(snapshot).replace("\"2026-11-20T08:00:00.117456Z\"", "\"yesterday\"")

    assert(SnapshotJson.decode(text).left.exists(_.contains("invalid instant")))

  test("writes and reads a whole snapshot file"):
    val path = Files.createTempFile("pgtop-snapshot", ".json")
    try
      SnapshotFile.write(path, snapshot) match
        case Left(message) => fail(s"could not write the snapshot file: $message")
        case Right(()) =>
          assertEquals(SnapshotFile.read(path), Right(snapshot))
    finally Files.deleteIfExists(path)

  test("reading a missing file names the path"):
    assert(SnapshotFile.read(Path.of("no", "such", "snap.json")).left.exists(_.contains("no such snapshot file")))
