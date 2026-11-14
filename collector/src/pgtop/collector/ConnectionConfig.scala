package pgtop.collector

import java.util.Properties

/** How to reach the server. Every field is optional so that flags, environment
  * variables and server defaults can be layered without sentinel values.
  */
final case class ConnectionConfig(
  url: Option[String] = None,
  host: Option[String] = None,
  port: Option[Int] = None,
  database: Option[String] = None,
  user: Option[String] = None,
  password: Option[String] = None,
  sslMode: Option[String] = None,
  applicationName: String = "pgtop",
  connectTimeoutSeconds: Int = 10
):
  /** JDBC URL, either passed through or assembled from the parts. */
  def jdbcUrl: String =
    url match
      case Some(raw) =>
        val trimmed = raw.trim
        if trimmed.startsWith("jdbc:postgresql://") then trimmed
        else if trimmed.startsWith("postgresql://") then "jdbc:postgresql://" + trimmed.drop("postgresql://".length)
        else if trimmed.startsWith("postgres://") then "jdbc:postgresql://" + trimmed.drop("postgres://".length)
        else trimmed
      case None =>
        val resolvedHost = host.getOrElse("localhost")
        val resolvedPort = port.getOrElse(5432)
        val resolvedDatabase = database.orElse(user).getOrElse("postgres")
        s"jdbc:postgresql://$resolvedHost:$resolvedPort/$resolvedDatabase"

  /** Connection properties; the password never appears in `describe`. */
  def properties: Map[String, String] =
    val base = Map(
      "ApplicationName" -> applicationName,
      "connectTimeout" -> connectTimeoutSeconds.toString,
      "loginTimeout" -> connectTimeoutSeconds.toString
    )
    base ++
      user.map("user" -> _) ++
      password.map("password" -> _) ++
      sslMode.map("sslmode" -> _)

  def asProperties: Properties =
    val props = new Properties()
    properties.foreach { case (key, value) => props.setProperty(key, value) }
    props

  /** Safe description for logs and report headers: never includes the password. */
  def describe: String =
    val withoutQuery = jdbcUrl.takeWhile(_ != '?')
    withoutQuery.stripPrefix("jdbc:")

object ConnectionConfig:
  val empty: ConnectionConfig = ConnectionConfig()

  /** Reads the usual libpq environment variables. `PGPASSWORD` is honoured but
    * preferred over a command-line flag, which would leak through `ps`.
    */
  def fromEnvironment(read: String => Option[String]): ConnectionConfig =
    ConnectionConfig(
      url = read("DATABASE_URL").orElse(read("PGURL")),
      host = read("PGHOST"),
      port = read("PGPORT").flatMap(_.toIntOption),
      database = read("PGDATABASE"),
      user = read("PGUSER"),
      password = read("PGPASSWORD"),
      sslMode = read("PGSSLMODE")
    )
