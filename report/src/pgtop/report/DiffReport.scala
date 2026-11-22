package pgtop.report

import pgtop.domain.{Format, IndexDelta, SnapshotDiff, StatementDelta, TableDelta}

/** The diff view: two snapshots' counters as deltas and per-hour rates.
  *
  * The single-run report advises; the diff answers what moved between two
  * captures. Selection is shared between the formats, only the drawing differs.
  */
object DiffReport:
  def terminal(input: DiffInput, options: RenderOptions): String =
    val diff = input.diff
    val lines = Vector.newBuilder[String]
    lines += terminalHeader(input, options)
    lines += Text.rule(options.width)
    lines +=
      s"  from ${Format.instant(diff.before.capturedAt)} to ${Format.instant(diff.after.capturedAt)}" +
        s" · window ${Format.window(diff.window)}"
    if diff.countersReset then
      lines +=
        s"  ${Ansi.dim("note  counters were reset between the snapshots; deltas undercount real work", options.color)}"
    if diff.databasesDiffer then
      lines +=
        s"  ${Ansi.dim(
            s"note  the snapshots come from different databases (${diff.before.cluster.database}, " +
              s"${diff.after.cluster.database})",
            options.color
          )}"

    val tables = movedTables(diff)
    val shownTables = tables.take(math.max(options.limit, 0))
    if shownTables.nonEmpty then
      lines ++= section("scan traffic, ranked by growth per hour", options)
      shownTables.foreach(table => lines += terminalTableLine(table))
      hiddenNote(tables.length - shownTables.length, "more changed tables", options)
        .foreach(line => lines += line)

    val untouched = untouchedIndexes(diff)
    val shownUntouched = untouched.take(math.max(options.limit, 0))
    if shownUntouched.nonEmpty then
      lines ++= section("indexes untouched in the window", options)
      shownUntouched.foreach { index =>
        val size = index.sizeBytes.fold("")(bytes => s" · ${Format.bytes(bytes)}")
        lines += s"    ${index.name.qualified}$size"
      }
      hiddenNote(untouched.length - shownUntouched.length, "more untouched indexes", options)
        .foreach(line => lines += line)

    val created = createdObjects(diff)
    if created.nonEmpty then
      lines ++= section("created during the window", options)
      created.foreach(line => lines += s"    $line")

    val removed = removedObjects(diff)
    if removed.nonEmpty then
      lines ++= section("dropped during the window", options)
      removed.foreach(line => lines += s"    $line")

    val statements = movedStatements(diff)
    val shownStatements = statements.take(math.max(options.limit, 0))
    if shownStatements.nonEmpty then
      lines ++= section("statements, ranked by added execution time", options)
      shownStatements.foreach { statement =>
        lines += s"    ${queryLabel(statement.query)}"
        val parts = terminalStatementParts(statement)
        if parts.nonEmpty then lines += s"      ${Ansi.dim(parts.mkString(" · "), options.color)}"
      }
      hiddenNote(statements.length - shownStatements.length, "more moved statements", options)
        .foreach(line => lines += line)

    if tables.isEmpty && untouched.isEmpty && created.isEmpty && removed.isEmpty && statements.isEmpty then
      lines += ""
      lines += "  Nothing changed between the snapshots."
    lines.result().mkString("\n") + "\n"

  def markdown(input: DiffInput, options: RenderOptions): String =
    val diff = input.diff
    val lines = Vector.newBuilder[String]
    lines += "# pgtop diff"
    lines += ""
    lines ++= markdownMetadata(diff)
    if diff.countersReset then
      lines += ""
      lines += "> Counters were reset between the snapshots; deltas undercount real work."
    if diff.databasesDiffer then
      lines += ""
      lines +=
        s"> The snapshots come from different databases (`${diff.before.cluster.database}`, `${diff.after.cluster.database}`)."

    val tables = movedTables(diff)
    val shownTables = tables.take(math.max(options.limit, 0))
    if shownTables.nonEmpty then
      lines += ""
      lines += "## Scan traffic"
      lines += ""
      lines += "| Table | Seq scans | Index scans | Dead tuples | Writes |"
      lines += "|---|---|---|---|---|"
      shownTables.foreach { table =>
        val row = table match
          case created if created.appeared =>
            s"| `${Text.escapeCell(created.relation.qualified)}` | created | | | |"
          case removed if removed.dropped =>
            s"| `${Text.escapeCell(removed.relation.qualified)}` | dropped | | | |"
          case paired =>
            s"| `${Text.escapeCell(paired.relation.qualified)}` | " +
              s"${change(paired.sequentialScansDelta, paired.sequentialScansPerHour)} | " +
              s"${change(paired.indexScansDelta, paired.indexScansPerHour)} | " +
              s"${change(paired.deadTuplesDelta, paired.deadTuplesPerHour)} | " +
              s"${change(paired.writesDelta, paired.writesPerHour)} |"
        lines += row
      }
      if tables.length > shownTables.length then
        lines += ""
        lines += s"_+ ${tables.length - shownTables.length} more changed tables not shown._"

    val untouched = untouchedIndexes(diff)
    val shownUntouched = untouched.take(math.max(options.limit, 0))
    if shownUntouched.nonEmpty then
      lines += ""
      lines += "## Indexes untouched in the window"
      lines += ""
      lines += "| Index | Size |"
      lines += "|---|---|"
      shownUntouched.foreach { index =>
        lines += s"| `${index.name.qualified}` | ${Format.bytes(index.sizeBytes.getOrElse(0L))} |"
      }

    val created = createdObjects(diff)
    if created.nonEmpty then
      lines += ""
      lines += "## Created during the window"
      lines += ""
      created.foreach(line => lines += s"- $line")

    val removed = removedObjects(diff)
    if removed.nonEmpty then
      lines += ""
      lines += "## Dropped during the window"
      lines += ""
      removed.foreach(line => lines += s"- $line")

    val statements = movedStatements(diff)
    val shownStatements = statements.take(math.max(options.limit, 0))
    if shownStatements.nonEmpty then
      lines += ""
      lines += "## Statements, ranked by added execution time"
      lines += ""
      lines += "| Statement | Calls | Execution |"
      lines += "|---|---|---|"
      shownStatements.foreach { statement =>
        val row = statement match
          case created if created.appeared =>
            created.after.fold(s"| `${Text.escapeCell(queryLabel(created.query))}` | new in the window | |")(after =>
              s"| `${Text.escapeCell(queryLabel(created.query))}` | new · ${Format.count(after.calls)} | " +
                s"${Format.milliseconds(after.totalExecMs)} |"
            )
          case gone if gone.dropped =>
            s"| `${Text.escapeCell(queryLabel(gone.query))}` | gone in the window | |"
          case paired =>
            val calls = paired.callsDelta.fold("")(delta => signedCount(delta))
            val execution = paired.totalExecMsDelta.fold("")(delta =>
              signedMillis(delta) + execRateSuffix(delta, paired)
            )
            s"| `${Text.escapeCell(queryLabel(paired.query))}` | $calls | $execution |"
        lines += row
      }

    if tables.isEmpty && untouched.isEmpty && created.isEmpty && removed.isEmpty && statements.isEmpty then
      lines += ""
      lines += "Nothing changed between the snapshots."
    lines.result().mkString("\n") + "\n"

  private def terminalHeader(input: DiffInput, options: RenderOptions): String =
    val cluster = input.diff.after.cluster
    "  " + Vector(
      Ansi.bold(s"pgtop ${input.toolVersion} diff", options.color),
      s"database ${cluster.database}",
      cluster.serverVersion.split(" ").take(2).mkString(" ")
    ).mkString("  ·  ")

  private def terminalTableLine(table: TableDelta): String =
    if table.appeared then s"    ${table.relation.qualified}  created during the window"
    else if table.dropped then s"    ${table.relation.qualified}  dropped during the window"
    else
      val parts = Vector(
        table.sequentialScansDelta.map(delta =>
          s"seq scans ${signedCount(delta)}${rateSuffix(delta, table.sequentialScansPerHour)}"
        ),
        table.indexScansDelta.map(delta =>
          s"idx scans ${signedCount(delta)}${rateSuffix(delta, table.indexScansPerHour)}"
        ),
        table.deadTuplesDelta.map(delta =>
          s"dead tuples ${signedCount(delta)}${rateSuffix(delta, table.deadTuplesPerHour)}"
        ),
        table.writesDelta.map(delta => s"writes ${signedCount(delta)}${rateSuffix(delta, table.writesPerHour)}")
      ).flatten
      s"    ${table.relation.qualified.padTo(34, ' ')} ${parts.mkString(" · ")}"

  private def terminalStatementParts(statement: StatementDelta): Vector[String] =
    if statement.appeared then
      statement.after.fold(Vector("new in the window"))(after =>
        Vector(
          "new in the window",
          s"${Format.count(after.calls)} calls",
          s"${Format.milliseconds(after.totalExecMs)} exec"
        )
      )
    else if statement.dropped then Vector("gone in the window")
    else
      val parts = Vector.newBuilder[String]
      statement.callsDelta.foreach(delta => parts += s"calls ${signedCount(delta)}")
      statement.totalExecMsDelta.foreach { delta =>
        parts += s"exec ${signedMillis(delta)}${execRateSuffix(delta, statement)}"
      }
      statement.rowsDelta.filter(_ != 0).foreach(delta => parts += s"rows ${signedCount(delta)}")
      statement.tempBlocksWrittenDelta.filter(_ != 0).foreach(delta => parts += s"temp ${signedCount(delta)}")
      parts.result()

  /** Tables whose counters moved, ranked by their largest per-hour rate. */
  private def movedTables(diff: SnapshotDiff): Vector[TableDelta] =
    diff.tables
      .filter(table =>
        table.appeared ||
          table.dropped ||
          table.sequentialScansDelta.exists(_ != 0) ||
          table.indexScansDelta.exists(_ != 0) ||
          table.deadTuplesDelta.exists(_ != 0) ||
          table.writesDelta.exists(_ != 0)
      )
      .sortBy(table => -largestRate(table))

  private def largestRate(table: TableDelta): Double =
    Seq(
      table.sequentialScansPerHour,
      table.indexScansPerHour,
      table.deadTuplesPerHour,
      table.writesPerHour
    ).flatten.map(math.abs).fold(0.0)(math.max)

  /** Valid indexes present on both sides that no scan or tuple read touched. */
  private def untouchedIndexes(diff: SnapshotDiff): Vector[IndexDelta] =
    diff.indexes
      .filter(index => index.usedInWindow.contains(false) && index.after.exists(_.isValid))
      .sortBy(index => -index.sizeBytes.getOrElse(0L))

  /** Statements whose calls or execution time moved, plus the new and the gone. */
  private def movedStatements(diff: SnapshotDiff): Vector[StatementDelta] =
    diff.statements
      .filter(statement =>
        statement.appeared ||
          statement.dropped ||
          statement.callsDelta.exists(_ != 0) ||
          statement.totalExecMsDelta.exists(_ != 0)
      )
      .sortBy(statement => -statement.totalExecMsDelta.getOrElse(0.0))

  private def createdObjects(diff: SnapshotDiff): Vector[String] =
    diff.tables.filter(_.appeared).map(table => s"table ${table.relation.qualified}") ++
      diff.indexes.filter(_.appeared).map(index => s"index ${index.name.qualified}")

  private def removedObjects(diff: SnapshotDiff): Vector[String] =
    diff.tables.filter(_.dropped).map(table => s"table ${table.relation.qualified}") ++
      diff.indexes.filter(_.dropped).map(index => s"index ${index.name.qualified}")

  private def markdownMetadata(diff: SnapshotDiff): Vector[String] =
    Vector(
      "| | |",
      "|---|---|",
      s"| Database | `${diff.after.cluster.database}` |",
      s"| Server | ${diff.after.cluster.serverVersion.split(" ").take(2).mkString(" ")} |",
      s"| From | ${Format.instant(diff.before.capturedAt)} |",
      s"| To | ${Format.instant(diff.after.capturedAt)} |",
      s"| Window | ${Format.window(diff.window)} |"
    )

  /** `+60 (5.0/h)` for a counter that moved; a bare value for one that did not. */
  private def change(delta: Option[Long], rate: Option[Double]): String =
    delta.fold("")(value => s"${signedCount(value)}${rateSuffix(value, rate)}")

  private def section(title: String, options: RenderOptions): Vector[String] =
    Vector("", s"  ${Ansi.bold(title, options.color)}")

  private def hiddenNote(hidden: Int, what: String, options: RenderOptions): Option[String] =
    if hidden > 0 then Some(s"  ${Ansi.dim(s"+ $hidden $what", options.color)}") else None

  private def queryLabel(query: String): String =
    val text = query.replaceAll("\\s+", " ").trim
    if text.length > 72 then text.take(71) + "…" else text

  private def signedCount(delta: Long): String =
    if delta > 0 then s"+${Format.count(delta)}" else Format.count(delta)

  private def signedMillis(delta: Double): String =
    if delta > 0 then s"+${Format.milliseconds(delta)}" else Format.milliseconds(delta)

  /** ` (5.0/h)` for a moved counter; empty when nothing moved. */
  private def rateSuffix(delta: Long, rate: Option[Double]): String =
    if delta != 0 then rate.fold("")(value => s" (${Format.rate(value)})") else ""

  /** The ` (12.3 s/h)` suffix for a statement whose execution time moved. */
  private def execRateSuffix(delta: Double, statement: StatementDelta): String =
    if delta != 0.0 then statement.totalExecMsPerHour.fold("")(rate => s" (${Format.milliseconds(rate)}/h)") else ""
