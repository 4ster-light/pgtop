package pgtop.collector

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
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
  /** The URL without credentials; the driver does not understand `user:pass@host`. */
  def jdbcUrl: String =
    url match
      case Some(raw) => ConnectionConfig.normalise(raw).jdbcUrl
      case None =>
        val resolvedHost = host.getOrElse("localhost")
        val resolvedPort = port.getOrElse(5432)
        val resolvedDatabase = database.orElse(user).getOrElse("postgres")
        s"jdbc:postgresql://$resolvedHost:$resolvedPort/$resolvedDatabase"

  /** Credentials carried by the URL, if any. Flags and environment win over these. */
  private def urlCredentials: (Option[String], Option[String]) =
    url.map(ConnectionConfig.normalise).map(parsed => (parsed.user, parsed.password)).getOrElse((None, None))

  /** Connection properties; the password never appears in `describe`. */
  def properties: Map[String, String] =
    val base = Map(
      "ApplicationName" -> applicationName,
      "connectTimeout" -> connectTimeoutSeconds.toString,
      "loginTimeout" -> connectTimeoutSeconds.toString
    )
    val (urlUser, urlPassword) = urlCredentials
    base ++
      user.orElse(urlUser).map("user" -> _) ++
      password.orElse(urlPassword).map("password" -> _) ++
      sslMode.map("sslmode" -> _)

  def asProperties: Properties =
    val props = new Properties()
    properties.foreach { case (key, value) => props.setProperty(key, value) }
    props

  /** Safe description for logs and report headers: never includes the password. */
  def describe: String = jdbcUrl.stripPrefix("jdbc:")

object ConnectionConfig:
  val empty: ConnectionConfig = ConnectionConfig()

  /** A URL split into a driver URL and its credentials. */
  private[collector] final case class ParsedUrl(
    jdbcUrl: String,
    user: Option[String],
    password: Option[String]
  )

  /** Accepts `postgresql://`, `postgres://` and `jdbc:postgresql://` forms and
    * moves the libpq-style `user:password@` userinfo out of the authority, where
    * the JDBC driver cannot read it.
    */
  private[collector] def normalise(raw: String): ParsedUrl =
    val trimmed = raw.trim
    val (prefix, rest) = trimmed match
      case value if value.startsWith("jdbc:postgresql://") =>
        ("jdbc:postgresql://", value.drop("jdbc:postgresql://".length))
      case value if value.startsWith("postgresql://") =>
        ("jdbc:postgresql://", value.drop("postgresql://".length))
      case value if value.startsWith("postgres://") =>
        ("jdbc:postgresql://", value.drop("postgres://".length))
      case value => ("", value)
    val authorityEnd = rest.indexWhere(c => c == '/' || c == '?')
    val authority = if authorityEnd < 0 then rest else rest.take(authorityEnd)
    val tail = if authorityEnd < 0 then "" else rest.drop(authorityEnd)
    authority.split("@", 2).toList match
      case userInfo :: hostPart :: Nil =>
        val credentials = userInfo.split(":", 2)
        ParsedUrl(
          jdbcUrl = prefix + hostPart + tail,
          user = credentials.headOption.map(decode).filter(_.nonEmpty),
          password = credentials.lift(1).map(decode)
        )
      case _ => ParsedUrl(prefix + rest, None, None)

  private def decode(value: String): String =
    URLDecoder.decode(value, StandardCharsets.UTF_8)

  /** Reads the usual libpq environment variables. `PGPASSWORD` is preferred over
    * a command-line flag, which would leak through `ps`.
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
