package pgtop.heuristics

class QueryShapesSuite extends munit.FunSuite:
  private val orders = Fixtures.relation("orders")

  test("finds the leading equality column and ranks by calls"):
    val candidates = QueryShapes.columnCandidates(
      orders,
      Vector(
        Fixtures.statement("SELECT * FROM public.orders WHERE customer_id = $1", calls = 900),
        Fixtures.statement("SELECT * FROM orders WHERE customer_id = $1 AND status = 'new'", calls = 100),
        Fixtures.statement("SELECT * FROM orders WHERE status = 'new'", calls = 50)
      )
    )

    assertEquals(candidates.map(_.column).take(2), Vector("customer_id", "status"))
    assertEquals(candidates.head.calls, 1_000L)
    assertEquals(candidates.head.statements, 2)

  test("recognises range predicates"):
    val candidates = QueryShapes.columnCandidates(
      orders,
      Vector(Fixtures.statement("SELECT * FROM orders WHERE created_at >= $1", calls = 10))
    )

    assertEquals(candidates.map(_.column), Vector("created_at"))

  test("ignores statements that do not mention the relation"):
    val candidates = QueryShapes.columnCandidates(
      orders,
      Vector(Fixtures.statement("SELECT * FROM public.invoices WHERE customer_id = $1"))
    )

    assertEquals(candidates, Vector.empty)

  test("ignores keywords and the table name itself"):
    val candidates = QueryShapes.columnCandidates(
      orders,
      Vector(
        Fixtures.statement("SELECT * FROM orders WHERE orders.status = 'new'"),
        Fixtures.statement("SELECT * FROM orders WHERE not status = 'new'")
      )
    )

    assertEquals(candidates.map(_.column), Vector("status"))
