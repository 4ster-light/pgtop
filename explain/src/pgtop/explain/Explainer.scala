package pgtop.explain

import java.sql.{Connection, DriverManager}
import scala.util.{Try, Using}

import pgtop.collector.ConnectionConfig
import pgtop.domain.{PlanSummary, StatementStats}

/** Plan summaries plus the reasons some statements were not explained. */
final case class ExplainOutcome(summaries: Vector[PlanSummary], warnings: Vector[String])

/** Runs `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)` for the most expensive
  * statements.
  *
  * Only read-only statements are explained, and they run inside a transaction
  * that is rolled back: EXPLAIN ANALYZE executes the statement, so a write would
  * otherwise be committed.
  */
object Explainer:
  def run(config: ConnectionConfig, statements: Vector[StatementStats], limit: Int): ExplainOutcome =
    val candidates = statements
      .filter(isReadOnly)
      .sortBy(statement => -statement.totalExecMs)
      .take(math.max(limit, 0))
    val skipped = statements.filter(statement => !isReadOnly(statement)).take(limit).map { statement =>
      s"skipped EXPLAIN for query ${statement.queryId}: only read-only statements are explained"
    }
    if candidates.isEmpty then ExplainOutcome(Vector.empty, skipped)
    else
      Try {
        Using.resource(DriverManager.getConnection(config.jdbcUrl, config.asProperties)) { connection =>
          connection.setAutoCommit(false)
          val planned = candidates.map(statement => explainWith(connection, statement))
          connection.rollback()
          val summaries = planned.collect { case Right(summary) => summary }
          val failures = planned.collect { case Left(warning) => warning }
          ExplainOutcome(summaries, skipped ++ failures)
        }
      }.getOrElse(ExplainOutcome(Vector.empty, skipped :+ "could not open a connection to run EXPLAIN"))
  private def explainWith(connection: Connection, statement: StatementStats): Either[String, PlanSummary] =
    val label = StatementLabelFor(statement)
    val sql = s"EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) ${trimSemicolon(statement.query)}"
    Try {
      Using.resource(connection.createStatement()) { jdbcStatement =>
        Using.resource(jdbcStatement.executeQuery(sql)) { rs =>
          if rs.next() then ExplainJson.parse(label, rs.getString(1))
          else Left(s"EXPLAIN returned no rows for query ${statement.queryId}")
        }
      }
    }.getOrElse(Left(s"EXPLAIN failed for query ${statement.queryId}"))

  private def isReadOnly(statement: StatementStats): Boolean =
    val head = statement.query.trim.toLowerCase
    (head.startsWith("select") || head.startsWith("with")) && !statement.hasParameters

  private def trimSemicolon(query: String): String =
    val trimmed = query.trim
    if trimmed.endsWith(";") then trimmed.dropRight(1) else trimmed

  private def StatementLabelFor(statement: StatementStats): String =
    val normalised = statement.normalisedQuery
    val head = if normalised.length > 70 then normalised.take(70) + "..." else normalised
    s"$head [${statement.queryId}]"
