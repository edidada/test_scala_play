package controllers

import ecs.{ApiError, FieldError}
import play.api.libs.json.JsValue
import play.api.mvc.{AbstractController, Request, RequestHeader, Result}

/**
 * Shared controller plumbing: contract error passthrough, JSON body access and
 * the `notReady` placeholder used while the URL skeleton is being implemented.
 */
trait ApiSupport:
  self: AbstractController =>

  /** 501 placeholder for a skeleton route (docs/09_url_completion_matrix.md). */
  protected def notReady(endpoint: String)(req: RequestHeader): Result =
    ApiError.notImplemented(endpoint, req)

  /** Any field validation failure becomes HTTP 400 `validation_error`. */
  protected def fail(fe: FieldError, req: RequestHeader): Result =
    FieldError.toResult(fe, req)

  protected def orFail[A](either: Either[FieldError, A], req: RequestHeader)(f: A => Result): Result =
    either match
      case Right(value) => f(value)
      case Left(fe)     => fail(fe, req)

  /** Fold a list of validations left-to-right, then build the result. */
  protected def orFailAll(errors: Seq[FieldError], req: RequestHeader)(f: () => Result): Result =
    errors.headOption match
      case Some(fe) => fail(fe, req)
      case None     => f()

  protected def jsonBody(req: Request[JsValue]): JsValue = req.body

  protected def xml(content: String): Result =
    Ok(content).as("application/xml; charset=utf-8")

  protected def js(content: String): Result =
    Ok(content).as("application/javascript; charset=utf-8")
