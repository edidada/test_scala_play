package ecs

import play.api.libs.json.Json

import java.sql.Connection
import java.time.Instant
import javax.inject.{Inject, Singleton}

/**
 * Newsletter subscription lifecycle (PHP `user.php?act=email_list` and its four jobs).
 *
 * `email_list` keeps one row per address: `status` is the subscription state, while
 * `token_hash` plus `pending_action` describe the confirmation that is currently
 * outstanding. Requests never fail on an unknown address, so the endpoints cannot be
 * used to enumerate which e-mails are on the list (docs/03_api_contract.md).
 */
@Singleton
class NewsletterRepo @Inject() (db: Db, mail: MailRepo):

  val TokenTtlSeconds = 24 * 3600

  private def expiry(seconds: Long): String =
    ApiJson.utcFormatter.format(Instant.now().plusSeconds(seconds))

  private def rowOf(conn: Connection, email: String): Option[Row] =
    db.row(conn, "SELECT email, status, pending_action FROM email_list WHERE email = ?", Seq(email))

  private def subscribed(row: Option[Row]): Boolean = row.exists(_.bool("status"))

  /** Remember the one-shot token for an address, creating the row when needed. */
  private def queue(conn: Connection, email: String, action: String, baseStatus: Int): Boolean =
    val token = Crypto.newEmailToken()
    val hash = Crypto.sha256Hex(token)
    if rowOf(conn, email).isEmpty then
      db.execute(conn,
        """INSERT INTO email_list (email, status, token_hash, pending_action, token_expires_at, updated_at)
          |VALUES (?, ?, ?, ?, ?, ?)""".stripMargin,
        Seq(email, baseStatus, hash, action, expiry(TokenTtlSeconds), ApiJson.nowUtc))
    else
      db.execute(conn,
        """UPDATE email_list SET token_hash = ?, pending_action = ?, token_expires_at = ?, updated_at = ?
          |WHERE email = ?""".stripMargin,
        Seq(hash, action, expiry(TokenTtlSeconds), ApiJson.nowUtc, email))
    mail.queue(conn, None, email, s"newsletter_$action", Json.obj("token" -> token, "email" -> email))
    true

  /** `POST /api/v1/newsletter-subscriptions` — false means "nothing to do". */
  def requestSubscribe(conn: Connection, email: String): Boolean =
    if subscribed(rowOf(conn, email)) then false else queue(conn, email, "subscribe", 0)

  /** `DELETE /api/v1/newsletter-subscriptions` — only a live subscription can be cancelled. */
  def requestUnsubscribe(conn: Connection, email: String): Boolean =
    if !subscribed(rowOf(conn, email)) then false else queue(conn, email, "unsubscribe", 1)

  /** `POST /api/v1/newsletter-subscriptions/confirm` — one atomic activation. */
  def confirmSubscribe(conn: Connection, token: String): Boolean =
    db.execute(conn,
      """UPDATE email_list SET status = 1, token_hash = NULL, pending_action = '',
        |  token_expires_at = NULL, updated_at = ?
        |WHERE token_hash = ? AND pending_action = 'subscribe' AND status = 0 AND token_expires_at > ?""".stripMargin,
      Seq(ApiJson.nowUtc, Crypto.sha256Hex(token), ApiJson.nowUtc)) == 1

  /** `POST /api/v1/newsletter-unsubscriptions/confirm` — the row goes with the token. */
  def confirmUnsubscribe(conn: Connection, token: String): Boolean =
    db.execute(conn,
      """DELETE FROM email_list
        |WHERE token_hash = ? AND pending_action = 'unsubscribe' AND status = 1 AND token_expires_at > ?""".stripMargin,
      Seq(Crypto.sha256Hex(token), ApiJson.nowUtc)) == 1
