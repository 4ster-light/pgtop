package pgtop.report

import pgtop.domain.{Analysis, PlanSummary, Severity, SnapshotDiff}

/** Output formats the CLI can ask for. */
enum Format:
  case Terminal, Markdown

object Format:
  def parse(raw: String): Option[Format] = raw.trim.toLowerCase match
    case "terminal" | "text" | "tty" => Some(Format.Terminal)
    case "markdown" | "md" => Some(Format.Markdown)
    case _ => None

/** Everything the renderers need. Rendering depends on nothing else. */
final case class ReportInput(analysis: Analysis, plans: Vector[PlanSummary], toolVersion: String)

/** The diff view: two snapshots already paired, with their window. */
final case class DiffInput(diff: SnapshotDiff, toolVersion: String)

final case class RenderOptions(
  minSeverity: Severity = Severity.Info,
  limit: Int = 25,
  color: Boolean = true,
  width: Int = 100
)

object Report:
  def render(input: ReportInput, format: Format, options: RenderOptions): String =
    format match
      case Format.Terminal => TerminalReport.render(input, options)
      case Format.Markdown => MarkdownReport.render(input, options)

  /** The diff view over two saved snapshots. */
  def renderDiff(input: DiffInput, format: Format, options: RenderOptions): String =
    format match
      case Format.Terminal => DiffReport.terminal(input, options)
      case Format.Markdown => DiffReport.markdown(input, options)
