package pgtop.domain

import java.time.Instant
import java.util.Locale

/** Number formatting shared by heuristics (explanations) and report rendering. */
object Format:
  private val KiB = 1024.0
  private val MiB = KiB * 1024.0
  private val GiB = MiB * 1024.0
  private val TiB = GiB * 1024.0

  /** Locale-independent formatting: reports must read the same on every machine. */
  private def fixed(value: Double, decimals: Int): String =
    String.format(Locale.ROOT, s"%.${decimals}f", Double.box(value))

  def bytes(value: Long): String =
    val magnitude = value.toDouble
    if magnitude >= TiB then s"${fixed(magnitude / TiB, 1)} TiB"
    else if magnitude >= GiB then s"${fixed(magnitude / GiB, 1)} GiB"
    else if magnitude >= MiB then s"${fixed(magnitude / MiB, 1)} MiB"
    else if magnitude >= KiB then s"${fixed(magnitude / KiB, 0)} KiB"
    else s"$value B"

  def count(value: Long): String =
    val magnitude = value.toDouble.abs
    val sign = if value < 0 then "-" else ""
    if magnitude >= 1e12 then sign + fixed(magnitude / 1e12, 1) + "T"
    else if magnitude >= 1e9 then sign + fixed(magnitude / 1e9, 1) + "B"
    else if magnitude >= 1e6 then sign + fixed(magnitude / 1e6, 1) + "M"
    else if magnitude >= 1e3 then sign + fixed(magnitude / 1e3, 1) + "K"
    else value.toString

  def milliseconds(value: Double): String =
    if value >= 60_000 then s"${fixed(value / 60_000, 1)} min"
    else if value >= 1_000 then s"${fixed(value / 1_000, 2)} s"
    else s"${fixed(value, 1)} ms"

  def percent(ratio: Double): String = s"${fixed(ratio * 100, 1)}%"

  def instant(value: Instant): String = value.toString
