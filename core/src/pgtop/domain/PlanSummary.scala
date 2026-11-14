package pgtop.domain

/** A digested EXPLAIN result, small enough to travel with the report. */
final case class PlanSummary(
  queryLabel: String,
  planningMs: Double,
  executionMs: Double,
  slowestNodeType: String,
  slowestNodeRelation: Option[String],
  slowestNodeMs: Double
)
