package pgtop.cli

import java.io.PrintStream
import java.sql.SQLException

import pgtop.collector.SnapshotCollector
import pgtop.domain.{Analysis, Severity}
import pgtop.explain.{ExplainOutcome, Explainer}
import pgtop.heuristics.Engine
import pgtop.report.{RenderOptions, Report, ReportInput}

/** Command-line entry point: parses arguments, wires the pipeline, chooses an exit code. */
object Main:
  val version: String = "0.1.0"

  def main(argv: Array[String]): Unit =
    val exitCode = run(argv.toVector, sys.env)
    if exitCode != 0 then sys.exit(exitCode)

  def run(
    argv: Vector[String],
    env: Map[String, String],
    out: PrintStream = System.out,
    err: PrintStream = System.err
  ): Int =
    Args.parse(argv, env) match
      case Left(message) =>
        err.println(s"pgtop: $message")
        err.println(Args.usage)
        2
      case Right(Invocation.Help) =>
        out.println(Args.usage)
        0
      case Right(Invocation.Version) =>
        out.println(s"pgtop $version")
        0
      case Right(Invocation.Run(args)) => execute(args, env, out, err)

  private def execute(args: Args, env: Map[String, String], out: PrintStream, err: PrintStream): Int =
    val collected =
      try Right(SnapshotCollector.jdbc(args.connection).collect())
      catch
        case error: SQLException =>
          Left(s"could not read ${args.connection.describe}: ${Option(error.getMessage).getOrElse("unknown error")}")
    collected match
      case Left(message) =>
        err.println(s"pgtop: $message")
        1
      case Right(snapshot) =>
        val analysis = Engine.analyse(snapshot)
        val explainOutcome =
          if args.explain then Explainer.run(args.connection, snapshot.statements, args.explainLimit)
          else ExplainOutcome(Vector.empty, Vector.empty)
        val options = RenderOptions(
          minSeverity = args.minSeverity,
          limit = args.limit,
          color = colorsEnabled(args, env, out),
          width = terminalWidth(env)
        )
        val rendered = Report.render(ReportInput(analysis, explainOutcome.summaries, version), args.format, options)
        write(rendered, args.output, out) match
          case Left(message) =>
            err.println(s"pgtop: $message")
            1
          case Right(()) =>
            (analysis.warnings ++ explainOutcome.warnings).foreach(warning => err.println(s"pgtop: note: $warning"))
            gate(analysis, args.failOn)

  private def write(rendered: String, output: Option[String], out: PrintStream): Either[String, Unit] =
    output match
      case None =>
        out.print(rendered)
        Right(())
      case Some(path) =>
        try
          java.nio.file.Files.writeString(java.nio.file.Path.of(path), rendered)
          Right(())
        catch case error: java.io.IOException => Left(s"could not write $path: ${error.getMessage}")

  /** `--fail-on` turns findings into a CI gate: exit 3 when anything is at least that severe. */
  private def gate(analysis: Analysis, failOn: Option[Severity]): Int =
    failOn match
      case Some(threshold) if analysis.findings.exists(_.severity.level <= threshold.level) => 3
      case _ => 0

  private def colorsEnabled(args: Args, env: Map[String, String], out: PrintStream): Boolean =
    args.colorMode match
      case ColorMode.Always => true
      case ColorMode.Never => false
      case ColorMode.Auto => args.output.isEmpty && env.get("NO_COLOR").isEmpty && (out eq System.out)

  private def terminalWidth(env: Map[String, String]): Int =
    env.get("COLUMNS").flatMap(_.toIntOption).filter(width => width >= 60).map(_ - 2).getOrElse(100)
