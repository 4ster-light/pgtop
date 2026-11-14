package pgtop.heuristics.rules

import pgtop.domain.{Evidence, Finding, Format, Severity, Snapshot, SuggestedFix, TableStats}
import pgtop.heuristics.{ColumnCandidate, QueryShapes, Rule, SqlName, Thresholds}

/** Tables that are read mostly through sequential scans, with an index candidate
  * inferred from `pg_stat_statements` where possible.
  */
final case class MissingIndexRule(thresholds: Thresholds) extends Rule:
  def id: String = "missing-index"
  def description: String = "Tables scanned sequentially far more than through indexes"

  def evaluate(snapshot: Snapshot): Vector[Finding] =
    snapshot.tables.filter(qualifies).map(table => finding(snapshot, table))

  private def qualifies(table: TableStats): Boolean =
    table.sequentialScans >= thresholds.missingIndexMinSequentialScans &&
      table.liveTuples >= thresholds.missingIndexMinLiveTuples &&
      table.sequentialScans.toDouble >=
      thresholds.missingIndexSequentialToIndexRatio * (table.indexScans.toDouble + 1.0)

  private def finding(snapshot: Snapshot, table: TableStats): Finding =
    val candidates = QueryShapes.columnCandidates(table.relation, snapshot.statements)
    val wastedBytes = table.sequentialScans.toDouble * table.totalBytes.toDouble
    val severity =
      if wastedBytes >= 100L * 1024 * 1024 * 1024 then Severity.Critical
      else if wastedBytes >= 10L * 1024 * 1024 * 1024 then Severity.High
      else if wastedBytes >= 1024L * 1024 * 1024 then Severity.Medium
      else Severity.Low
    val evidence = Vector(
      Evidence("sequential scans", Format.count(table.sequentialScans)),
      Evidence("index scans", Format.count(table.indexScans)),
      Evidence("sequential scan share", Format.percent(table.sequentialScanRatio)),
      Evidence("live rows", Format.count(table.liveTuples)),
      Evidence("table size", Format.bytes(table.totalBytes)),
      Evidence("rows walked by scans", Format.count(table.sequentialScans * table.liveTuples))
    ) ++ candidates.headOption.map(c => Evidence("inferred filter column", c.column)).toVector
    Finding(
      rule = id,
      severity = severity,
      title = s"Sequential scans dominate reads on ${table.relation.qualified}",
      subject = table.relation.qualified,
      explanation =
        s"${Format.count(table.sequentialScans)} sequential scans against ${Format.count(table.indexScans)} index scans " +
          s"over ${Format.count(table.liveTuples)} live rows. Each scan walks the table (${Format.bytes(table.tableBytes)}), " +
          s"so roughly ${Format.bytes(wastedBytes.toLong)} of reads are full-table work an index path could avoid.",
      evidence = evidence,
      fix = fixFor(snapshot, table, candidates.headOption),
      weight = math.min(wastedBytes, Long.MaxValue.toDouble).toLong
    )

  private def fixFor(snapshot: Snapshot, table: TableStats, candidate: Option[ColumnCandidate]): SuggestedFix =
    candidate match
      case Some(column) =>
        val indexName = SqlName.proposedIndexName(table.relation, column.column)
        SuggestedFix(
          summary = s"Index ${table.relation.qualified} on (${column.column}); it leads the filters of " +
            s"${Format.count(column.calls)} calls seen in pg_stat_statements.",
          sql =
            s"CREATE INDEX CONCURRENTLY IF NOT EXISTS ${SqlName.quote(indexName)} " +
              s"ON ${SqlName.qualified(table.relation)} (${SqlName.quote(column.column)});",
          caveats = Vector(
            "CONCURRENTLY cannot run inside a transaction block and briefly doubles index write cost.",
            "The column was inferred from query text; confirm it with EXPLAIN (ANALYZE, BUFFERS).",
            "Re-run pgtop after the index is live to see whether the sequential scan share drops."
          )
        )
      case None if snapshot.cluster.pgStatStatementsVersion.isDefined =>
        SuggestedFix(
          summary =
            s"No filter column could be inferred for ${table.relation.qualified}; find the statements hitting it, then index the leading filter column.",
          sql =
            s"SELECT calls, total_exec_time, query FROM pg_stat_statements\n" +
              s"WHERE query ILIKE '%${table.relation.name}%' AND query NOT ILIKE '%pg_stat_statements%'\n" +
              "ORDER BY total_exec_time DESC LIMIT 10;",
          caveats = Vector(
            "This is a diagnostic query, not the index itself: it names the statements that scan the table."
          )
        )
      case None =>
        SuggestedFix(
          summary =
            "pg_stat_statements is not installed, so pgtop cannot name the filter columns; install it and re-run.",
          sql = "CREATE EXTENSION IF NOT EXISTS pg_stat_statements;",
          caveats = Vector(
            "Adding pg_stat_statements requires shared_preload_libraries and a PostgreSQL restart."
          )
        )
