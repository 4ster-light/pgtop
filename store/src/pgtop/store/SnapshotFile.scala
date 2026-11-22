package pgtop.store

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.util.{Failure, Success, Try}

import pgtop.domain.Snapshot

/** Snapshots on disk: whole files, read and written in one step. */
object SnapshotFile:
  def write(path: Path, snapshot: Snapshot): Either[String, Unit] =
    Try(Files.writeString(path, SnapshotJson.encode(snapshot), StandardCharsets.UTF_8)) match
      case Success(_) => Right(())
      case Failure(error) => Left(s"could not write $path: ${describe(error)}")

  def read(path: Path): Either[String, Snapshot] =
    if !Files.isRegularFile(path) then Left(s"no such snapshot file: $path")
    else
      Try(Files.readString(path, StandardCharsets.UTF_8)) match
        case Success(raw) => SnapshotJson.decode(raw)
        case Failure(error) => Left(s"could not read $path: ${describe(error)}")

  private def describe(error: Throwable): String =
    Option(error.getMessage).getOrElse(error.getClass.getSimpleName)
