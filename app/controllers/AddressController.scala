package controllers

import ecs.{AddressFailure, AddressInput, AddressPatch, AddressRepo, ApiError, AuthedAction, Body, Db, FieldError}
import javax.inject.Inject
import play.api.libs.json.{JsObject, JsValue}
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents, RequestHeader, Result}

/**
 * Shipping addresses — maps PHP user.php?act=address_list|act_edit_address|drop_consignee.
 *
 * The whole address body is parsed here; `ecs.AddressRepo` decides what the
 * database does with it.
 */
class AddressController @Inject() (cc: ControllerComponents, db: Db, addresses: AddressRepo, authed: AuthedAction)
  extends AbstractController(cc) with ApiSupport:

  /** `GET /api/v1/me/addresses` */
  def index: Action[AnyContent] = authed { req =>
    Ok(db.readOnly(conn => addresses.list(conn, req.user.id)))
  }

  /** `POST /api/v1/me/addresses` — the region chain is validated inside the transaction. */
  def create: Action[JsValue] = authed(parse.json) { req =>
    orFail(AddressController.fullBody(Body(req.body)), req) { input =>
      outcome(db.inTransaction(conn => addresses.create(conn, req.user.id, input)), req, created = true)
    }
  }

  /** `PATCH /api/v1/me/addresses/{id}` — at least one field, merged with the stored row. */
  def update(id: Long): Action[JsValue] = authed(parse.json) { req =>
    orFail(AddressController.patchBody(Body(req.body)), req) { patch =>
      outcome(db.inTransaction(conn => addresses.update(conn, req.user.id, id, patch)), req, created = false)
    }
  }

  /** `DELETE /api/v1/me/addresses/{id}` — id plus owner, 404 for anything else. */
  def destroy(id: Long): Action[AnyContent] = authed { req =>
    if db.inTransaction(conn => addresses.delete(conn, req.user.id, id)) then NoContent
    else ApiError.notFound("address not found", req)
  }

  private def outcome(result: Either[AddressFailure, JsObject], req: RequestHeader, created: Boolean): Result =
    result match
      case Right(payload) => if created then Created(payload) else Ok(payload)
      case Left(AddressFailure.InvalidRegion(field, message)) => fail(FieldError(field, message), req)
      case Left(AddressFailure.NotFound) => ApiError.notFound("address not found", req)

object AddressController:
  private val Fields = Set("consignee", "country_id", "province_id", "city_id", "district_id",
    "address", "zip", "mobile", "is_default")

  /** `POST` needs every core field; region ids are checked by the repository. */
  def fullBody(body: Body): Either[FieldError, AddressInput] =
    for
      consignee <- body.str("consignee", maxBytes = 60)
      country   <- body.long("country_id", min = 1)
      province  <- body.long("province_id", min = 1)
      city      <- body.long("city_id", min = 1)
      district  <- body.long("district_id", min = 0)
      address   <- body.str("address", maxBytes = 255)
      zip       <- MessageController.text(body.optStr("zip", 20), "")
      mobile    <- MessageController.text(body.optStr("mobile", 32), "")
      isDefault <- body.bool("is_default")
      _         <- body.rejectUnknown(Fields).toLeft(())
    yield AddressInput(consignee, country, province, city, district, address, zip, mobile, isDefault)

  /** `PATCH` accepts any subset, but an empty object is a 400. */
  def patchBody(body: Body): Either[FieldError, AddressPatch] =
    for
      _         <- if body.keys.isEmpty then Left(FieldError("body", "no field to update")) else Right(())
      consignee <- body.strOpt("consignee", 60)
      country   <- body.optLong("country_id", min = 1)
      province  <- body.optLong("province_id", min = 1)
      city      <- body.optLong("city_id", min = 1)
      district  <- body.optLong("district_id", min = 0)
      address   <- body.strOpt("address", 255)
      zip       <- body.strOpt("zip", 20)
      mobile    <- body.strOpt("mobile", 32)
      isDefault <- body.optBool("is_default")
      _         <- body.rejectUnknown(Fields).toLeft(())
    yield AddressPatch(consignee, country, province, city, district, address, zip, mobile, isDefault)
