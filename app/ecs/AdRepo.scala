package ecs

import play.api.libs.json.{JsObject, Json}

import java.sql.Connection
import javax.inject.{Inject, Singleton}

/**
 * Advertising reads and the click recorder (PHP `affiche.php`,
 * `cycle_image.php`).
 *
 * The legacy `affiche.php?act=js` response was a string-concatenated script;
 * this API returns data only and lets the front end render it per `media_type`.
 */
@Singleton
class AdRepo @Inject() (db: Db):

  private val Columns: String =
    "ad_id, position_id, media_type, ad_name, ad_link, ad_code, start_time, end_time, click_count"

  private val Active: String = "enabled = 1 AND start_time <= ? AND end_time >= ?"

  private def project(row: Row): JsObject =
    Json.obj(
      "id"          -> row.long("ad_id"),
      "position_id" -> row.long("position_id"),
      "media_type"  -> row.long("media_type"),
      "name"        -> row.str("ad_name"),
      "ad_link"     -> row.str("ad_link"),
      "ad_code"     -> row.str("ad_code"),
      "start_time"  -> Views.timestamp(row, "start_time"),
      "end_time"    -> Views.timestamp(row, "end_time"),
      "click_count" -> row.long("click_count")
    )

  /** XML attribute escaping for the flash text file. */
  private def attr(text: String): String =
    text.flatMap {
      case '<'  => "&lt;"
      case '>'  => "&gt;"
      case '&'  => "&amp;"
      case '"'  => "&quot;"
      case c    => c.toString
    }

  /**
   * `GET /cycle-image.xml` (PHP `cycle_image.php`): the currently running image
   * ads (`media_type = 0`) in the bcaster format the flash player expects.
   */
  def cycleImage(conn: Connection): String =
    val now = ApiJson.nowUtc
    val items = db.rows(conn,
      s"""SELECT ad_id, ad_name, ad_link, ad_code
         |FROM ad
         |WHERE media_type = 0 AND $Active
         |ORDER BY position_id, ad_id""".stripMargin, Seq(now, now))
      .map(r => s"""<item item_url="${attr(r.str("ad_code"))}" adlink="${attr(r.str("ad_link"))}"/>""")

    s"""<?xml version="1.0" encoding="UTF-8"?>
       |<bcaster>
       |${items.mkString("\n")}
       |</bcaster>
       |""".stripMargin

  /** `GET /api/v1/ads/{id}`: one ad that is enabled and inside its window. */
  def detail(conn: Connection, adId: Long): Option[JsObject] =
    val now = ApiJson.nowUtc
    db.row(conn, s"SELECT $Columns FROM ad WHERE ad_id = ? AND $Active", Seq(adId, now, now)).map(project)

  /**
   * `POST /api/v1/ads/{id}/click`: counts the click and the referer in one
   * transaction and hands back the redirect target.
   */
  def click(conn: Connection, adId: Long, referer: String): Option[String] =
    val now = ApiJson.nowUtc
    db.row(conn, s"SELECT ad_link FROM ad WHERE ad_id = ? AND $Active", Seq(adId, now, now)).map { row =>
      db.execute(conn, "UPDATE ad SET click_count = click_count + 1 WHERE ad_id = ?", Seq(adId))
      db.execute(conn,
        Sql.upsert(db.dialect, "adsense", Seq("from_ad", "referer", "clicks"), Seq("?", "?", "1"),
          Seq("from_ad", "referer"), Seq("clicks" -> "clicks + 1")),
        Seq(adId, referer))
      row.str("ad_link")
    }
