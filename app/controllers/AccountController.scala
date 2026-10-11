package controllers

import ecs.{AccountFailure, AccountRepo, ApiError, AuthedAction, Body, Db, ErrorCode, FieldError, Paging}
import javax.inject.Inject
import play.api.libs.json.{JsObject, JsValue}
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents, RequestHeader, Result}

/** Balance requests and the cent ledger — maps PHP user.php?act=act_account|account_log|cancel|account_detail|pay. */
class AccountController @Inject() (cc: ControllerComponents, db: Db, account: AccountRepo, authed: AuthedAction)
  extends AbstractController(cc) with ApiSupport:

  /** `GET /api/v1/me/account/requests` */
  def listRequests(page: Option[Int], page_size: Option[Int]): Action[AnyContent] = authed { req =>
    orFail(Paging.of(page, page_size), req) { p =>
      Ok(db.readOnly(conn => account.listRequests(conn, req.user.id, p)))
    }
  }

  /** `POST /api/v1/me/account/requests` — deposit or withdrawal, money as a decimal string. */
  def createRequest: Action[JsValue] = authed(parse.json) { req =>
    val body = Body(req.body)
    val parsed =
      for
        kind <- body.str("kind", maxBytes = 20).flatMap { value =>
            if value == "deposit" || value == "withdrawal" then Right(value)
            else Left(FieldError("kind", "kind must be deposit or withdrawal"))
          }
        amount <- body.cents("amount")
        paymentId <- body.optLong("payment_id", 1)
        note <- MessageController.text(body.optStr("note", 255), "")
        _ <- body.rejectUnknown(Set("kind", "amount", "payment_id", "note")).toLeft(())
      yield (kind, amount, paymentId, note)

    orFail(parsed, req) { case (kind, amount, paymentId, note) =>
      accountOutcome(db.inTransaction(conn =>
        account.createRequest(conn, req.user.id, kind, amount, paymentId, note)), req, created = true)
    }
  }

  /** `DELETE /api/v1/me/account/requests/{id}` */
  def cancelRequest(id: Long): Action[AnyContent] = authed { req =>
    db.inTransaction(conn => account.cancelRequest(conn, req.user.id, id)) match
      case Right(_) => NoContent
      case Left(AccountFailure.NotFound) => ApiError.notFound("account request not found", req)
      case Left(_) => ApiError.conflict(ErrorCode.Conflict, "this account request can no longer be cancelled", req)
  }

  /** `GET /api/v1/me/account/transactions` */
  def transactions(page: Option[Int], page_size: Option[Int]): Action[AnyContent] = authed { req =>
    orFail(Paging.of(page, page_size), req) { p =>
      Ok(db.readOnly(conn => account.transactions(conn, req.user.id, p)))
    }
  }

  /** `POST /api/v1/me/account/requests/{id}/payment` — idempotent on the request id. */
  def payRequest(id: Long): Action[JsValue] = authed(parse.json) { req =>
    val body = Body(req.body)
    val parsed =
      for
        paymentId <- body.long("payment_id", min = 1)
        _ <- body.rejectUnknown(Set("payment_id")).toLeft(())
      yield paymentId

    orFail(parsed, req) { paymentId =>
      accountOutcome(db.inTransaction(conn =>
        account.payRequest(conn, req.user.id, id, paymentId)), req, created = false)
    }
  }

  /** One place that turns the repository failures into contract status codes. */
  private def accountOutcome(outcome: Either[AccountFailure, JsObject], req: RequestHeader, created: Boolean): Result =
    outcome match
      case Right(payload) => if created then Created(payload) else Ok(payload)
      case Left(AccountFailure.PaymentRequired) =>
        fail(FieldError("payment_id", "a deposit request needs an enabled payment_id"), req)
      case Left(AccountFailure.PaymentUnavailable) =>
        ApiError.notFound("payment method is not available", req)
      case Left(AccountFailure.InsufficientBalance) =>
        ApiError.conflict(ErrorCode.Conflict, "the available balance is not sufficient", req)
      case Left(AccountFailure.WrongState) =>
        ApiError.conflict(ErrorCode.Conflict, "the account request is already processed", req)
      case Left(AccountFailure.NotFound) => ApiError.notFound("account request not found", req)
      case Left(AccountFailure.UnknownKind(kind)) =>
        fail(FieldError("kind", s"kind $kind is not supported"), req)
