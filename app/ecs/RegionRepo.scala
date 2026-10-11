package ecs

import play.api.libs.json.{JsObject, Json}

import java.sql.Connection
import javax.inject.{Inject, Singleton}

/**
 * Region and shipping availability reads (PHP `region.php`, `myship.php`).
 *
 * Region ids come from the four-level `region` table, so address and checkout
 * validation always asks the database instead of trusting a client-side list.
 */
@Singleton
class RegionRepo @Inject() (db: Db):

  private def project(row: Row): JsObject =
    Json.obj(
      "id"        -> row.long("region_id"),
      "parent_id" -> row.long("parent_id"),
      "name"      -> row.str("region_name"),
      "type"      -> row.long("region_type")
    )

  /**
   * Children of `parent`, or the whole table when no parent is given.
   * `GET /api/v1/regions?parent=2`.
   */
  def list(conn: Connection, parent: Option[Long]): JsObject =
    val (sql, params) = parent match
      case Some(id) => ("SELECT region_id, parent_id, region_name, region_type FROM region WHERE parent_id = ? ORDER BY region_id", Seq[Any](id))
      case None     => ("SELECT region_id, parent_id, region_name, region_type FROM region ORDER BY parent_id, region_id", Nil)
    Json.obj("items" -> Json.toJson(db.rows(conn, sql, params).map(project)))

  /** Region exists and, when `parentId` is given, hangs under that parent. */
  def regionUnder(conn: Connection, regionId: Long, parentId: Long): Boolean =
    db.row(conn, "SELECT region_id FROM region WHERE region_id = ? AND parent_id = ?", Seq(regionId, parentId)).isDefined

  /** Enabled shipping methods (PHP `myship.php` renders the same list). */
  def shippingOptions(conn: Connection): List[JsObject] =
    db.rows(conn,
      """SELECT shipping_id, shipping_name, shipping_code, shipping_fee
        |FROM shipping WHERE enabled = 1
        |ORDER BY shipping_id""".stripMargin)
      .map(r => Json.obj(
        "id"   -> r.long("shipping_id"),
        "name" -> r.str("shipping_name"),
        "code" -> r.str("shipping_code"),
        "fee"  -> ApiJson.money(r.dec("shipping_fee"))
      ))
