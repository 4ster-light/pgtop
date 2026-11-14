package pgtop.collector

/** The read-only queries pgtop runs. Kept in one place so they are easy to audit. */
private[collector] object Sql:
  val serverInfo: String =
    """SELECT version() AS server_version,
      |       current_database() AS database,
      |       pg_postmaster_start_time() AS postmaster_started_at,
      |       (SELECT stats_reset FROM pg_stat_database WHERE datname = current_database()) AS stats_reset""".stripMargin

  val pgStatStatementsVersion: String =
    "SELECT extversion FROM pg_extension WHERE extname = 'pg_stat_statements'"

  val tables: String =
    """SELECT n.nspname AS schema_name,
      |       c.relname AS table_name,
      |       COALESCE(s.seq_scan, 0) AS seq_scan,
      |       COALESCE(s.idx_scan, 0) AS idx_scan,
      |       COALESCE(s.n_live_tup, 0) AS n_live_tup,
      |       COALESCE(s.n_dead_tup, 0) AS n_dead_tup,
      |       COALESCE(s.n_mod_since_analyze, 0) AS n_mod_since_analyze,
      |       COALESCE(s.n_tup_ins, 0) AS n_tup_ins,
      |       COALESCE(s.n_tup_upd, 0) AS n_tup_upd,
      |       COALESCE(s.n_tup_del, 0) AS n_tup_del,
      |       s.last_vacuum, s.last_autovacuum, s.last_analyze, s.last_autoanalyze,
      |       pg_total_relation_size(c.oid) AS total_bytes,
      |       pg_table_size(c.oid) AS table_bytes,
      |       pg_indexes_size(c.oid) AS indexes_bytes
      |  FROM pg_stat_user_tables s
      |  JOIN pg_class c ON c.oid = s.relid
      |  JOIN pg_namespace n ON n.oid = c.relnamespace
      | ORDER BY n.nspname, c.relname""".stripMargin

  val indexes: String =
    """SELECT n.nspname AS schema_name,
      |       c.relname AS table_name,
      |       i.relname AS index_name,
      |       am.amname AS method,
      |       ix.indisunique AS is_unique,
      |       ix.indisprimary AS is_primary,
      |       ix.indisvalid AS is_valid,
      |       pg_get_indexdef(i.oid) AS definition,
      |       COALESCE((SELECT array_agg(pg_get_indexdef(i.oid, k, true) ORDER BY k)
      |                   FROM generate_series(1, ix.indnkeyatts) AS k), ARRAY[]::text[]) AS key_columns,
      |       COALESCE((SELECT array_agg(pg_get_indexdef(i.oid, k, true) ORDER BY k)
      |                   FROM generate_series(ix.indnkeyatts + 1, ix.indnatts) AS k), ARRAY[]::text[]) AS include_columns,
      |       NULLIF(pg_get_expr(ix.indpred, ix.indrelid), '') AS predicate,
      |       COALESCE(st.idx_scan, 0) AS idx_scan,
      |       COALESCE(st.idx_tup_read, 0) AS idx_tup_read,
      |       pg_relation_size(i.oid) AS size_bytes,
      |       EXISTS (SELECT 1 FROM pg_constraint con WHERE con.conindid = i.oid) AS constraint_backed
      |  FROM pg_index ix
      |  JOIN pg_class i ON i.oid = ix.indexrelid
      |  JOIN pg_class c ON c.oid = ix.indrelid
      |  JOIN pg_namespace n ON n.oid = c.relnamespace
      |  JOIN pg_am am ON am.oid = i.relam
      |  LEFT JOIN pg_stat_user_indexes st ON st.indexrelid = i.oid
      | WHERE n.nspname NOT IN ('pg_catalog', 'information_schema')
      | ORDER BY n.nspname, c.relname, i.relname""".stripMargin

  /** `pg_stat_statements` 1.8 renamed `total_time` to `total_exec_time`. */
  def statements(totalColumn: String, meanColumn: String): String =
    s"""SELECT queryid,
       |       query,
       |       calls,
       |       COALESCE($totalColumn, 0) AS total_ms,
       |       COALESCE($meanColumn, 0) AS mean_ms,
       |       COALESCE(rows, 0) AS rows,
       |       COALESCE(shared_blks_hit, 0) AS shared_blks_hit,
       |       COALESCE(shared_blks_read, 0) AS shared_blks_read,
       |       COALESCE(temp_blks_written, 0) AS temp_blks_written
       |  FROM pg_stat_statements
       | WHERE dbid = (SELECT oid FROM pg_database WHERE datname = current_database())
       |   AND query NOT ILIKE '%pg_stat_statements%'
       |   AND query NOT ILIKE '%pg_stat_user_%'
       | ORDER BY $totalColumn DESC
       | LIMIT ?""".stripMargin

  val statementTimeColumn: String =
    """SELECT column_name
      |  FROM information_schema.columns
      | WHERE table_name = 'pg_stat_statements'
      |   AND column_name IN ('total_exec_time', 'mean_exec_time')""".stripMargin

  val activity: String =
    """SELECT pid,
      |       usename,
      |       application_name,
      |       state,
      |       wait_event_type,
      |       wait_event,
      |       left(query, 500) AS query,
      |       EXTRACT(EPOCH FROM (now() - query_start)) AS running_seconds,
      |       backend_type
      |  FROM pg_stat_activity
      | WHERE backend_type = 'client backend'
      |   AND pid <> pg_backend_pid()
      | ORDER BY query_start NULLS LAST""".stripMargin
