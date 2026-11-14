package pgtop.heuristics

/** Rule tuning knobs. Defaults are deliberately conservative: a finding that
  * cannot be justified from the snapshot should not be printed at all.
  */
final case class Thresholds(
  unusedIndexMinBytes: Long = 4L * 1024 * 1024,
  unusedIndexMaxScans: Long = 50,
  missingIndexMinSequentialScans: Long = 1_000,
  missingIndexSequentialToIndexRatio: Double = 10.0,
  missingIndexMinLiveTuples: Long = 10_000,
  vacuumDebtMinDeadTuples: Long = 1_000,
  vacuumDebtRatio: Double = 0.10,
  vacuumDebtHighRatio: Double = 0.30,
  duplicateIndexMinBytes: Long = 0,
  expensiveStatementMinTotalMs: Double = 60_000,
  expensiveStatementLimit: Int = 5
)

object Thresholds:
  val default: Thresholds = Thresholds()
