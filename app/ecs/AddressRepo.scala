package ecs

import play.api.libs.json.{JsObject, Json}

import java.sql.Connection
import javax.inject.{Inject, Singleton}

/**
 * Shipping addresses (PHP `user.php?act=address_list|act_edit_address|drop_consignee`).
 *
 * Region ids are validated against the four-level `region` table on every write, so
 * a client cannot store a city that belongs to another province. Reads, updates and
 * deletes all carry the authenticated user id in the SQL condition.
 */
@Singleton
class AddressRepo @Inject() (db: Db, regions: RegionRepo):

  private val Columns =
    """address_id, user_id, consignee, country_id, province_id, city_id, district_id,
      |address, zipcode, mobile, is_default, created_at""".stripMargin

  private def project(row: Row): JsObject =
    Json.obj(
      "id"           -> row.long("address_id"),
      "consignee"    -> row.str("consignee"),
      "country_id"   -> row.long("country_id"),
      "province_id"  -> row.long("province_id"),
      "city_id"      -> row.long("city_id"),
      "district_id"  -> row.long("district_id"),
      "address"      -> row.str("address"),
      "zip"          -> row.str("zipcode"),
      "mobile"       -> row.str("mobile"),
      "is_default"   -> row.bool("is_default"),
      "created_at"   -> Views.timestamp(row, "created_at")
    )

  private def byId(conn: Connection, userId: Long, addressId: Long, lock: Boolean): Option[Row] =
    db.row(conn,
      s"""SELECT $Columns FROM user_address WHERE address_id = ? AND user_id = ?${Sql.forUpdate(db.dialect)}"""
        .stripMargin, Seq(addressId, userId))

  /** `GET /api/v1/me/addresses` — the owner's addresses, default first. */
  def list(conn: Connection, userId: Long): JsObject =
    val rows = db.rows(conn,
      s"""SELECT $Columns FROM user_address WHERE user_id = ?
         |ORDER BY is_default DESC, address_id DESC""".stripMargin, Seq(userId))
    Json.obj("items" -> Json.toJson(rows.map(project)))

  /** `POST /api/v1/me/addresses` after the body has been parsed. */
  def create(conn: Connection, userId: Long, input: AddressInput): Either[AddressFailure, JsObject] =
    regions.regionChainError(conn, input.countryId, input.provinceId, input.cityId, input.districtId) match
      case Some(fe) => Left(AddressFailure.InvalidRegion(fe.field, fe.message))
      case None =>
        if input.isDefault then
          db.execute(conn, "UPDATE user_address SET is_default = 0 WHERE user_id = ? AND is_default = 1", Seq(userId))
        val id = db.insertReturningKey(conn,
          """INSERT INTO user_address (user_id, consignee, country_id, province_id, city_id, district_id,
            |  address, zipcode, mobile, is_default, created_at)
            |VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""".stripMargin,
          Seq(userId, input.consignee, input.countryId, input.provinceId, input.cityId, input.districtId,
            input.address, input.zip, input.mobile, if input.isDefault then 1 else 0, ApiJson.nowUtc))
        byId(conn, userId, id, lock = false) match
          case Some(row) => Right(project(row))
          case None      => Left(AddressFailure.NotFound)

  /**
   * `PATCH /api/v1/me/addresses/{id}` — the row is locked, the missing fields fall
   * back to the stored values, and the merged region chain is revalidated.
   */
  def update(conn: Connection, userId: Long, addressId: Long,
    patch: AddressPatch): Either[AddressFailure, JsObject] =

    byId(conn, userId, addressId, lock = true) match
      case None => Left(AddressFailure.NotFound)
      case Some(current) =>
        val merged = AddressInput(
          patch.consignee.getOrElse(current.str("consignee")),
          patch.countryId.getOrElse(current.long("country_id")),
          patch.provinceId.getOrElse(current.long("province_id")),
          patch.cityId.getOrElse(current.long("city_id")),
          patch.districtId.getOrElse(current.long("district_id")),
          patch.address.getOrElse(current.str("address")),
          patch.zip.getOrElse(current.str("zipcode")),
          patch.mobile.getOrElse(current.str("mobile")),
          patch.isDefault.getOrElse(current.bool("is_default"))
        )
        val regionError =
          regions.regionChainError(conn, merged.countryId, merged.provinceId, merged.cityId, merged.districtId)
        regionError match
          case Some(fe) => Left(AddressFailure.InvalidRegion(fe.field, fe.message))
          case None =>
            if merged.isDefault then
              db.execute(conn,
                """UPDATE user_address SET is_default = 0
                  |WHERE user_id = ? AND is_default = 1 AND address_id <> ?""".stripMargin,
                Seq(userId, addressId))
            db.execute(conn,
              """UPDATE user_address SET consignee = ?, country_id = ?, province_id = ?, city_id = ?,
                |  district_id = ?, address = ?, zipcode = ?, mobile = ?, is_default = ?
                |WHERE address_id = ? AND user_id = ?""".stripMargin,
              Seq(merged.consignee, merged.countryId, merged.provinceId, merged.cityId, merged.districtId,
                merged.address, merged.zip, merged.mobile, if merged.isDefault then 1 else 0, addressId, userId))
            byId(conn, userId, addressId, lock = false) match
              case Some(row) => Right(project(row))
              case None      => Left(AddressFailure.NotFound)

  /** `DELETE /api/v1/me/addresses/{id}` — id and owner both have to match. */
  def delete(conn: Connection, userId: Long, addressId: Long): Boolean =
    db.execute(conn, "DELETE FROM user_address WHERE address_id = ? AND user_id = ?", Seq(addressId, userId)) == 1

/** A fully validated address body, used by both create and the merged update. */
final case class AddressInput(consignee: String, countryId: Long, provinceId: Long, cityId: Long,
  districtId: Long, address: String, zip: String, mobile: String, isDefault: Boolean)

/** Every field of `PATCH /api/v1/me/addresses/{id}` is optional. */
final case class AddressPatch(consignee: Option[String], countryId: Option[Long], provinceId: Option[Long],
  cityId: Option[Long], districtId: Option[Long], address: Option[String], zip: Option[String],
  mobile: Option[String], isDefault: Option[Boolean])

/** Why an address write was refused; each case maps to one HTTP status. */
enum AddressFailure:
  case InvalidRegion(field: String, message: String)
  case NotFound
