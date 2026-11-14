package pgtop.collector

class ConnectionConfigSuite extends munit.FunSuite:
  test("moves libpq credentials out of the URL and into the driver properties"):
    val config = ConnectionConfig(url = Some("postgresql://app:s3cret@db.internal:5433/shop"))

    assertEquals(config.jdbcUrl, "jdbc:postgresql://db.internal:5433/shop")
    assertEquals(config.properties.get("user"), Some("app"))
    assertEquals(config.properties.get("password"), Some("s3cret"))
    assert(!config.describe.contains("s3cret"))

  test("accepts the postgres:// and jdbc:postgresql:// spellings"):
    val short = ConnectionConfig(url = Some("postgres://app@db.internal/shop"))
    val driver = ConnectionConfig(url = Some("jdbc:postgresql://db.internal/shop?sslmode=require"))

    assertEquals(short.jdbcUrl, "jdbc:postgresql://db.internal/shop")
    assertEquals(short.properties.get("user"), Some("app"))
    assertEquals(driver.jdbcUrl, "jdbc:postgresql://db.internal/shop?sslmode=require")

  test("decodes percent-encoded credentials"):
    val config = ConnectionConfig(url = Some("postgresql://app:p%40ss%2Fword@localhost/shop"))

    assertEquals(config.properties.get("password"), Some("p@ss/word"))

  test("flags win over the credentials carried by the URL"):
    val config = ConnectionConfig(
      url = Some("postgresql://url-user:url-pass@localhost/shop"),
      user = Some("flag-user"),
      password = Some("flag-pass")
    )

    assertEquals(config.properties.get("user"), Some("flag-user"))
    assertEquals(config.properties.get("password"), Some("flag-pass"))

  test("builds a URL from parts when none is given"):
    val config = ConnectionConfig(host = Some("db"), port = Some(6543), database = Some("shop"))

    assertEquals(config.jdbcUrl, "jdbc:postgresql://db:6543/shop")
    assertEquals(config.describe, "postgresql://db:6543/shop")

  test("defaults to localhost and the postgres database"):
    assertEquals(ConnectionConfig.empty.jdbcUrl, "jdbc:postgresql://localhost:5432/postgres")

  test("carries the application name, timeout and sslmode"):
    val config = ConnectionConfig(sslMode = Some("verify-full"), applicationName = "pgtop-test")

    assertEquals(config.properties.get("sslmode"), Some("verify-full"))
    assertEquals(config.properties.get("ApplicationName"), Some("pgtop-test"))
    assertEquals(config.properties.get("connectTimeout"), Some("10"))

  test("reads the libpq environment"):
    val env = Map("DATABASE_URL" -> "postgresql://app:pw@db/shop", "PGSSLMODE" -> "require")

    val config = ConnectionConfig.fromEnvironment(env.get)

    assertEquals(config.jdbcUrl, "jdbc:postgresql://db/shop")
    assertEquals(config.properties.get("sslmode"), Some("require"))
    assertEquals(config.properties.get("user"), Some("app"))
