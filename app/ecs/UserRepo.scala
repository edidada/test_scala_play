package ecs

import play.api.libs.json.{JsObject, Json}
import play.api.mvc.Headers

import java.sql.Connection
import javax.inject.{Inject, Singleton}
import scala.util.{Failure, Success, Try}

/**
 * Accounts and sessions (PHP `user.php?act=act_register|act_login|logout`).
 *
 * Passwords are hashed with salted PBKDF2-HMAC-SHA256 and a bearer token is only
 * ever stored as its SHA-256 digest. Every method expects the caller's
 * transaction, so a uniqueness check and its insert can never be interleaved.
 */
@Singleton
class UserRepo @Inject() (db: Db):

  private val ProfileSelect =
    """SELECT user_id, user_name, email, mobile_phone, user_money, pay_points, rank_points,
      |  is_validated, created_at""".stripMargin

  private def project(row: Row): JsObject =
    Json.obj(
      "id"             -> row.long("user_id"),
      "username"       -> row.str("user_name"),
      "email"          -> row.str("email"),
      "mobile_phone"   -> row.str("mobile_phone"),
      "balance"        -> ApiJson.money(row.dec("user_money")),
      "points"         -> row.long("pay_points"),
      "rank_points"    -> row.long("rank_points"),
      "email_verified" -> row.bool("is_validated"),
      "created_at"     -> Views.timestamp(row, "created_at")
    )

  /** `GET /api/v1/me` — the wallet fields come from the row, not from the client. */
  def profile(conn: Connection, userId: Long): Option[JsObject] =
    db.row(conn, s"$ProfileSelect FROM users WHERE user_id = ?", Seq(userId)).map(project)

  def usernameTaken(conn: Connection, username: String): Boolean =
    db.row(conn, "SELECT user_id FROM users WHERE user_name = ?", Seq(username)).isDefined

  def emailTaken(conn: Connection, email: String): Boolean =
    db.row(conn, "SELECT user_id FROM users WHERE email = ?", Seq(email)).isDefined

  /** The raw bearer token, only so `logout` can revoke exactly that session. */
  def rawToken(headers: Headers): Option[String] = AuthedAction.bearerOf(headers)

  /** New session row for a fresh random token; the plaintext is returned once. */
  def createSession(conn: Connection, userId: Long): String =
    val token = Crypto.newToken()
    db.execute(conn,
      "INSERT INTO sessions (token_hash, user_id, created_at) VALUES (?, ?, ?)",
      Seq(Crypto.sha256Hex(token), userId, ApiJson.nowUtc))
    token

  def revokeSession(conn: Connection, token: String): Unit =
    db.execute(conn, "DELETE FROM sessions WHERE token_hash = ?", Seq(Crypto.sha256Hex(token)))

  def revokeAllSessions(conn: Connection, userId: Long): Unit =
    db.execute(conn, "DELETE FROM sessions WHERE user_id = ?", Seq(userId))

  /**
   * `POST /api/v1/auth/register` — user row plus its first session.
   *
   * The pre-checks only pick the friendlier field name. The unique keys on
   * `user_name` and `email` are the actual race guard, so a concurrent duplicate
   * is still refused instead of silently committed.
   */
  def register(conn: Connection, username: String, email: String, password: String): Either[FieldError, (Long, String)] =
    if usernameTaken(conn, username) then Left(FieldError("username", "username is already taken"))
    else if emailTaken(conn, email) then Left(FieldError("email", "email is already registered"))
    else
      val attempt = Try {
        val id = db.insertReturningKey(conn,
          """INSERT INTO users (user_name, email, password_hash, created_at, updated_at)
            |VALUES (?, ?, ?, ?, ?)""".stripMargin,
          Seq(username, email, Crypto.hashPassword(password), ApiJson.nowUtc, ApiJson.nowUtc))
        (id, createSession(conn, id))
      }
      attempt match
        case Success(value) => Right(value)
        case Failure(_) =>
          if usernameTaken(conn, username) then Left(FieldError("username", "username is already taken"))
          else Left(FieldError("email", "email is already registered"))

  /** `POST /api/v1/auth/login` — `identifier` may be the user name or the e-mail. */
  def login(conn: Connection, identifier: String, password: String): Option[(Long, String, String)] =
    db.row(conn,
      """SELECT user_id, user_name, password_hash FROM users
        |WHERE user_name = ? OR email = ?""".stripMargin, Seq(identifier, identifier))
      .filter(row => Crypto.verifyPassword(password, row.str("password_hash")))
      .map { row =>
        val userId = row.long("user_id")
        (userId, row.str("user_name"), createSession(conn, userId))
      }

  /** `PATCH /api/v1/me` — e-mail and/or mobile phone, at least one per call. */
  def updateProfile(conn: Connection, userId: Long, email: Option[String], mobile: Option[String]): Either[FieldError, JsObject] =
    if email.isEmpty && mobile.isEmpty then Left(FieldError("email", "email or mobile_phone is required"))
    else if email.exists(e => emailTaken(conn, e)) then Left(FieldError("email", "email is already registered"))
    else
      val sets = List(email.map(_ => "email = ?"), mobile.map(_ => "mobile_phone = ?")).flatten :+ "updated_at = ?"
      val params: Seq[Any] = List(email, mobile).flatten.map[Any](identity) :+ ApiJson.nowUtc :+ userId
      db.execute(conn, s"UPDATE users SET ${sets.mkString(", ")} WHERE user_id = ?", params)
      profile(conn, userId) match
        case Some(payload) => Right(payload)
        case None          => Left(FieldError("user_id", "the authenticated user no longer exists"))

  /**
   * `PATCH /api/v1/me/password` — verify the old password, replace the hash under a
   * `password_hash = <old>` condition, then revoke every session of that user.
   */
  def changePassword(conn: Connection, userId: Long, current: String, next: String): Either[PasswordFailure, Unit] =
    db.row(conn,
      s"SELECT password_hash FROM users WHERE user_id = ?${Sql.forUpdate(db.dialect)}", Seq(userId)) match
      case None => Left(PasswordFailure.NotFound)
      case Some(row) if !Crypto.verifyPassword(current, row.str("password_hash")) =>
        Left(PasswordFailure.WrongCurrent)
      case Some(row) =>
        val old = row.str("password_hash")
        val updated = db.execute(conn,
          """UPDATE users SET password_hash = ?, updated_at = ?
            |WHERE user_id = ? AND password_hash = ?""".stripMargin,
          Seq(Crypto.hashPassword(next), ApiJson.nowUtc, userId, old))
        if updated != 1 then Left(PasswordFailure.ConcurrentChange)
        else
          revokeAllSessions(conn, userId)
          Right(())

/** Why a password change was refused (docs/03_api_contract.md). */
enum PasswordFailure:
  case NotFound, WrongCurrent, ConcurrentChange
