package pgtop.heuristics

import pgtop.domain.{RelationName, StatementStats}

/** A column that appears in the filter clause of statements touching a relation. */
final case class ColumnCandidate(column: String, statements: Int, calls: Long)

/** Very conservative extraction of filter columns from `pg_stat_statements`
  * query text.
  *
  * pgtop does not ship a SQL parser: it only recognises the shape
  * `column <op> <parameter|literal>` inside a statement's `WHERE` clause, and
  * only for statements that mention the relation by name. Anything more
  * elaborate degrades to "no candidate", which the missing-index rule reports
  * as a query to run by hand instead of inventing an index.
  */
object QueryShapes:
  private val StopWords: Set[String] = Set(
    "and",
    "or",
    "not",
    "null",
    "true",
    "false",
    "select",
    "from",
    "where",
    "case",
    "when",
    "then",
    "else",
    "end",
    "as",
    "is",
    "in",
    "like",
    "between",
    "exists",
    "any",
    "all"
  )

  private val Predicate =
    """(?i)\b([a-z_][a-z0-9_]*)\s*(>=|<=|<>|!=|=|>|<|\blike\b|\bin\b)\s*(\$\d+|'[^']*'|-?\d+(?:\.\d+)?)""".r

  private val ClauseBoundary =
    """(?i)\b(group\s+by|order\s+by|limit|offset|having|returning|union|window|for\s+update)\b""".r

  /** Candidate leading index columns for `relation`, ranked by the call weight of matching statements. */
  def columnCandidates(relation: RelationName, statements: Vector[StatementStats]): Vector[ColumnCandidate] =
    val perStatement = statements.flatMap { statement =>
      firstFilterColumn(relation, statement).map(column => (column, statement.calls))
    }
    perStatement
      .groupBy(_._1)
      .toVector
      .map { case (column, hits) => ColumnCandidate(column, hits.length, hits.map(_._2).sum) }
      .sortBy(candidate => (-candidate.calls, -candidate.statements, candidate.column))
      .filterNot(candidate => candidate.column == relation.name.toLowerCase)

  private def firstFilterColumn(relation: RelationName, statement: StatementStats): Option[String] =
    val text = statement.query.toLowerCase
    if !mentionsRelation(text, relation) then None
    else
      val whereIndex = text.indexOf(" where ")
      if whereIndex < 0 then None
      else
        val clause = text.substring(whereIndex + " where ".length)
        val bounded = ClauseBoundary.findFirstMatchIn(clause).fold(clause)(m => clause.substring(0, m.start))
        Predicate
          .findAllMatchIn(bounded)
          .map(_.group(1))
          .find(column => !StopWords.contains(column))

  private def mentionsRelation(loweredQuery: String, relation: RelationName): Boolean =
    val bare = relation.name.toLowerCase
    val qualified = s"${relation.schema.toLowerCase}.$bare"
    loweredQuery.contains(s" $bare ") || loweredQuery.contains(s" $bare\n") ||
    loweredQuery.contains(s"$qualified ") || loweredQuery.contains(s"$qualified\n") ||
    loweredQuery.endsWith(bare) || loweredQuery.endsWith(qualified)
