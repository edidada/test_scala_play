package ecs

import play.api.libs.json.{JsObject, Json}

import java.sql.Connection
import javax.inject.{Inject, Singleton}

/**
 * Points conversion (PHP `user.php?act=transform_points|act_transform_points`).
 *
 * The legacy store kept an exchange rate in `shop_config`; here the catalogue lives in
 * `points_conversion_option` so several bundles can be offered at once. A conversion
 * is one guarded decrement of `users.pay_points` plus one credited balance: the
 * `WHERE pay_points >= ?` clause means two concurrent conversions can never spend the
 * same points twice, and a zero-row update is reported as 409 instead of leaving an
 * accounting credit behind.
 */
@Singleton
class PointsRepo @Inject() (db: Db):

  private def optionRow(row: Row, availablePoints: Long): JsObject =
    val need = row.long("need_points")
    Json.obj(
      "id"                -> row.long("option_id"),
      "name"              -> row.str("option_name"),
      "need_points"       -> need,
      "money"             -> ApiJson.money(row.dec("money")),
      "affordable"        -> (need <= availablePoints)
    )

  /** `GET /api/v1/me/points/conversion-options`. */
  def options(conn: Connection, userId: Long): JsObject =
    val available = db.row(conn, "SELECT pay_points FROM users WHERE user_id = ?", Seq(userId))
      .map(_.long("pay_points")).getOrElse(0L)
    val rows = db.rows(conn,
      """SELECT option_id, option_name, need_points, money
        |FROM points_conversion_option
        |WHERE enabled = 1
        |ORDER BY need_points, option_id""".stripMargin, Seq.empty)
    Json.obj(
      "available_points" -> available,
      "options"          -> Json.toJson(rows.map(optionRow(_, available)))
    )

  /**
   * `POST /api/v1/me/points/conversions` — spend `quantity` copies of one option and
   * credit the matching amount to the account balance.
   *
   * The points are taken first and the credit only happens when exactly one row was
   * updated, so a failed guard aborts the whole transaction through [[Abort]].
   */
  def convert(conn: Connection, userId: Long, optionId: Long,
    quantity: Long): Either[PointsFailure, JsObject] =
    db.row(conn,
      """SELECT option_id, option_name, need_points, money
        |FROM points_conversion_option WHERE option_id = ? AND enabled = 1""".stripMargin, Seq(optionId)) match
      case None => Left(PointsFailure.OptionUnavailable)
      case Some(option) =>
        val spend = option.long("need_points") * quantity
        val credit = CartRepo.toCents(option.dec("money")) * quantity
        val before = db.row(conn, "SELECT pay_points FROM users WHERE user_id = ?", Seq(userId))
          .map(_.long("pay_points")).getOrElse(0L)
        if db.execute(conn,
          """UPDATE users SET pay_points = pay_points - ?, updated_at = ?
            |WHERE user_id = ? AND pay_points >= ?""".stripMargin,
          Seq(spend, ApiJson.nowUtc, userId, spend)) != 1 then Left(PointsFailure.InsufficientPoints)
        else
          db.execute(conn,
            Sql.insertIgnore(db.dialect) + "account_balance (user_id, available_cents, frozen_cents) VALUES (?, 0, 0)",
            Seq(userId))
          if db.execute(conn,
            """UPDATE account_balance SET available_cents = available_cents + ? WHERE user_id = ?""".stripMargin,
              Seq(credit, userId)) != 1 then throw Abort(PointsFailure.OptionUnavailable)
          db.execute(conn,
            """INSERT INTO account_log (user_id, available_delta_cents, frozen_delta_cents, reason,
              |  reference_type, reference_id, created_at)
              |VALUES (?, ?, 0, ?, 'points_conversion', ?, ?)""".stripMargin,
            Seq(userId, credit, s"converted $spend points via option $optionId", optionId, ApiJson.nowUtc))
          Right(Json.obj(
            "option_id"        -> optionId,
            "option_name"      -> option.str("option_name"),
            "quantity"         -> quantity,
            "points_spent"     -> spend,
            "amount"           -> ApiJson.money(credit),
            "available_points" -> (before - spend),
            "converted_at"     -> ApiJson.nowUtc
          ))
/** Why a points conversion was refused. */
enum PointsFailure:
  case OptionUnavailable, InsufficientPoints
