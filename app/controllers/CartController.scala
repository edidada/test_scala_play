package controllers

import ecs.{ApiError, AuthedAction, Body, CartFailure, CartRepo, Db, ErrorCode}
import javax.inject.Inject
import play.api.libs.json.{JsObject, JsValue}
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents, RequestHeader, Result}

/**
 * Cart — maps PHP flow.php?step=cart|add_to_cart|update_cart|drop_goods.
 *
 * The user id always comes from the bearer token, never from the body.
 */
class CartController @Inject() (cc: ControllerComponents, db: Db, cart: CartRepo, authed: AuthedAction)
  extends AbstractController(cc) with ApiSupport:

  /** `GET /api/v1/me/cart` */
  def index: Action[AnyContent] = authed { req =>
    Ok(db.readOnly(conn => cart.list(conn, req.user.id)))
  }

  /** `POST /api/v1/me/cart` — repeated adds accumulate into the existing line. */
  def create: Action[JsValue] = authed(parse.json) { req =>
    val body = Body(req.body)
    val parsed =
      for
        goodsId  <- body.long("goods_id", min = 1)
        quantity <- body.long("quantity", min = 1, max = 999999)
        _        <- body.rejectUnknown(Set("goods_id", "quantity")).toLeft(())
      yield (goodsId, quantity)

    orFail(parsed, req) { case (goodsId, quantity) =>
      outcome(db.inTransaction(conn => cart.add(conn, req.user.id, goodsId, quantity)), req, created = true)
    }
  }

  /** `PATCH /api/v1/me/cart/{id}` — `{"quantity":3,"version":1}` optimistic lock. */
  def update(id: Long): Action[JsValue] = authed(parse.json) { req =>
    val body = Body(req.body)
    val parsed =
      for
        quantity <- body.long("quantity", min = 1, max = 999999)
        version  <- body.long("version", min = 1)
        _        <- body.rejectUnknown(Set("quantity", "version")).toLeft(())
      yield (quantity, version)

    orFail(parsed, req) { case (quantity, version) =>
      outcome(db.inTransaction(conn => cart.updateQuantity(conn, req.user.id, id, quantity, version)),
        req, created = false)
    }
  }

  /** `DELETE /api/v1/me/cart/{id}` */
  def destroy(id: Long): Action[AnyContent] = authed { req =>
    if db.inTransaction(conn => cart.delete(conn, req.user.id, id)) then NoContent
    else ApiError.notFound("cart line not found", req)
  }

  private def outcome(result: Either[CartFailure, JsObject], req: RequestHeader, created: Boolean): Result =
    result match
      case Right(payload) => if created then Created(payload) else Ok(payload)
      case Left(CartFailure.GoodsNotFound) => ApiError.notFound("goods not found or not on sale", req)
      case Left(CartFailure.NotFound) => ApiError.notFound("cart line not found", req)
      case Left(CartFailure.OutOfStock(name, stock)) =>
        ApiError.conflict(ErrorCode.OutOfStock, s"only $stock of $name is in stock", req)
      case Left(CartFailure.VersionMismatch) =>
        ApiError.conflict(ErrorCode.Conflict, "the cart line changed, read /api/v1/me/cart again", req)
