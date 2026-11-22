package pgtop.store

import java.time.{Duration, Instant}

import scala.util.Try

import pgtop.domain.*

import io.circe.{Decoder, Encoder, Json, JsonObject, Printer, parser}
import io.circe.syntax.*

/** The on-disk snapshot format: one JSON document per point in time.
  *
  * The envelope names the format and its version, so a later pgtop can refuse a
  * file it cannot read honestly instead of misreading it. The body is the
  * snapshot exactly as the collector took it; nothing is summarised away.
  *
  * The codecs are written out instead of derived: circe-generic would add a
  * dependency to pin down a schema this file already states explicitly.
  */
object SnapshotJson:
  val format: String = "pgtop-snapshot"
  val version: Int = 1

  private val printer = Printer.spaces2

  given Encoder[Instant] = Encoder.encodeString.contramap(_.toString)

  given Decoder[Instant] = Decoder.decodeString.emap(raw => attempt("instant", raw, Instant.parse(raw)))

  given Encoder[Duration] = Encoder.encodeString.contramap(_.toString)

  given Decoder[Duration] = Decoder.decodeString.emap(raw => attempt("duration", raw, Duration.parse(raw)))

  private given Encoder[RelationName] = Encoder.forProduct2("schema", "name")(name => (name.schema, name.name))

  private given Decoder[RelationName] = Decoder.forProduct2("schema", "name")(RelationName(_, _))

  private given Encoder[IndexName] = Encoder.forProduct2("schema", "name")(name => (name.schema, name.name))

  private given Decoder[IndexName] = Decoder.forProduct2("schema", "name")(IndexName(_, _))

  private given Encoder[ClusterInfo] = Encoder.forProduct5(
    "serverVersion",
    "database",
    "postmasterStartedAt",
    "statisticsResetAt",
    "pgStatStatementsVersion"
  )(info =>
    (info.serverVersion, info.database, info.postmasterStartedAt, info.statisticsResetAt, info.pgStatStatementsVersion)
  )

  private given Decoder[ClusterInfo] = Decoder.forProduct5(
    "serverVersion",
    "database",
    "postmasterStartedAt",
    "statisticsResetAt",
    "pgStatStatementsVersion"
  )(ClusterInfo(_, _, _, _, _))

  private given Encoder[TableStats] = Encoder.forProduct16(
    "relation",
    "sequentialScans",
    "indexScans",
    "liveTuples",
    "deadTuples",
    "modificationsSinceAnalyze",
    "inserted",
    "updated",
    "deleted",
    "lastVacuum",
    "lastAutovacuum",
    "lastAnalyze",
    "lastAutoanalyze",
    "totalBytes",
    "tableBytes",
    "indexesBytes"
  )(table =>
    (
      table.relation,
      table.sequentialScans,
      table.indexScans,
      table.liveTuples,
      table.deadTuples,
      table.modificationsSinceAnalyze,
      table.inserted,
      table.updated,
      table.deleted,
      table.lastVacuum,
      table.lastAutovacuum,
      table.lastAnalyze,
      table.lastAutoanalyze,
      table.totalBytes,
      table.tableBytes,
      table.indexesBytes
    )
  )

  private given Decoder[TableStats] = Decoder.forProduct16(
    "relation",
    "sequentialScans",
    "indexScans",
    "liveTuples",
    "deadTuples",
    "modificationsSinceAnalyze",
    "inserted",
    "updated",
    "deleted",
    "lastVacuum",
    "lastAutovacuum",
    "lastAnalyze",
    "lastAutoanalyze",
    "totalBytes",
    "tableBytes",
    "indexesBytes"
  )(
    (
      relation: RelationName,
      sequentialScans: Long,
      indexScans: Long,
      liveTuples: Long,
      deadTuples: Long,
      modificationsSinceAnalyze: Long,
      inserted: Long,
      updated: Long,
      deleted: Long,
      lastVacuum: Option[Instant],
      lastAutovacuum: Option[Instant],
      lastAnalyze: Option[Instant],
      lastAutoanalyze: Option[Instant],
      totalBytes: Long,
      tableBytes: Long,
      indexesBytes: Long
    ) =>
      TableStats(
        relation = relation,
        sequentialScans = sequentialScans,
        indexScans = indexScans,
        liveTuples = liveTuples,
        deadTuples = deadTuples,
        modificationsSinceAnalyze = modificationsSinceAnalyze,
        inserted = inserted,
        updated = updated,
        deleted = deleted,
        lastVacuum = lastVacuum,
        lastAutovacuum = lastAutovacuum,
        lastAnalyze = lastAnalyze,
        lastAutoanalyze = lastAutoanalyze,
        totalBytes = totalBytes,
        tableBytes = tableBytes,
        indexesBytes = indexesBytes
      )
  )

  private given Encoder[IndexStats] = Encoder.forProduct14(
    "relation",
    "name",
    "method",
    "isUnique",
    "isPrimary",
    "isValid",
    "definition",
    "keyColumns",
    "includeColumns",
    "predicate",
    "scans",
    "tuplesRead",
    "sizeBytes",
    "constraintBacked"
  )(index =>
    (
      index.relation,
      index.name,
      index.method,
      index.isUnique,
      index.isPrimary,
      index.isValid,
      index.definition,
      index.keyColumns,
      index.includeColumns,
      index.predicate,
      index.scans,
      index.tuplesRead,
      index.sizeBytes,
      index.constraintBacked
    )
  )

  private given Decoder[IndexStats] = Decoder.forProduct14(
    "relation",
    "name",
    "method",
    "isUnique",
    "isPrimary",
    "isValid",
    "definition",
    "keyColumns",
    "includeColumns",
    "predicate",
    "scans",
    "tuplesRead",
    "sizeBytes",
    "constraintBacked"
  )(
    (
      relation: RelationName,
      name: IndexName,
      method: String,
      isUnique: Boolean,
      isPrimary: Boolean,
      isValid: Boolean,
      definition: String,
      keyColumns: Vector[String],
      includeColumns: Vector[String],
      predicate: Option[String],
      scans: Long,
      tuplesRead: Long,
      sizeBytes: Long,
      constraintBacked: Boolean
    ) =>
      IndexStats(
        relation = relation,
        name = name,
        method = method,
        isUnique = isUnique,
        isPrimary = isPrimary,
        isValid = isValid,
        definition = definition,
        keyColumns = keyColumns,
        includeColumns = includeColumns,
        predicate = predicate,
        scans = scans,
        tuplesRead = tuplesRead,
        sizeBytes = sizeBytes,
        constraintBacked = constraintBacked
      )
  )

  private given Encoder[StatementStats] = Encoder.forProduct9(
    "queryId",
    "query",
    "calls",
    "totalExecMs",
    "meanExecMs",
    "rows",
    "sharedBlocksHit",
    "sharedBlocksRead",
    "tempBlocksWritten"
  )(statement =>
    (
      statement.queryId,
      statement.query,
      statement.calls,
      statement.totalExecMs,
      statement.meanExecMs,
      statement.rows,
      statement.sharedBlocksHit,
      statement.sharedBlocksRead,
      statement.tempBlocksWritten
    )
  )

  private given Decoder[StatementStats] = Decoder.forProduct9(
    "queryId",
    "query",
    "calls",
    "totalExecMs",
    "meanExecMs",
    "rows",
    "sharedBlocksHit",
    "sharedBlocksRead",
    "tempBlocksWritten"
  )(
    (
      queryId: Long,
      query: String,
      calls: Long,
      totalExecMs: Double,
      meanExecMs: Double,
      rows: Long,
      sharedBlocksHit: Long,
      sharedBlocksRead: Long,
      tempBlocksWritten: Long
    ) =>
      StatementStats(
        queryId = queryId,
        query = query,
        calls = calls,
        totalExecMs = totalExecMs,
        meanExecMs = meanExecMs,
        rows = rows,
        sharedBlocksHit = sharedBlocksHit,
        sharedBlocksRead = sharedBlocksRead,
        tempBlocksWritten = tempBlocksWritten
      )
  )

  private given Encoder[ActivitySample] = Encoder.forProduct9(
    "pid",
    "user",
    "applicationName",
    "state",
    "waitEventType",
    "waitEvent",
    "query",
    "runningFor",
    "backendType"
  )(sample =>
    (
      sample.pid,
      sample.user,
      sample.applicationName,
      sample.state,
      sample.waitEventType,
      sample.waitEvent,
      sample.query,
      sample.runningFor,
      sample.backendType
    )
  )

  private given Decoder[ActivitySample] = Decoder.forProduct9(
    "pid",
    "user",
    "applicationName",
    "state",
    "waitEventType",
    "waitEvent",
    "query",
    "runningFor",
    "backendType"
  )(
    (
      pid: Int,
      user: Option[String],
      applicationName: Option[String],
      state: Option[String],
      waitEventType: Option[String],
      waitEvent: Option[String],
      query: Option[String],
      runningFor: Option[Duration],
      backendType: Option[String]
    ) =>
      ActivitySample(
        pid = pid,
        user = user,
        applicationName = applicationName,
        state = state,
        waitEventType = waitEventType,
        waitEvent = waitEvent,
        query = query,
        runningFor = runningFor,
        backendType = backendType
      )
  )

  private given Encoder[Snapshot] = Encoder.forProduct7(
    "capturedAt",
    "cluster",
    "tables",
    "indexes",
    "statements",
    "activity",
    "warnings"
  )(snapshot =>
    (
      snapshot.capturedAt,
      snapshot.cluster,
      snapshot.tables,
      snapshot.indexes,
      snapshot.statements,
      snapshot.activity,
      snapshot.warnings
    )
  )

  private given Decoder[Snapshot] = Decoder.forProduct7(
    "capturedAt",
    "cluster",
    "tables",
    "indexes",
    "statements",
    "activity",
    "warnings"
  )(
    (
      capturedAt: Instant,
      cluster: ClusterInfo,
      tables: Vector[TableStats],
      indexes: Vector[IndexStats],
      statements: Vector[StatementStats],
      activity: Vector[ActivitySample],
      warnings: Vector[String]
    ) =>
      Snapshot(
        capturedAt = capturedAt,
        cluster = cluster,
        tables = tables,
        indexes = indexes,
        statements = statements,
        activity = activity,
        warnings = warnings
      )
  )

  /** The file text: the envelope first, then the snapshot body. */
  def encode(snapshot: Snapshot): String =
    val body = snapshot.asJson.asObject.getOrElse(JsonObject.empty)
    val envelope = JsonObject("format" -> Json.fromString(format), "version" -> Json.fromInt(version))
    printer.print(Json.fromJsonObject(JsonObject.fromIterable(envelope.toVector ++ body.toVector)))

  def decode(raw: String): Either[String, Snapshot] =
    parser.parse(raw) match
      case Left(error) => Left(s"not valid JSON: ${error.message}")
      case Right(document) =>
        for
          _ <- checkFormat(document)
          _ <- checkVersion(document)
          snapshot <- document.as[Snapshot].left.map(failure => s"not a pgtop snapshot: ${failure.message}")
        yield snapshot

  private def checkFormat(document: Json): Either[String, Unit] =
    document.hcursor.get[String]("format") match
      case Right(value) if value == format => Right(())
      case Right(value) => Left(s"unknown snapshot format: $value")
      case Left(_) => Left("""not a pgtop snapshot file: no "format" field""")

  private def checkVersion(document: Json): Either[String, Unit] =
    document.hcursor.get[Int]("version") match
      case Right(value) if value == version => Right(())
      case Right(value) => Left(s"unsupported snapshot version: $value (this build reads version $version)")
      case Left(_) => Left("""not a pgtop snapshot file: no "version" field""")

  /** Parses without throwing: `Instant.parse` and `Duration.parse` are the only fallible steps. */
  private def attempt[A](label: String, raw: String, value: => A): Either[String, A] =
    Try(value).toEither.left.map(_ => s"invalid $label: $raw")
