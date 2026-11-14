# pgtop

`pgtop` reads PostgreSQL workload statistics and prints **ranked, actionable
findings** with a concrete SQL fix for each one. It is not a dashboard: the
output is advice you can act on immediately, and every finding carries its
evidence so you can disagree with it.

```
pgtop 0.1.0  ·  database app  ·  PostgreSQL 17.2  ·  snapshot 2026-11-13T10:00:00Z
────────────────────────────────────────────────────────────────────────────────
  4 findings  2 high, 1 medium, 1 low

 1  HIGH      Index public.idx_orders_email has not been used
      rule unused-index · public.idx_orders_email

      0 scans since the statistics were last reset while occupying 200.0 MiB.
      Every write to public.orders maintains it.

      evidence
        index scans                0
        index size                 200.0 MiB
        definition                 CREATE INDEX idx_orders_email ON public.orders USING btree (email)

      fix  Drop public.idx_orders_email; nothing reads it.
        DROP INDEX CONCURRENTLY IF EXISTS public.idx_orders_email;
        · Counters reset with pg_stat_reset(); an index created after the reset has had little time to be used.
        · Check for monthly or ad-hoc reports that run rarely before dropping.
```

Markdown output (`--format markdown`) renders the same findings with evidence
tables and fenced SQL blocks, ready for a pull request or a ticket.

## Findings

| Rule | What it looks at | Typical fix |
|---|---|---|
| `missing-index` | tables read mostly by sequential scans, with the filter column inferred from `pg_stat_statements` | `CREATE INDEX CONCURRENTLY` |
| `unused-index` | valid, non-constraint indexes with no scans | `DROP INDEX CONCURRENTLY` |
| `vacuum-debt` | dead tuples above a ratio and an absolute floor | `VACUUM (ANALYZE)` + autovacuum thresholds |
| `duplicate-index` | indexes with the same table, method, key columns, predicate and uniqueness | drop the redundant twin |
| `expensive-statement` | statements with the largest cumulative execution time | `EXPLAIN (ANALYZE, BUFFERS)` on the real statement |

Findings are ranked by severity (critical, high, medium, low, info), then by the
rule's own impact estimate. Thresholds live in `pgtop.heuristics.Thresholds`.

## Quick start

```console
$ nix develop                     # JDK 21, Mill, scala-cli, scalafmt, scalafix, Postgres
$ export DATABASE_URL=postgresql://postgres@localhost:5432/app
$ mill cli.run                    # terminal report
$ mill cli.run --format markdown --output report.md
$ mill cli.run --min-severity high --fail-on high   # exit 3 when something is bad enough for CI
```

Without Nix, use the wrapper `./mill` from a Mill 1.3.1 installation and JDK 21.

Connection settings come from flags (`--url`, `--host`, `--port`, `--dbname`,
`--user`, `--sslmode`) or the usual `PG*` variables (`DATABASE_URL`, `PGHOST`,
`PGPORT`, `PGDATABASE`, `PGUSER`, `PGPASSWORD`, `PGSSLMODE`). `PGPASSWORD` is the
supported way to pass a password: a command-line flag would be visible in `ps`.

`--explain` additionally runs `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)` on the
most expensive statements. Only read-only statements are explained, and they run
inside a transaction that is rolled back, because `EXPLAIN ANALYZE` executes the
statement.

## Why Mill, not sbt

- The whole build is one `build.mill` in plain Scala, readable top to bottom. No
  plugin resolution, no `project/` directory, no boot overhead.
- `moduleDeps` makes the layering a compile-time fact: `heuristics` cannot see
  JDBC because `collector` is not on its classpath.
- The custom tasks this project needs (`lint.check`, `lint.fmt`, `lint.fixCheck`)
  are ordinary Scala methods, not plugins.
- For a single-module CLI with a handful of internal layers, sbt's advantages
  (plugin ecosystem, IDE paired with scala-cli, publishing conventions) do not
  apply.

Mill's `scalafmt` integration only formats, so formatting is driven by the
`scalafmt` CLI (`lint.fmt`, `lint.fmtCheck`) and linting by the `scalafix` CLI
(`lint.fixCheck`). Both are wired into `lint.check`, which is what CI runs.

## Layout

```
build.mill           Mill build: modules, strict scalac options, lint tasks
flake.nix            flake-parts: devShell, package/app, checks
.scalafmt.conf       formatting rules (scalafmt 3.11.5)
.scalafix.conf       OrganizeImports + DisableSyntax (no vars, throws, nulls, returns)

core/                domain: Snapshot, TableStats, IndexStats, StatementStats,
                     Finding (severity, evidence, SuggestedFix), Analysis
collector/           JDBC access to pg_stat_statements, pg_stat_user_tables,
                     pg_stat_user_indexes, pg_stat_activity; SnapshotCollector
heuristics/          pure rules: snapshot -> findings, registered in RuleRegistry
explain/             optional EXPLAIN (ANALYZE, FORMAT JSON) runner, parsed with circe
report/              terminal (ranked, colored) and Markdown rendering
cli/                 argument parsing, wiring, exit codes
itest/               integration test against a throwaway PostgreSQL (testcontainers)
```

Dependency rule, enforced by `moduleDeps`:

```
core  <-  collector        collector never mentions findings
core  <-  heuristics       pure, no IO, no JDBC
core  <-  report           pure, no terminal state, no JDBC
core <- collector <- explain
all of the above  <-  cli
```

## Quality gates

```console
$ mill lint.check          # scalafmt --test + scalafix --check
$ mill __.test             # unit tests (core, heuristics, report)
$ mill itest.test          # integration test, needs Docker
$ nix run .#check          # fmt + fix + unit tests with the flake toolchain
$ nix flake check          # validates the flake outputs
```

`-Werror` plus `-Wunused:all`, `-Wdeprecation`, `-Wfeature` means a warning is a
build failure. `DisableSyntax` rejects `var`, `throw`, `null` and `return` in the
Scala sources; `OrganizeImports` keeps import blocks sorted.

The gate runs as `apps.check` / `apps.integration` rather than as a `checks`
derivation: Mill resolves Maven dependencies at build time, and a sandboxed Nix
build has neither a dependency lock nor the JVM that Mill fetches for itself.
The apps run the same commands in the project tree with the dev shell toolchain.

`nix run . -- --help` launches the CLI from a writable copy of the source in
`~/.cache/pgtop`, compiling on first use. First run needs network for
dependencies; after that it is cached.
