package pgtop.heuristics

import pgtop.domain.{IndexName, RelationName}

/** Identifier quoting for the SQL we hand back to the user. */
object SqlName:
  private val SimpleIdentifier = "[a-z_][a-z0-9_$]*".r

  /** PostgreSQL truncates identifiers at 63 bytes; proposed names stay inside that. */
  val MaxIdentifierBytes: Int = 63

  def quote(identifier: String): String =
    if SimpleIdentifier.matches(identifier) then identifier
    else "\"" + identifier.replace("\"", "\"\"") + "\""

  def qualified(relation: RelationName): String =
    s"${quote(relation.schema)}.${quote(relation.name)}"

  def qualified(index: IndexName): String =
    s"${quote(index.schema)}.${quote(index.name)}"

  /** `idx_<table>_<column>`, truncated so the server accepts it unchanged. */
  def proposedIndexName(relation: RelationName, column: String): String =
    val raw = s"idx_${relation.name}_$column".toLowerCase.replaceAll("[^a-z0-9_]", "_")
    val bytes = raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).take(MaxIdentifierBytes)
    new String(bytes, java.nio.charset.StandardCharsets.UTF_8)
