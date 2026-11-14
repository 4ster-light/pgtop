package pgtop.collector

import java.sql.ResultSet
import java.time.{Duration, Instant}

/** Null-tolerant accessors: JDBC returns null, the domain never sees it. */
private[collector] object Rows:
  def text(rs: ResultSet, column: String): Option[String] = Option(rs.getString(column))

  def long(rs: ResultSet, column: String): Long = rs.getLong(column)

  def int(rs: ResultSet, column: String): Int = rs.getInt(column)

  def instant(rs: ResultSet, column: String): Option[Instant] =
    Option(rs.getTimestamp(column)).map(_.toInstant)

  def seconds(rs: ResultSet, column: String): Option[Duration] =
    val value = rs.getDouble(column)
    if rs.wasNull() then None else Some(Duration.ofMillis((value * 1000.0).toLong))

  def textArray(rs: ResultSet, column: String): Vector[String] =
    Option(rs.getArray(column)) match
      case None => Vector.empty
      case Some(sqlArray) =>
        sqlArray.getArray match
          case values: Array[?] => values.toVector.collect { case value: String => value }
          case _ => Vector.empty
