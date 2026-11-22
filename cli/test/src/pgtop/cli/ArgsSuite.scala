package pgtop.cli

import pgtop.collector.ConnectionConfig
import pgtop.report.Format

class ArgsSuite extends munit.FunSuite:
  private val env: Map[String, String] = Map.empty
  private val base: Args = Args(connection = ConnectionConfig.empty)

  test("plain flags become a run invocation"):
    assertEquals(Args.parse(Vector.empty, env), Right(Invocation.Run(base)))
    assertEquals(
      Args.parse(Vector("-f", "markdown", "-n", "5"), env),
      Right(Invocation.Run(base.copy(format = Format.Markdown, limit = 5)))
    )

  test("save takes a path plus connection flags"):
    assertEquals(
      Args.parse(Vector("save", "snap.json", "-u", "postgresql://u@h/db"), env),
      Right(Invocation.Save(base.copy(connection = ConnectionConfig(url = Some("postgresql://u@h/db"))), "snap.json"))
    )

  test("save without a path is a usage error"):
    assert(Args.parse(Vector("save"), env).left.exists(_.contains("save needs a file path")))

  test("diff takes two paths plus presentation flags"):
    assertEquals(
      Args.parse(Vector("diff", "a.json", "b.json", "-f", "markdown", "--limit=3"), env),
      Right(Invocation.Diff("a.json", "b.json", DiffArgs(format = Format.Markdown, limit = 3)))
    )

  test("diff rejects short argument lists and connection flags"):
    assert(Args.parse(Vector("diff", "a.json"), env).left.exists(_.contains("diff needs two snapshot files")))
    assert(
      Args.parse(Vector("diff", "a.json", "b.json", "-u", "x"), env).left.exists(_.contains("unknown option for diff"))
    )

  test("help and version work from every command"):
    assertEquals(Args.parse(Vector("save", "-h"), env), Right(Invocation.Help))
    assertEquals(Args.parse(Vector("diff", "-V"), env), Right(Invocation.Version))
    assertEquals(Args.parse(Vector("--help"), env), Right(Invocation.Help))
