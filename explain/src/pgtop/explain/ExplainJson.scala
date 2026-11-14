package pgtop.explain

import io.circe.{Json, parser}

import pgtop.domain.PlanSummary

/** Output of `EXPLAIN (ANALYZE, FORMAT JSON)`: one plan per statement. */
private[explain] final case class PlanNode(
  nodeType: String,
  relation: Option[String],
  actualTotalMs: Double,
  children: Vector[PlanNode]
):
  def selfAndDescendants: Vector[PlanNode] = this +: children.flatMap(_.selfAndDescendants)

  def slowest: PlanNode = selfAndDescendants.maxBy(_.actualTotalMs)

private[explain] final case class ExplainedPlan(planningMs: Double, executionMs: Double, root: PlanNode)

/** Parses the JSON that PostgreSQL returns for an EXPLAIN (ANALYZE, FORMAT JSON). */
private[explain] object ExplainJson:
  def parse(label: String, json: String): Either[String, PlanSummary] =
    for
      document <- parser.parse(json).left.map(error => s"invalid EXPLAIN JSON: ${error.message}")
      entry <- document.asArray.flatMap(_.headOption).toRight("EXPLAIN returned no plan")
      plan <- explainedPlan(entry)
    yield
      val slowest = plan.root.slowest
      PlanSummary(
        queryLabel = label,
        planningMs = plan.planningMs,
        executionMs = plan.executionMs,
        slowestNodeType = slowest.nodeType,
        slowestNodeRelation = slowest.relation,
        slowestNodeMs = slowest.actualTotalMs
      )

  private def explainedPlan(entry: Json): Either[String, ExplainedPlan] =
    val cursor = entry.hcursor
    cursor
      .downField("Plan")
      .focus
      .toRight("EXPLAIN JSON has no Plan field")
      .flatMap(node)
      .map(root =>
        ExplainedPlan(
          planningMs = cursor.get[Double]("Planning Time").getOrElse(0.0),
          executionMs = cursor.get[Double]("Execution Time").getOrElse(0.0),
          root = root
        )
      )

  private def node(json: Json): Either[String, PlanNode] =
    val cursor = json.hcursor
    cursor.get[String]("Node Type").left.map(_ => "plan node without Node Type").map { nodeType =>
      // Parallel nodes report per-worker timings; loops make them comparable.
      val loops = cursor.get[Double]("Actual Loops").getOrElse(1.0).max(1.0)
      val perLoop = cursor.get[Double]("Actual Total Time").getOrElse(0.0)
      PlanNode(
        nodeType = nodeType,
        relation = cursor.get[String]("Relation Name").toOption,
        actualTotalMs = perLoop * loops,
        children = cursor
          .downField("Plans")
          .focus
          .flatMap(_.asArray)
          .getOrElse(Vector.empty)
          .flatMap(child => node(child).toOption)
      )
    }
