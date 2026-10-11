package ecs

import play.api.libs.json.Json

import java.sql.Connection
import javax.inject.{Inject, Singleton}

/**
 * One-shot mail tokens for e-mail verification and password resets
 * (PHP `user.php?act=send_hash_mail|validate_email|get_password|reset_password`).
 *
 * A plaintext token exists in exactly two places: the outbox payload, which a
 * separate mail sender consumes, and the HTTP response the client already has.
 * Databases only ever store the SHA-256 digest, and every redemption is a
 * conditional update so a double submit cannot be replayed.
 */
@Singleton
class VerificationRepo @Inject() (db: Db, mail: MailRepo):

  val VerificationTtlSeconds = 24 * 3600
  val ResetTtlSeconds = 3600

  private def expiry(seconds: Long): String =
    ApiJson.utcFormatter.format(java.time.Instant.now().plusSeconds(seconds))

  /** `POST /api/v1/me/email-verifications` — an old pending token is replaced. */
  def queueEmailVerification(conn: Connection, userId: Long, email: String): Unit =
    val token = Crypto.newEmailToken()
    db.execute(conn, "DELETE FROM email_verification_tokens WHERE user_id = ?", Seq(userId))
    db.execute(conn,
      """INSERT INTO email_verification_tokens (user_id, token_hash, expires_at, created_at)
        |VALUES (?, ?, ?, ?)""".stripMargin,
      Seq(userId, Crypto.sha256Hex(token), expiry(VerificationTtlSeconds), ApiJson.nowUtc))
    mail.queue(conn, Some(userId), email, "verify_email",
      Json.obj("token" -> token, "user_id" -> userId))

  /**
   * `POST /api/v1/email-verifications/confirm`.
   *
   * Returns false for an unknown, expired or already consumed token, which the
   * endpoint reports as 409 without revealing which case it was.
   */
  def confirmEmail(conn: Connection, token: String): Boolean =
    val hash = Crypto.sha256Hex(token)
    db.row(conn,
      s"""SELECT user_id, expires_at, consumed_at FROM email_verification_tokens
         |WHERE token_hash = ?${Sql.forUpdate(db.dialect)}""".stripMargin, Seq(hash)) match
      case None => false
      case Some(row) if row.optStr("consumed_at").isDefined || row.str("expires_at") < ApiJson.nowUtc => false
      case Some(row) =>
        val userId = row.long("user_id")
        db.execute(conn,
          Sql.insertIgnore(db.dialect) + "email_verified_users (user_id, verified_at) VALUES (?, ?)",
          Seq(userId, ApiJson.nowUtc))
        db.execute(conn, "UPDATE users SET is_validated = 1 WHERE user_id = ?", Seq(userId))
        db.execute(conn,
          "UPDATE email_verification_tokens SET consumed_at = ? WHERE token_hash = ? AND consumed_at IS NULL",
          Seq(ApiJson.nowUtc, hash)) == 1

  /** `POST /api/v1/password-resets` — true when the address belongs to an account. */
  def queuePasswordReset(conn: Connection, email: String): Boolean =
    db.row(conn, "SELECT user_id, email FROM users WHERE email = ?", Seq(email)) match
      case None => false
      case Some(row) =>
        val userId = row.long("user_id")
        val token = Crypto.newEmailToken()
        db.execute(conn, "DELETE FROM password_reset_tokens WHERE user_id = ?", Seq(userId))
        db.execute(conn,
          """INSERT INTO password_reset_tokens (user_id, token_hash, expires_at, created_at)
            |VALUES (?, ?, ?, ?)""".stripMargin,
          Seq(userId, Crypto.sha256Hex(token), expiry(ResetTtlSeconds), ApiJson.nowUtc))
        mail.queue(conn, Some(userId), row.str("email"), "password_reset",
          Json.obj("token" -> token, "user_id" -> userId))
        true

  /**
   * `POST /api/v1/password-resets/confirm` — rotate the password, revoke every
   * session and consume the token inside the caller's transaction.
   */
  def confirmPasswordReset(conn: Connection, token: String, newPassword: String): Boolean =
    val hash = Crypto.sha256Hex(token)
    db.row(conn,
      s"""SELECT user_id, expires_at, consumed_at FROM password_reset_tokens
         |WHERE token_hash = ?${Sql.forUpdate(db.dialect)}""".stripMargin, Seq(hash)) match
      case None => false
      case Some(row) if row.optStr("consumed_at").isDefined || row.str("expires_at") < ApiJson.nowUtc => false
      case Some(row) =>
        val userId = row.long("user_id")
        db.execute(conn,
          "UPDATE users SET password_hash = ?, updated_at = ? WHERE user_id = ?",
          Seq(Crypto.hashPassword(newPassword), ApiJson.nowUtc, userId))
        db.execute(conn, "DELETE FROM sessions WHERE user_id = ?", Seq(userId))
        db.execute(conn,
          "UPDATE password_reset_tokens SET consumed_at = ? WHERE token_hash = ? AND consumed_at IS NULL",
          Seq(ApiJson.nowUtc, hash)) == 1
