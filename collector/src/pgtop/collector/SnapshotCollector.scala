package pgtop.collector

import pgtop.domain.Snapshot

/** Captures one statistics snapshot. All JDBC access lives behind this interface. */
trait SnapshotCollector:
  def collect(): Snapshot

object SnapshotCollector:
  /** A collector that talks to PostgreSQL over JDBC with the given connection settings. */
  def jdbc(
    config: ConnectionConfig,
    clock: java.time.Clock = java.time.Clock.systemUTC(),
    statementLimit: Int = 200
  ): SnapshotCollector =
    JdbcSnapshotCollector(config, clock, statementLimit)
