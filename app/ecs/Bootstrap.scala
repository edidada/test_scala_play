package ecs

import com.google.inject.AbstractModule
import javax.inject.{Inject, Singleton}
import play.api.{Configuration, Environment, Logger}

import java.io.File
import java.sql.Connection

/** Eager singleton that creates the SQLite file, schema and demo rows at startup. */
@Singleton
class Bootstrap @Inject() (config: Configuration, env: Environment, db: Db):
  private val log = Logger(this.getClass)

  private def resource(path: String): Option[String] =
    env.resourceAsStream(path).map { stream =>
      try new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8) finally stream.close()
    }

  /** Split a script into statements, honouring `--` comments and quoting. */
  private def statements(script: String): List[String] =
    val out = scala.collection.mutable.ListBuffer.empty[String]
    val current = new StringBuilder
    var inSingle = false
    var inDouble = false
    var line = 0
    val text = script.replace("\r\n", "\n")
    while line < text.length do
      val c = text.charAt(line)
      if !inSingle && !inDouble && c == '-' && line + 1 < text.length && text.charAt(line + 1) == '-' then
        while line < text.length && text.charAt(line) != '\n' do line += 1
      else if c == '\'' && !inDouble then { inSingle = !inSingle; current += c }
      else if c == '"' && !inSingle then { inDouble = !inDouble; current += c }
      else if c == ';' && !inSingle && !inDouble then
        val stmt = current.toString.trim
        if stmt.nonEmpty then out += stmt
        current.clear()
      else current += c
      line += 1
    val tail = current.toString.trim
    if tail.nonEmpty then out += tail
    out.toList

  private def run(conn: Connection, path: String): Int =
    val script = resource(path).getOrElse(throw new IllegalStateException(s"missing classpath resource $path"))
    statements(script).map { sql =>
      val statement = conn.createStatement()
      try
        statement.execute(sql)
        1
      catch
        case e: java.sql.SQLException =>
          log.warn(s"skipped statement in $path: ${e.getMessage}: ${sql.take(120).replace('\n', ' ')}")
          0
      finally statement.close()
    }.sum

  // Runs from the Guice eager-singleton path, before the first request serves traffic.
  locally {
    val ddl = if db.isSQLite then config.get[String]("ecshop.boot.sqlite.ddl") else config.get[String]("ecshop.boot.mysql.ddl")
    val seed = if db.isSQLite then config.get[String]("ecshop.boot.sqlite.seed") else config.get[String]("ecshop.boot.mysql.seed")
    val applied = db.inTransaction { conn => run(conn, ddl) + run(conn, seed) }
    log.info(s"ECShop schema bootstrap applied $applied statements for ${db.dialect}")
  }

/** Guice bindings for the application. */
class AppModule extends AbstractModule:
  override def configure(): Unit =
    // SQLite cannot create its database file when the parent directory is missing, and the
    // connection pool materialises before any singleton runs. Create ./data here, during
    // injector construction, so `jdbc:sqlite:data/ecshop.sqlite3` opens cleanly.
    if sys.env.get("DB_DRIVER").forall(_.contains("sqlite")) then
      val dir = new File("data")
      if !dir.exists() && !dir.mkdirs() then
        sys.error(s"could not create SQLite data directory ${dir.getAbsolutePath}")
    bind(classOf[Bootstrap]).asEagerSingleton()
