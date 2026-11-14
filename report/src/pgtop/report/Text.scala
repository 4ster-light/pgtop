package pgtop.report

/** Line-oriented text helpers for the terminal renderer. */
object Text:
  /** Greedy wrap on whitespace. Words longer than the width stay intact. */
  def wrap(text: String, width: Int): Vector[String] =
    val limit = math.max(width, 1)
    text.split("\\s+").filter(_.nonEmpty).foldLeft(Vector.empty[String]) { (lines, word) =>
      lines.lastOption match
        case Some(last) if last.nonEmpty && last.length + 1 + word.length <= limit => lines.init :+ s"$last $word"
        case _ => lines :+ word
    }

  def indent(lines: Iterable[String], prefix: String): Vector[String] =
    lines.iterator.map(line => if line.isEmpty then line else prefix + line).toVector

  def rule(width: Int, character: String = "─"): String =
    character * math.max(width, 1)
