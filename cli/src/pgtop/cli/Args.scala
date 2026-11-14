package pgtop.cli

import pgtop.collector.ConnectionConfig
import pgtop.domain.Severity
import pgtop.report.Format

/** When to emit ANSI colours. `Auto` means "only on a terminal, honouring NO_COLOR". */
enum ColorMode:
  case Auto, Always, Never

/** What the user asked for on the command line. */
final case class Args(
  connection: ConnectionConfig,
  format: Format = Format.Terminal,
  output: Option[String] = None,
  minSeverity: Severity = Severity.Info,
  limit: Int = 25,
  colorMode: ColorMode = ColorMode.Auto,
  explain: Boolean = false,
  explainLimit: Int = 3,
  failOn: Option[Severity] = None
)

/** Parsing the command line, including the built-in help and version entries. */
enum Invocation:
  case Help
  case Version
  case Run(args: Args)

object Args:
  val usage: String =
    """pgtop — PostgreSQL workload advisor
      |
      |Usage: pgtop [options]
      |
      |Connection (flags win over the environment):
      |  -u, --url URL          PostgreSQL URL, e.g. postgresql://user@host:5432/db
      |      --host HOST        database host (PGHOST)
      |      --port PORT        database port (PGPORT)
      |  -d, --dbname NAME      database name (PGDATABASE)
      |      --user USER        user name (PGUSER)
      |      --sslmode MODE     sslmode passed to the driver (PGSSLMODE)
      |
      |Output:
      |  -f, --format FORMAT    terminal (default) or markdown
      |  -o, --output PATH      write the report to a file
      |  -n, --limit N          maximum number of findings to print (default 25)
      |  -m, --min-severity S   critical, high, medium, low or info (default info)
      |      --color            always emit ANSI colours
      |      --no-color         never emit ANSI colours (also honoured: NO_COLOR)
      |
      |Analysis:
      |      --explain          run EXPLAIN (ANALYZE) on the most expensive statements
      |      --explain-limit N  how many statements to explain (default 3)
      |      --fail-on S        exit with status 3 when a finding at S or worse exists
      |
      |Other:
      |  -h, --help             show this help
      |  -V, --version          show the version
      |""".stripMargin

  def parse(argv: Vector[String], env: Map[String, String]): Either[String, Invocation] =
    val fromEnv = ConnectionConfig.fromEnvironment(env.get)
    loop(argv.toList, Args(connection = fromEnv))

  private def loop(remaining: List[String], args: Args): Either[String, Invocation] =
    remaining match
      case Nil => Right(Invocation.Run(args))
      case ("-h" | "--help") :: _ => Right(Invocation.Help)
      case ("-V" | "--version") :: _ => Right(Invocation.Version)
      case "--color" :: rest => loop(rest, args.copy(colorMode = ColorMode.Always))
      case "--no-color" :: rest => loop(rest, args.copy(colorMode = ColorMode.Never))
      case "--explain" :: rest => loop(rest, args.copy(explain = true))
      case flag :: rest =>
        val (name, inlineValue) = flag.indexOf('=') match
          case -1 => (flag, None)
          case at => (flag.take(at), Some(flag.drop(at + 1)))
        withValue(name, inlineValue, rest) match
          case None => Left(s"unknown option: $flag")
          case Some(Left(error)) => Left(error)
          case Some(Right((value, tail))) => applyOption(name, value, tail, args)

  private def withValue(
    name: String,
    inlineValue: Option[String],
    rest: List[String]
  ): Option[Either[String, (String, List[String])]] =
    if !knownValueOption(name) then None
    else
      Some(
        (inlineValue, rest) match
          case (Some(value), tail) => Right((value, tail))
          case (None, value :: tail) => Right((value, tail))
          case (None, Nil) => Left(s"$name needs a value")
      )

  private def knownValueOption(name: String): Boolean =
    Set(
      "-u",
      "--url",
      "--host",
      "--port",
      "-d",
      "--dbname",
      "--user",
      "--sslmode",
      "-f",
      "--format",
      "-o",
      "--output",
      "-n",
      "--limit",
      "-m",
      "--min-severity",
      "--explain-limit",
      "--fail-on"
    ).contains(name)

  private def applyOption(
    name: String,
    value: String,
    rest: List[String],
    args: Args
  ): Either[String, Invocation] =
    val connection = args.connection
    name match
      case "-u" | "--url" => loop(rest, args.copy(connection = connection.copy(url = Some(value))))
      case "--host" => loop(rest, args.copy(connection = connection.copy(host = Some(value))))
      case "--port" =>
        value.toIntOption.filter(port => port > 0 && port < 65536) match
          case Some(port) => loop(rest, args.copy(connection = connection.copy(port = Some(port))))
          case None => Left(s"invalid port: $value")
      case "-d" | "--dbname" => loop(rest, args.copy(connection = connection.copy(database = Some(value))))
      case "--user" => loop(rest, args.copy(connection = connection.copy(user = Some(value))))
      case "--sslmode" => loop(rest, args.copy(connection = connection.copy(sslMode = Some(value))))
      case "-f" | "--format" =>
        Format.parse(value) match
          case Some(format) => loop(rest, args.copy(format = format))
          case None => Left(s"unknown format: $value (expected terminal or markdown)")
      case "-o" | "--output" => loop(rest, args.copy(output = Some(value)))
      case "-n" | "--limit" =>
        value.toIntOption.filter(_ >= 0) match
          case Some(limit) => loop(rest, args.copy(limit = limit))
          case None => Left(s"invalid limit: $value")
      case "-m" | "--min-severity" =>
        Severity.parse(value) match
          case Some(severity) => loop(rest, args.copy(minSeverity = severity))
          case None => Left(s"unknown severity: $value")
      case "--explain-limit" =>
        value.toIntOption.filter(_ >= 0) match
          case Some(limit) => loop(rest, args.copy(explainLimit = limit))
          case None => Left(s"invalid explain limit: $value")
      case "--fail-on" =>
        Severity.parse(value) match
          case Some(severity) => loop(rest, args.copy(failOn = Some(severity)))
          case None => Left(s"unknown severity: $value")
      case other => Left(s"unknown option: $other")
