package ecs

import java.sql.{Connection, PreparedStatement, ResultSet, ResultSetMetaData}
import javax.inject.{Inject, Singleton}
import play.api.db.Database

import scala.collection.mutable.ArrayBuffer
import scala.util.Using

/** SQL dialect of the active JDBC datasource. */
enum Dialect:
  case SQLite, MySQL

/** One result row, keyed by lower-cased column label. */
final case class Row(columns: Map[String, Int], values: Map[String, Any]):
  private def raw(key: String): Any = values.getOrElse(key.toLowerCase, throw new NoSuchElementException(s"column $key"))
  def isNull(key: String): Boolean = raw(key) == null
  def str(key: String): String = raw(key) match
    case null   => ""
    case v      => v.toString
  def optStr(key: String): Option[String] = if isNull(key) then None else Some(str(key))
  def long(key: String): Long = raw(key) match
    case null            => 0L
    case v: Number       => v.longValue
    case v               => v.toString.toLong
  def optLong(key: String): Option[Long] = if isNull(key) then None else Some(long(key))
  def int(key: String): Int = long(key).toInt
  def bool(key: String): Boolean = long(key) != 0
  def dec(key: String): BigDecimal = raw(key) match
    case null            => BigDecimal(0)
    case v: java.math.BigDecimal => BigDecimal(v)
    case v: Number       => BigDecimal(v.toString)
    case v               => BigDecimal(v.toString)
  override def toString: String = s"Row(${values.keys.mkString(",")})"

/**
 * Thin JDBC access layer shared by every repository.
 *
 * All statements use `?` placeholders; values are never interpolated into SQL.
 * SQL text may still embed dialect-specific fragments from [[ecs.Sql]], which is
 * the only place allowed to build dialect syntax.
 */
@Singleton
class Db @Inject() (database: Database, config: play.api.Configuration):
  val dialect: Dialect =
    val url = config.get[String]("db.default.url").toLowerCase
    if url.startsWith("jdbc:mysql") || url.startsWith("jdbc:mariadb") then Dialect.MySQL else Dialect.SQLite

  def isSQLite: Boolean = dialect == Dialect.SQLite
  def isMySQL: Boolean = dialect == Dialect.MySQL

  /** Run a read-only block on a pooled connection. */
  def readOnly[A](body: Connection => A): A =
    val conn = database.getConnection()
    try body(conn) finally conn.close()

  /**
   * Run `body` inside one transaction. Rollback on any throwable, including the
   * business errors the application layer raises to map onto HTTP 4xx.
   */
  def inTransaction[A](body: Connection => A): A =
    val conn = database.getConnection()
    val previous = conn.getAutoCommit
    conn.setAutoCommit(false)
    try
      val out = body(conn)
      conn.commit()
      out
    catch
      case e: Throwable =>
        try conn.rollback() catch { case _: Throwable => () }
        throw e
    finally
      try conn.setAutoCommit(previous) catch { case _: Throwable => () }
      conn.close()

  private def bindOne(stmt: PreparedStatement, i: Int, value: Any): Unit = value match
    case null                  => stmt.setObject(i, null)
    case None                  => stmt.setObject(i, null)
    case Some(inner)           => bindOne(stmt, i, inner)
    case v: BigDecimal         => stmt.setBigDecimal(i, v.bigDecimal)
    case v: java.math.BigDecimal => stmt.setBigDecimal(i, v)
    case v: BigInt             => stmt.setBigDecimal(i, new java.math.BigDecimal(v.bigInteger))
    case v: Int                => stmt.setInt(i, v)
    case v: Long               => stmt.setLong(i, v)
    case v: Double             => stmt.setDouble(i, v)
    case v: Boolean            => stmt.setInt(i, if v then 1 else 0)
    case v: String             => stmt.setString(i, v)
    case v                     => stmt.setObject(i, v)

  private def bind(stmt: PreparedStatement, params: Seq[Any]): Unit =
    params.zipWithIndex.foreach { case (value, idx) => bindOne(stmt, idx + 1, value) }

  /** Materialise every row of a query. */
  def rows(conn: Connection, sql: String, params: Seq[Any] = Nil): List[Row] =
    Using.resource(conn.prepareStatement(sql)) { stmt =>
      bind(stmt, params)
      Using.resource(stmt.executeQuery()) { rs => readAll(rs) }
    }

  def row(conn: Connection, sql: String, params: Seq[Any] = Nil): Option[Row] =
    rows(conn, sql, params).headOption

  def scalar(conn: Connection, sql: String, params: Seq[Any] = Nil): Option[Any] =
    row(conn, sql, params).map(_.values.head._2)

  /** `COUNT(*)`-style helper. */
  def count(conn: Connection, sql: String, params: Seq[Any] = Nil): Long =
    scalar(conn, sql, params) match
      case Some(v: Number) => v.longValue
      case Some(v)         => v.toString.toLong
      case None            => 0L

  /** INSERT/UPDATE/DELETE; returns the affected row count. */
  def execute(conn: Connection, sql: String, params: Seq[Any] = Nil): Int =
    Using.resource(conn.prepareStatement(sql)) { stmt =>
      bind(stmt, params)
      stmt.executeUpdate()
    }

  /** INSERT and return the generated primary key (0 when the driver gives none). */
  def insertReturningKey(conn: Connection, sql: String, params: Seq[Any] = Nil): Long =
    Using.resource(conn.prepareStatement(sql, java.sql.Statement.RETURN_GENERATED_KEYS)) { stmt =>
      bind(stmt, params)
      stmt.executeUpdate()
      Using.resource(stmt.getGeneratedKeys) { keys =>
        if keys.next() then keys.getLong(1) else 0L
      }
    }

  private def readAll(rs: ResultSet): List[Row] =
    val meta: ResultSetMetaData = rs.getMetaData
    val width = meta.getColumnCount
    val labels = (1 to width).map(i => meta.getColumnLabel(i).toLowerCase).toList
    val out = ArrayBuffer.empty[Row]
    while rs.next() do
      val values = scala.collection.mutable.Map.empty[String, Any]
      labels.zipWithIndex.foreach { case (label, i) => values(label) = rs.getObject(i + 1) }
      out += Row(labels.zipWithIndex.toMap, values.toMap)
    out.toList

/** Dialect-specific SQL fragments. Only whitelist-derived text enters SQL here. */
object Sql:
  /** Row lock for the current transaction (MySQL only; SQLite locks the write txn). */
  def forUpdate(dialect: Dialect): String = dialect match
    case Dialect.MySQL  => " FOR UPDATE"
    case Dialect.SQLite => ""

  /** Insert that silently skips rows whose primary key already exists. */
  def insertIgnore(dialect: Dialect): String = dialect match
    case Dialect.MySQL  => "INSERT IGNORE INTO "
    case Dialect.SQLite => "INSERT OR IGNORE INTO "

  /**
   * Upsert an `amount = amount + ?` style column on a unique-key collision.
   * `conflict` is the unique key list, `assignments` the column updates applied on collision.
   */
  def upsert(dialect: Dialect, table: String, columns: Seq[String], values: Seq[String], conflict: Seq[String], assignments: Seq[(String, String)]): String =
    dialect match
      case Dialect.SQLite =>
        s"""INSERT INTO $table (${columns.mkString(", ")}) VALUES (${values.mkString(", ")})
           |ON CONFLICT (${conflict.mkString(", ")}) DO UPDATE SET ${assignments.map { case (c, e) => s"$c = $e" }.mkString(", ")}""".stripMargin
      case Dialect.MySQL =>
        s"""INSERT INTO $table (${columns.mkString(", ")}) VALUES (${values.mkString(", ")})
           |ON DUPLICATE KEY UPDATE ${assignments.map { case (c, e) => s"$c = $e" }.mkString(", ")}""".stripMargin

  /** Placeholder list for `n` values. */
  def marks(n: Int): String = List.fill(n)("?").mkString(", ")

  /** Random ordering function of the active dialect (legacy `order_type=rand`). */
  def random(dialect: Dialect): String = dialect match
    case Dialect.MySQL  => "RAND()"
    case Dialect.SQLite => "RANDOM()"
