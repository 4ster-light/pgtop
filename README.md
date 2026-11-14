# pgtop

[![Scala](https://img.shields.io/badge/Scala-%23DC322F.svg?logo=scala&logoColor=white&style=flat-square)](https://www.scala-lang.org)
[![Mill](https://img.shields.io/badge/build-Mill-FF5A5F?style=flat-square)](build.mill)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-12%2B-4169E1?logo=postgresql&logoColor=white&style=flat-square)](https://www.postgresql.org)
[![Nix flake](https://img.shields.io/badge/Nix-flake-5277C3?logo=nixos&logoColor=white&style=flat-square)](flake.nix)
[![ci](https://img.shields.io/github/actions/workflow/status/4ster-light/pgtop/ci.yml?branch=main&style=flat-square&label=ci)](https://github.com/4ster-light/pgtop/actions/workflows/ci.yml)
[![lint](https://img.shields.io/github/actions/workflow/status/4ster-light/pgtop/lint.yml?branch=main&style=flat-square&label=lint)](https://github.com/4ster-light/pgtop/actions/workflows/lint.yml)
[![License: GPLv3](https://img.shields.io/badge/license-GPLv3-blue?style=flat-square)](LICENSE)

`pgtop` connects to a PostgreSQL instance, reads the workload statistics the
server already keeps, and prints **ranked findings with a ready-to-run fix**.
Not a dashboard: every line is advice, every finding shows its evidence, and the
suggested SQL can be pasted into `psql`.

![pgtop report](img/report.svg)

## Contents

- [Why](#why)
- [What it reports](#what-it-reports)
- [Quick start](#quick-start)
- [Output formats](#output-formats)
- [How the rules decide](#how-the-rules-decide)
- [Architecture](#architecture)
- [Build and tooling](#build-and-tooling)
- [Flake outputs](#flake-outputs)
- [Quality gates](#quality-gates)
- [Requirements](#requirements)
- [Limitations](#limitations)
- [Roadmap](#roadmap)
- [License](#license)

## Why

`pg_stat_*` is full of answers, but it reports counters, not conclusions. You
still have to know that 3.6K sequential scans over a million rows matter, that an
index with zero scans costs write throughput, and that an idempotent
`CREATE INDEX CONCURRENTLY` is the safe way to fix the first one.

`pgtop` does that reading for you:

- **Actionable, not descriptive**: each finding carries a severity, the numbers
  behind it, and SQL that fixes it.
- **Ranked**: critical first, then by the rule's own impact estimate.
- **Read-only**: only queries the statistics views; `EXPLAIN` runs are opt-in and
  rolled back.
- **Honest about uncertainty**: when it cannot infer an index column it says so
  and hands you the query that will name it.

## What it reports

| Rule                  | Looks at                                                            | Severity driven by       | Fix                                                       |
|-----------------------|---------------------------------------------------------------------|--------------------------|-----------------------------------------------------------|
| `missing-index`       | tables read mostly by sequential scans                              | rows walked × table size | `CREATE INDEX CONCURRENTLY` on the inferred filter column |
| `unused-index`        | valid, non-constraint indexes with no scans                         | index size               | `DROP INDEX CONCURRENTLY`                                 |
| `vacuum-debt`         | dead tuples above a ratio and an absolute floor                     | estimated dead bytes     | `VACUUM (ANALYZE)` + autovacuum thresholds                |
| `duplicate-index`     | indexes with the same table, method, keys, predicate and uniqueness | reclaimable size         | drop the redundant twin                                   |
| `expensive-statement` | statements with the largest cumulative time                         | total execution time     | `EXPLAIN (ANALYZE, BUFFERS)` on the real statement        |

A finding looks like this:

```text
 1  CRITICAL  Sequential scans dominate reads on public.orders
      rule missing-index · public.orders

      3.6K sequential scans against 1 index scans over 1.0M live rows. Each scan
      walks the table (60.1 MiB), so roughly 362.5 GiB of reads are full-table
      work an index path could avoid.

      evidence
        sequential scans           3.6K
        sequential scan share      100.0%
        inferred filter column     status

      fix  Index public.orders on (status); it leads the filters of 1.2K calls
           seen in pg_stat_statements.
        CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_orders_status ON public.orders (status);
        · CONCURRENTLY cannot run inside a transaction block and briefly doubles index write cost.
```

## Quick start

```console
nix develop                                  # JDK 21, Mill, scala-cli, scalafmt, scalafix, Postgres
export DATABASE_URL=postgresql://postgres@localhost:5432/app
mill cli.run                                 # terminal report
mill cli.run -f markdown -o report.md        # Markdown export
mill cli.run -m high --fail-on high          # exits 3 when something is that bad
```

Without Nix, use a Mill 1.3 installation and JDK 21.

### Connection

Settings come from flags or the usual libpq environment:

| Flag           | Environment             | Meaning                               |
|----------------|-------------------------|---------------------------------------|
| `-u, --url`    | `DATABASE_URL`, `PGURL` | `postgresql://user:pass@host:5432/db` |
| `--host`       | `PGHOST`                | host, default `localhost`             |
| `--port`       | `PGPORT`                | port, default `5432`                  |
| `-d, --dbname` | `PGDATABASE`            | database, default the user name       |
| `--user`       | `PGUSER`                | user                                  |
|                | `PGPASSWORD`            | password, kept out of `ps`            |
| `--sslmode`    | `PGSSLMODE`             | e.g. `require`, `verify-full`         |

Credentials inside the URL are accepted and moved into driver properties.
`PGPASSWORD` is the supported way to pass a password: a flag would be visible in
`ps` and in shell history.

### Exit codes

| Code | Meaning                                          |
|------|--------------------------------------------------|
| `0`  | report produced, nothing at or above `--fail-on` |
| `1`  | could not connect or read the statistics         |
| `2`  | bad usage                                        |
| `3`  | `--fail-on` was triggered                        |

## Output formats

| Format   | Flag                    | Use                                               |
|----------|-------------------------|---------------------------------------------------|
| Terminal | `-f terminal` (default) | ranked, coloured, one finding at a time           |
| Markdown | `-f markdown`           | evidence tables and fenced SQL for a ticket or PR |

Colour follows the usual convention: `--color` forces it, `--no-color` disables
it, and `NO_COLOR` is honoured. Piped output is plain by default.

`--explain` additionally runs `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)` on the
most expensive statements and renders a plan summary. Only read-only statements
are explained, inside a transaction that is rolled back, because `EXPLAIN
ANALYZE` executes the statement.

## How the rules decide

Thresholds live in `pgtop.heuristics.Thresholds` and default to conservative
values: a finding that cannot be justified from the snapshot is not printed.

| Rule                  | Fires when                                                         | Default                   |
|-----------------------|--------------------------------------------------------------------|---------------------------|
| `missing-index`       | `seq_scan ≥ N`, `n_live_tup ≥ M`, `seq_scan ≥ R × idx_scan`        | 1000 scans, 10K rows, 10× |
| `unused-index`        | `idx_scan ≤ N`, size ≥ B, not constraint-backed, valid             | 50 scans, 4 MiB           |
| `vacuum-debt`         | `n_dead_tup ≥ N` and dead ratio ≥ R                                | 1000 tuples, 10%          |
| `duplicate-index`     | two valid indexes share table, method, keys, predicate, uniqueness | exact match               |
| `expensive-statement` | cumulative time ≥ T, top K                                         | 60 s, top 5               |

The missing-index rule infers a candidate column by scanning `pg_stat_statements`
for statements touching the table and reading the shape
`column <op> <parameter|literal>` inside `WHERE`. That is deliberately narrow: a
SQL parser is out of scope, and inventing a column would be worse than asking you
to look. When no column can be inferred the fix becomes the diagnostic query that
lists the offending statements.

## Architecture

```txt
core/        snapshot, tables, indexes, statements, findings, severity, suggested fixes
collector/   JDBC reads of pg_stat_statements, pg_stat_user_tables, pg_stat_user_indexes, pg_stat_activity
heuristics/  pure rules, a registry, query-shape inference
explain/     optional EXPLAIN (ANALYZE, FORMAT JSON) runner, parsed with circe
report/      terminal and Markdown rendering
cli/         argument parsing, wiring, exit codes
itest/       integration test against a throwaway PostgreSQL
```

`moduleDeps` makes the layering a compile-time fact:

```txt
core  ←  collector        collector never mentions findings
core  ←  heuristics       pure, no IO, no JDBC
core  ←  report           pure, no terminal state, no JDBC
core  ←  collector ← explain
all of the above ← cli
```

Two consequences that keep the code honest: heuristics cannot accidentally query
the database, and the renderers cannot accidentally decide what matters.

## Build and tooling

- **Mill 1.3, not sbt.** One `build.mill` in plain Scala, no plugin resolution and
  no `project/` directory. `moduleDeps` states the layering in one place, and the
  lint tasks are ordinary methods. For a single CLI with six internal layers,
  sbt's plugin ecosystem and publishing conventions buy nothing.
- **Scala 3.7** with `-Werror`, `-Wunused:all` and `-Xsemanticdb`: a warning fails
  the build.
- **scalafmt 3.11** for formatting and **scalafix 0.14** with `OrganizeImports`
  and `DisableSyntax` (no `var`, `throw`, `null`, `return`). Both run from
  `mill lint.check`.
- **Nix flake (flake-parts)** for the dev shell, the CLI launcher and the gate.

## Flake outputs

| Output              | What it is                                                                |
|---------------------|---------------------------------------------------------------------------|
| `devShells.default` | JDK 21, Mill, scala-cli, scalafmt, scalafix 0.14, coursier, PostgreSQL 17 |
| `packages.default`  | `pgtop` launcher, builds into `~/.cache/pgtop` on first use               |
| `apps.default`      | same as `nix run . -- …`                                                  |
| `apps.check`        | `lint.check` + unit tests with the dev shell toolchain                    |
| `apps.integration`  | the testcontainers suite, needs Docker                                    |

The gate runs as an app rather than a `checks` derivation: Mill resolves Maven
dependencies and fetches its own JDK at build time, and a sandboxed Nix build has
neither a dependency lock nor that JDK. `nix flake check` validates the flake
outputs; `nix run .#check` runs the gate.

## Quality gates

```console
mill lint.check          # scalafmt --test + scalafix --check
mill __.test             # 57 unit tests: core, collector, heuristics, report
mill itest.test          # 4 integration tests against postgres:17-alpine
nix run .#check          # the same gate through the flake
```

| Suite             | Tests | Covers                                                             |
|-------------------|-------|--------------------------------------------------------------------|
| `core.test`       | 8     | severity ordering, ranking, formatting, signature normalisation    |
| `collector.test`  | 8     | connection URLs, credentials, libpq environment                    |
| `heuristics.test` | 33    | every rule against fixture snapshots, registry, query shapes       |
| `report.test`     | 8     | terminal and Markdown rendering, limits, severity filtering        |
| `itest.test`      | 4     | real collection, findings, rendering and EXPLAIN on a live cluster |

GitHub Actions runs both gates on every push and pull request: `ci.yml` runs the
four unit suites and the testcontainers integration test, `lint.yml` runs
`mill lint.check`. The badges at the top of this file report their status.

## Requirements

- PostgreSQL 12 or newer (`indnkeyatts` in `pg_index`).
- `pg_stat_statements` for the statement-level rules. Without it, `pgtop` still
  reports table and index findings and says so in a note.
- A role that can read `pg_stat_activity` and the statistics views; `pg_monitor`
  is enough on a managed instance.

## Limitations

- The missing-index rule infers a single leading filter column and only from
  `column <op> value` shapes. Anything more elaborate becomes a diagnostic query.
- Counters are cumulative since the last `pg_stat_reset()`; the report prints the
  reset time so the numbers can be judged.
- Duplicate detection uses exact signature equality, not prefix coverage: `(a)`
  and `(a, b)` are not reported as duplicates yet.
- `EXPLAIN` needs literal statements; parameterised queries are skipped and noted.

## Roadmap

1. **Snapshot diffing over time.** Persist snapshots, diff two of them, and turn
   counters into rates: scans per hour, dead-tuple growth, index usage since a
   deploy. The untested-but-important question is "is this regressing?", and it
   needs history.
2. Rule inputs switch from a snapshot to a diff, so `unused-index` becomes
   "unused for 14 days" and `missing-index` becomes "seq scans growing since the
   last release".
3. Prefix-covered duplicate detection.
4. Fold `--explain` output into the statement findings instead of a separate
   section.
5. SARIF export so findings annotate a pull request.

## License

GPL-3.0. See [LICENSE](LICENSE).
