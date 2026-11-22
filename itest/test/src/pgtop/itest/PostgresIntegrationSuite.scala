package pgtop.itest

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.file.Files
import java.sql.DriverManager
import scala.concurrent.duration.*
import scala.util.Using

import pgtop.cli.Main
import pgtop.collector.SnapshotCollector
import pgtop.domain.Snapshot
import pgtop.heuristics.{Engine, RuleRegistry, Thresholds}
import pgtop.report.{RenderOptions, Report, ReportInput, Format}
import pgtop.store.SnapshotFile

class PostgresIntegrationSuite extends munit.FunSuite:
  override def munitTimeout: Duration = 5.minutes

  /** Thresholds small enough to fire on the tiny fixture schema. */
  private val testThresholds = Thresholds(
    unusedIndexMinBytes = 0,
    unusedIndexMaxScans = 0,
    missingIndexMinSequentialScans = 1,
    missingIndexSequentialToIndexRatio = 1.0,
    missingIndexMinLiveTuples = 1,
    vacuumDebtMinDeadTuples = 1,
    vacuumDebtRatio = 0.0,
    expensiveStatementMinTotalMs = 0
  )

  private def snapshot(): Snapshot =
    SnapshotCollector.jdbc(TestPostgres.config).collect()

  test("collects tables, indexes, statements and cluster information over JDBC"):
    val collected = snapshot()

    assertEquals(collected.cluster.database, "pgtop")
    assert(collected.cluster.serverVersion.startsWith("PostgreSQL"), collected.cluster.serverVersion)
    assert(collected.tables.exists(_.relation.name == "orders"), collected.tables.map(_.relation.name))
    assert(collected.indexes.exists(_.name.name == "idx_orders_customer_id"), collected.indexes.map(_.name.name))
    assert(collected.statements.nonEmpty, "pg_stat_statements should report the seeded workload")
    assert(
      !collected.warnings.exists(_.startsWith("could not read")),
      collected.warnings.mkString("; ")
    )
    val orders = collected.tables.find(_.relation.name == "orders").getOrElse(fail("orders table missing"))
    assert(orders.liveTuples > 1000, orders.liveTuples.toString)
    assert(orders.sequentialScans > 0, orders.sequentialScans.toString)

  test("turns a real workload into ranked, actionable findings"):
    val collected = snapshot()
    val analysis = Engine.analyse(collected, RuleRegistry.default(testThresholds))

    assert(analysis.findings.nonEmpty, "expected findings on the fixture schema")
    val rules = analysis.findings.map(_.rule).toSet
    assert(rules.contains("duplicate-index"), rules.toString)
    assert(rules.contains("unused-index"), rules.toString)
    assert(rules.contains("vacuum-debt"), rules.toString)
    assert(analysis.ranked.map(_.severity.level) == analysis.ranked.map(_.severity.level).sorted)
    val duplicate = analysis.findings.filter(_.rule == "duplicate-index")
    assert(duplicate.forall(_.fix.sql.startsWith("DROP INDEX CONCURRENTLY")), duplicate.map(_.fix.sql))

  test("renders the collected snapshot as a terminal and a markdown report"):
    val analysis = Engine.analyse(snapshot(), RuleRegistry.default(testThresholds))
    val input = ReportInput(analysis, Vector.empty, "0.1.0")

    val terminal = Report.render(input, Format.Terminal, RenderOptions(color = false))
    val markdown = Report.render(input, Format.Markdown, RenderOptions(color = false))

    assert(terminal.contains("pgtop 0.1.0"))
    assert(terminal.contains("database pgtop"))
    assert(terminal.contains("CREATE INDEX CONCURRENTLY") || terminal.contains("DROP INDEX CONCURRENTLY"))
    assert(markdown.startsWith("# pgtop report"))
    assert(markdown.contains("```sql"))

  test("the explain runner returns a parsed plan for a read-only statement"):
    val collected = snapshot()
    val outcome = pgtop.explain.Explainer.run(TestPostgres.config, collected.statements, 1)

    assert(outcome.summaries.nonEmpty || outcome.warnings.nonEmpty, "explain produced neither plans nor warnings")
    outcome.summaries.foreach { plan =>
      assert(plan.executionMs >= 0.0, plan.executionMs.toString)
      assert(plan.slowestNodeType.nonEmpty, plan.slowestNodeType)
    }

  test("saves a snapshot to JSON and reads it back unchanged"):
    val collected = snapshot()
    val path = Files.createTempFile("pgtop-snapshot", ".json")
    try
      SnapshotFile.write(path, collected) match
        case Left(message) => fail(s"could not write the snapshot: $message")
        case Right(()) =>
          assertEquals(SnapshotFile.read(path), Right(collected))
    finally Files.deleteIfExists(path)

  test("the CLI saves snapshots and diffs them end to end"):
    val connection = Vector("-u", TestPostgres.container.getJdbcUrl, "--user", TestPostgres.container.getUsername)
    val env = Map("PGPASSWORD" -> TestPostgres.container.getPassword)
    val first = Files.createTempFile("pgtop-first", ".json")
    val second = Files.createTempFile("pgtop-second", ".json")
    val output = ByteArrayOutputStream()
    val printed = PrintStream(output)
    try
      assertEquals(Main.run("save" +: first.toString +: connection, env, printed, printed), 0)
      runWorkload()
      assertEquals(Main.run("save" +: second.toString +: connection, env, printed, printed), 0)
      output.reset()
      assertEquals(Main.run(Vector("diff", first.toString, second.toString), Map.empty, printed, printed), 0)
      val text = output.toString
      assert(text.contains("database pgtop"), text)
      assert(text.contains("window"), text)
      assert(text.contains("seq scans +2"), text)
      assert(!text.contains("\u001b["), text)
    finally
      Files.deleteIfExists(first)
      Files.deleteIfExists(second)

  /** Two more sequential scans of public.orders for the second snapshot to see. */
  private def runWorkload(): Unit =
    Using.resource(
      DriverManager.getConnection(
        TestPostgres.container.getJdbcUrl,
        TestPostgres.container.getUsername,
        TestPostgres.container.getPassword
      )
    ) { connection =>
      (1 to 2).foreach(_ =>
        Using.resource(connection.createStatement())(statement =>
          statement.execute("SELECT count(*) FROM public.orders WHERE status = 'new'")
        )
      )
      // pg_stat_* counters are flushed by the statistics collector, not instantly.
      Thread.sleep(1_500)
    }
