package pgtop.report

import pgtop.domain.Severity

/** ANSI styling, switchable so piped output stays clean. */
object Ansi:
  private val Reset = "\u001b[0m"

  def paint(code: String, text: String, enabled: Boolean): String =
    if enabled then s"\u001b[${code}m$text$Reset" else text

  def bold(text: String, enabled: Boolean): String = paint("1", text, enabled)

  def dim(text: String, enabled: Boolean): String = paint("2", text, enabled)

  def severityCode(severity: Severity): String = severity match
    case Severity.Critical => "1;31"
    case Severity.High => "31"
    case Severity.Medium => "33"
    case Severity.Low => "36"
    case Severity.Info => "2"

  /** Right-padded severity tag, upper case. */
  def severityTag(severity: Severity, enabled: Boolean): String =
    paint(severityCode(severity), severity.label.toUpperCase.padTo(8, ' '), enabled)
