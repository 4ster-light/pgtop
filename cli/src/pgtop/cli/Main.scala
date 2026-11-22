package pgtop.cli

import java.io.PrintStream
import java.nio.file.Path
import java.sql.SQLException

import pgtop.collector.SnapshotCollector
import pgtop.domain.{Analysis, Severity, Snapshot, SnapshotDiff}
import pgtop.explain.{ExplainOutcome, Explainer}
import pgtop.heuristics.Engine
import pgtop.report.{DiffInput, RenderOptions, Report, ReportInput}
import pgtop.store.SnapshotFile

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
      case Right(Invocation.Save(args, path)) => saveSnapshot(args, path, out, err)
      case Right(Invocation.Diff(before, after, diffArgs)) => compare(before, after, diffArgs, env, out, err)

  private def execute(args: Args, env: Map[String, String], out: PrintStream, err: PrintStream): Int =
    collect(args) match
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
          color = colorsEnabled(args.colorMode, args.output.isEmpty, env, out),
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

  /** `pgtop save PATH`: one collection, one JSON file, no report. */
  private def saveSnapshot(args: Args, path: String, out: PrintStream, err: PrintStream): Int =
    collect(args) match
      case Left(message) =>
        err.println(s"pgtop: $message")
        1
      case Right(snapshot) =>
        SnapshotFile.write(Path.of(path), snapshot) match
          case Left(message) =>
            err.println(s"pgtop: $message")
            1
          case Right(()) =>
            out.println(
              s"pgtop: wrote $path · ${snapshot.tables.length} tables · ${snapshot.indexes.length} indexes · " +
                s"${snapshot.statements.length} statements · captured ${snapshot.capturedAt}"
            )
            snapshot.warnings.foreach(warning => err.println(s"pgtop: note: $warning"))
            0

  /** `pgtop diff BEFORE AFTER`: two files in, rates out. No database involved. */
  private def compare(
    before: String,
    after: String,
    args: DiffArgs,
    env: Map[String, String],
    out: PrintStream,
    err: PrintStream
  ): Int =
    (for
      earlier <- SnapshotFile.read(Path.of(before))
      later <- SnapshotFile.read(Path.of(after))
    yield SnapshotDiff.between(earlier, later)) match
      case Left(message) =>
        err.println(s"pgtop: $message")
        1
      case Right(diff) =>
        val options = RenderOptions(
          limit = args.limit,
          color = colorsEnabled(args.colorMode, args.output.isEmpty, env, out),
          width = terminalWidth(env)
        )
        val rendered = Report.renderDiff(DiffInput(diff, version), args.format, options)
        write(rendered, args.output, out) match
          case Left(message) =>
            err.println(s"pgtop: $message")
            1
          case Right(()) =>
            (diff.before.warnings ++ diff.after.warnings).distinct
              .foreach(warning => err.println(s"pgtop: note: $warning"))
            0

  /** One collection over the configured connection, or the reason it failed. */
  private def collect(args: Args): Either[String, Snapshot] =
    try Right(SnapshotCollector.jdbc(args.connection).collect())
    catch
      case error: SQLException =>
        Left(s"could not read ${args.connection.describe}: ${Option(error.getMessage).getOrElse("unknown error")}")

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

  /** Piped output and NO_COLOR keep colour off by default; the flags override. */
  private def colorsEnabled(
    mode: ColorMode,
    toTerminal: Boolean,
    env: Map[String, String],
    out: PrintStream
  ): Boolean =
    mode match
      case ColorMode.Always => true
      case ColorMode.Never => false
      case ColorMode.Auto => toTerminal && env.get("NO_COLOR").isEmpty && (out eq System.out)

  private def terminalWidth(env: Map[String, String]): Int =
    env.get("COLUMNS").flatMap(_.toIntOption).filter(width => width >= 60).map(_ - 2).getOrElse(100)
