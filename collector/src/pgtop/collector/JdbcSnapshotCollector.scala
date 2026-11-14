package pgtop.collector

import java.sql.{Connection, DriverManager, ResultSet}
import java.time.{Clock, Instant}
import scala.collection.mutable
import scala.util.{Failure, Success, Try, Using}

import pgtop.domain.*

/** Reads the `pg_stat_*` views of one database into a `Snapshot`.
  *
  * A failing section (missing extension, missing privileges) becomes a warning on
  * the snapshot instead of aborting the run: a partial report with an explicit
  * note is more useful than no report at all.
  */
final class JdbcSnapshotCollector(
  config: ConnectionConfig,
  clock: Clock = Clock.systemUTC(),
  statementLimit: Int = 200
) extends SnapshotCollector:

  def collect(): Snapshot =
    Using.resource(openConnection()) { connection =>
      val warnings = Vector.newBuilder[String]
      val cluster = guarded(warnings, "cluster information")(readCluster(connection))
        .getOrElse(ClusterInfo.unknown)
      val statementsAvailable = cluster.pgStatStatementsVersion.isDefined
      if !statementsAvailable then
        warnings += "pg_stat_statements is not installed in this database: statement-level rules are skipped"

      Snapshot(
        capturedAt = Instant.now(clock),
        cluster = cluster,
        tables = guarded(warnings, "pg_stat_user_tables")(readTables(connection)).getOrElse(Vector.empty),
        indexes = guarded(warnings, "pg_stat_user_indexes")(readIndexes(connection)).getOrElse(Vector.empty),
        statements =
          if statementsAvailable then
            guarded(warnings, "pg_stat_statements")(readStatements(connection)).getOrElse(Vector.empty)
          else Vector.empty,
        activity = guarded(warnings, "pg_stat_activity")(readActivity(connection)).getOrElse(Vector.empty),
        warnings = warnings.result()
      )
    }

  private def openConnection(): Connection =
    val connection = DriverManager.getConnection(config.jdbcUrl, config.asProperties)
    connection.setReadOnly(true)
    connection

  private def guarded[A](warnings: mutable.Builder[String, Vector[String]], label: String)(
    body: => A
  ): Option[A] =
    Try(body) match
      case Success(value) => Some(value)
      case Failure(error) =>
        warnings += s"could not read $label: ${error.getClass.getSimpleName}: ${Option(error.getMessage).getOrElse("")}"
        None

  private def readCluster(connection: Connection): ClusterInfo =
    val base = query(connection, Sql.serverInfo) { rs =>
      if rs.next() then
        ClusterInfo(
          serverVersion = Rows.text(rs, "server_version").getOrElse("unknown"),
          database = Rows.text(rs, "database").getOrElse("unknown"),
          postmasterStartedAt = Rows.instant(rs, "postmaster_started_at"),
          statisticsResetAt = Rows.instant(rs, "stats_reset"),
          pgStatStatementsVersion = None
        )
      else ClusterInfo.unknown
    }
    val extensionVersion = query(connection, Sql.pgStatStatementsVersion) { rs =>
      if rs.next() then Rows.text(rs, "extversion") else None
    }
    base.copy(pgStatStatementsVersion = extensionVersion)

  private def readTables(connection: Connection): Vector[TableStats] =
    query(connection, Sql.tables) { rs =>
      val rows = Vector.newBuilder[TableStats]
      while rs.next() do rows += tableRow(rs)
      rows.result()
    }

  private def tableRow(rs: ResultSet): TableStats =
    TableStats(
      relation = RelationName(Rows.text(rs, "schema_name").getOrElse(""), Rows.text(rs, "table_name").getOrElse("")),
      sequentialScans = Rows.long(rs, "seq_scan"),
      indexScans = Rows.long(rs, "idx_scan"),
      liveTuples = Rows.long(rs, "n_live_tup"),
      deadTuples = Rows.long(rs, "n_dead_tup"),
      modificationsSinceAnalyze = Rows.long(rs, "n_mod_since_analyze"),
      inserted = Rows.long(rs, "n_tup_ins"),
      updated = Rows.long(rs, "n_tup_upd"),
      deleted = Rows.long(rs, "n_tup_del"),
      lastVacuum = Rows.instant(rs, "last_vacuum"),
      lastAutovacuum = Rows.instant(rs, "last_autovacuum"),
      lastAnalyze = Rows.instant(rs, "last_analyze"),
      lastAutoanalyze = Rows.instant(rs, "last_autoanalyze"),
      totalBytes = Rows.long(rs, "total_bytes"),
      tableBytes = Rows.long(rs, "table_bytes"),
      indexesBytes = Rows.long(rs, "indexes_bytes")
    )

  private def readIndexes(connection: Connection): Vector[IndexStats] =
    query(connection, Sql.indexes) { rs =>
      val rows = Vector.newBuilder[IndexStats]
      while rs.next() do rows += indexRow(rs)
      rows.result()
    }

  private def indexRow(rs: ResultSet): IndexStats =
    val schema = Rows.text(rs, "schema_name").getOrElse("")
    IndexStats(
      relation = RelationName(schema, Rows.text(rs, "table_name").getOrElse("")),
      name = IndexName(schema, Rows.text(rs, "index_name").getOrElse("")),
      method = Rows.text(rs, "method").getOrElse("btree"),
      isUnique = rs.getBoolean("is_unique"),
      isPrimary = rs.getBoolean("is_primary"),
      isValid = rs.getBoolean("is_valid"),
      definition = Rows.text(rs, "definition").getOrElse(""),
      keyColumns = Rows.textArray(rs, "key_columns"),
      includeColumns = Rows.textArray(rs, "include_columns"),
      predicate = Rows.text(rs, "predicate"),
      scans = Rows.long(rs, "idx_scan"),
      tuplesRead = Rows.long(rs, "idx_tup_read"),
      sizeBytes = Rows.long(rs, "size_bytes"),
      constraintBacked = rs.getBoolean("constraint_backed")
    )

  private def readStatements(connection: Connection): Vector[StatementStats] =
    val (totalColumn, meanColumn) = statementTimeColumns(connection)
    val sql = Sql.statements(totalColumn, meanColumn)
    Using.resource(connection.prepareStatement(sql)) { statement =>
      statement.setInt(1, statementLimit)
      Using.resource(statement.executeQuery()) { rs =>
        val rows = Vector.newBuilder[StatementStats]
        while rs.next() do rows += statementRow(rs)
        rows.result()
      }
    }

  private def statementRow(rs: ResultSet): StatementStats =
    StatementStats(
      queryId = Rows.long(rs, "queryid"),
      query = Rows.text(rs, "query").getOrElse(""),
      calls = Rows.long(rs, "calls"),
      totalExecMs = rs.getDouble("total_ms"),
      meanExecMs = rs.getDouble("mean_ms"),
      rows = Rows.long(rs, "rows"),
      sharedBlocksHit = Rows.long(rs, "shared_blks_hit"),
      sharedBlocksRead = Rows.long(rs, "shared_blks_read"),
      tempBlocksWritten = Rows.long(rs, "temp_blks_written")
    )

  /** `pg_stat_statements` 1.8 renamed `total_time` to `total_exec_time`. */
  private def statementTimeColumns(connection: Connection): (String, String) =
    val columns = query(connection, Sql.statementTimeColumn) { rs =>
      val found = Vector.newBuilder[String]
      while rs.next() do found += Rows.text(rs, "column_name").getOrElse("")
      found.result()
    }
    if columns.contains("total_exec_time") then ("total_exec_time", "mean_exec_time")
    else ("total_time", "mean_time")

  private def readActivity(connection: Connection): Vector[ActivitySample] =
    query(connection, Sql.activity) { rs =>
      val rows = Vector.newBuilder[ActivitySample]
      while rs.next() do rows += activityRow(rs)
      rows.result()
    }

  private def activityRow(rs: ResultSet): ActivitySample =
    ActivitySample(
      pid = Rows.int(rs, "pid"),
      user = Rows.text(rs, "usename"),
      applicationName = Rows.text(rs, "application_name"),
      state = Rows.text(rs, "state"),
      waitEventType = Rows.text(rs, "wait_event_type"),
      waitEvent = Rows.text(rs, "wait_event"),
      query = Rows.text(rs, "query"),
      runningFor = Rows.seconds(rs, "running_seconds"),
      backendType = Rows.text(rs, "backend_type")
    )

  private def query[A](connection: Connection, sql: String)(read: ResultSet => A): A =
    Using.resource(connection.createStatement()) { statement =>
      Using.resource(statement.executeQuery(sql))(read)
    }
