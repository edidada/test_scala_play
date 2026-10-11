package ecs

import play.api.libs.json.JsObject

import java.sql.Connection
import javax.inject.{Inject, Singleton}

/**
 * Development mail transport: messages are queued in `email_outbox` and an
 * independent sender delivers them.
 *
 * Nothing written here ever reaches the HTTP response or a log line; payloads may
 * contain plaintext one-shot tokens, which is exactly why only the hash is kept in
 * the token tables (docs/03_api_contract.md).
 */
@Singleton
class MailRepo @Inject() (db: Db):

  def queue(conn: Connection, userId: Option[Long], recipient: String, template: String, payload: JsObject): Unit =
    db.execute(conn,
      """INSERT INTO email_outbox (user_id, recipient, template_name, payload, status, created_at)
        |VALUES (?, ?, ?, ?, 'pending', ?)""".stripMargin,
      Seq(userId, recipient, template, payload.toString, ApiJson.nowUtc))
