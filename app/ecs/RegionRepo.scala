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

  private def exists(conn: Connection, regionId: Long): Boolean =
    db.row(conn, "SELECT region_id FROM region WHERE region_id = ?", Seq(regionId)).isDefined

  /**
   * Four-level chain check for address and order writes: the country must exist and
   * every deeper level must be a direct child of the level above it. A district id of
   * `0` means "no district", which legacy data allows.
   */
  def regionChainError(conn: Connection, countryId: Long, provinceId: Long, cityId: Long,
    districtId: Long): Option[FieldError] =
    if !exists(conn, countryId) then Some(FieldError("country_id", s"country_id $countryId does not exist"))
    else if !regionUnder(conn, provinceId, countryId) then
      Some(FieldError("province_id", s"province_id $provinceId is not a province of country_id $countryId"))
    else if !regionUnder(conn, cityId, provinceId) then
      Some(FieldError("city_id", s"city_id $cityId is not a city of province_id $provinceId"))
    else if districtId != 0 && !regionUnder(conn, districtId, cityId) then
      Some(FieldError("district_id", s"district_id $districtId is not a district of city_id $cityId"))
    else None

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

  private def childrenRows(conn: Connection, parentId: Long): List[Row] =
    db.rows(conn,
      "SELECT region_id, parent_id, region_name, region_type FROM region WHERE parent_id = ? ORDER BY region_id",
      Seq(parentId))

  /**
   * `GET /api/v1/shipping-options` (PHP `myship.php`): the current selection, the
   * candidates of every level and the enabled carriers.
   *
   * Each level is checked against the children of the level above it, so a client
   * cannot pair a city with a province it does not belong to.
   */
  def shippingSelection(conn: Connection, country: Option[Long], province: Option[Long],
    city: Option[Long], district: Option[Long]): Either[FieldError, JsObject] =

    val countries = childrenRows(conn, 0L)
    val chosenCountry = country.orElse(countries.map(_.long("region_id")).headOption)
    val provinces = chosenCountry.map(childrenRows(conn, _)).getOrElse(Nil)
    val cities = province.map(childrenRows(conn, _)).getOrElse(Nil)
    val districts = city.map(childrenRows(conn, _)).getOrElse(Nil)

    val invalid =
      country.filterNot(c => countries.exists(_.long("region_id") == c))
        .map(c => FieldError("country_id", s"country_id $c is not a country"))
        .orElse(province.filterNot(p => provinces.exists(_.long("region_id") == p))
          .map(p => FieldError("province_id", s"province_id $p is not a province of country_id")))
        .orElse(city.filterNot(c => cities.exists(_.long("region_id") == c))
          .map(c => FieldError("city_id", s"city_id $c is not a city of province_id")))
        .orElse(district.filterNot(d => districts.exists(_.long("region_id") == d))
          .map(d => FieldError("district_id", s"district_id $d is not a district of city_id")))

    invalid.toLeft(
      Json.obj(
        "selected" -> Json.obj(
          "country_id"  -> chosenCountry.getOrElse(0L),
          "province_id" -> province.getOrElse(0L),
          "city_id"     -> city.getOrElse(0L),
          "district_id" -> district.getOrElse(0L)
        ),
        "countries"        -> Json.toJson(countries.map(project)),
        "provinces"        -> Json.toJson(provinces.map(project)),
        "cities"           -> Json.toJson(cities.map(project)),
        "districts"        -> Json.toJson(districts.map(project)),
        "shipping_options" -> Json.toJson(shippingOptions(conn))
      )
    )
