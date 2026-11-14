package pgtop.itest

import java.sql.{Connection, DriverManager}
import scala.util.Using

import org.testcontainers.containers.PostgreSQLContainer

import pgtop.collector.ConnectionConfig

/** The concrete container type; the generic base class needs a self type. */
final class PgtopPostgres extends PostgreSQLContainer[PgtopPostgres]("postgres:17-alpine"):
  withDatabaseName("pgtop")
  withUsername("pgtop")
  withPassword("pgtop")
  withCommand("postgres", "-c", "shared_preload_libraries=pg_stat_statements")

/** A throwaway PostgreSQL with a small, deliberately unhealthy schema.
  *
  * The container starts once per test run and is stopped by a shutdown hook.
  * `pg_stat_statements` is preloaded so the statement-level rules have data.
  */
object TestPostgres:
  lazy val container: PgtopPostgres =
    val box = new PgtopPostgres()
    box.start()
    Runtime.getRuntime.addShutdownHook(new Thread(() => box.stop()))
    seed(box)
    box

  def config: ConnectionConfig =
    ConnectionConfig(
      url = Some(container.getJdbcUrl),
      user = Some(container.getUsername),
      password = Some(container.getPassword)
    )

  private def seed(box: PgtopPostgres): Unit =
    Using.resource(DriverManager.getConnection(box.getJdbcUrl, box.getUsername, box.getPassword)) { connection =>
      connection.setAutoCommit(true)
      statements.foreach(sql => execute(connection, sql))
    }
    // pg_stat_* counters are flushed by the statistics collector, not instantly.
    Thread.sleep(1_500)

  private def execute(connection: Connection, sql: String): Unit =
    Using.resource(connection.createStatement())(statement => statement.execute(sql))

  private def statements: Vector[String] = Vector(
    "CREATE EXTENSION IF NOT EXISTS pg_stat_statements",
    """CREATE TABLE public.orders (
      |  id bigserial PRIMARY KEY,
      |  customer_id bigint NOT NULL,
      |  status text NOT NULL,
      |  created_at timestamptz NOT NULL DEFAULT now()
      |)""".stripMargin,
    """INSERT INTO public.orders (customer_id, status)
      |SELECT g % 1000, CASE WHEN g % 2 = 0 THEN 'new' ELSE 'shipped' END
      |  FROM generate_series(1, 50000) AS g""".stripMargin,
    "CREATE INDEX idx_orders_customer_id ON public.orders (customer_id)",
    "CREATE INDEX idx_orders_customer_id_dup ON public.orders (customer_id)",
    "CREATE INDEX idx_orders_status_unused ON public.orders (status)",
    "CREATE TABLE public.events (id bigserial PRIMARY KEY, payload text NOT NULL)",
    "INSERT INTO public.events (payload) SELECT repeat('x', 200) FROM generate_series(1, 20000) g",
    "DELETE FROM public.events WHERE id % 2 = 0",
    "SELECT count(*) FROM public.orders WHERE status = 'new'",
    "SELECT count(*) FROM public.orders WHERE customer_id = 42"
  )
