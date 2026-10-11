package ecs

import javax.inject.{Inject, Singleton}
import play.api.Logger
import play.api.http.HttpErrorHandler
import play.api.mvc.{RequestHeader, Result}

import scala.concurrent.Future

/**
 * Turns Play's own client/server errors into the contract error body
 * (docs/03_api_contract.md).
 *
 * Nothing from the exception, its message or the SQL layer is ever written to
 * the response; the request id links the response to the server log line.
 */
@Singleton
class ApiErrorHandler @Inject() () extends HttpErrorHandler:
  private val log = Logger(this.getClass)

  private def now(result: Result): Future[Result] = Future.successful(result)

  override def onClientError(request: RequestHeader, statusCode: Int, message: String): Future[Result] =
    val text = Option(message).getOrElse("")
    val lower = text.toLowerCase
    statusCode match
      // `parse.json` and malformed bodies arrive here as 400.
      case 400 if lower.contains("json") || lower.contains("parsing") =>
        now(ApiError.badRequest("a valid JSON object body is required", request))
      case 400 => now(ApiError.badRequest(if text.isEmpty then "bad request" else text, request))
      case 401 => now(ApiError.unauthorized("a valid bearer token is required", request))
      case 403 => now(ApiError.forbidden("the request is not allowed", request))
      case 404 => now(ApiError.notFound("resource not found", request))
      case 405 => now(ApiError.badRequest("the HTTP method is not allowed for this resource", request))
      case 409 => now(ApiError.conflict(ErrorCode.Conflict, "the request conflicts with the current state", request))
      case 415 => now(ApiError.badRequest("Content-Type must be application/json", request))
      case 422 => now(ApiError.invalidState("the request does not fit the current resource state", request))
      case 429 => now(ApiError.conflict(ErrorCode.Conflict, "too many requests", request))
      case _   => now(ApiError.badRequest("bad request", request))

  override def onServerError(request: RequestHeader, exception: Throwable): Future[Result] =
    val requestId = ApiError.requestIdOf(request)
    log.error(s"unhandled error request_id=$requestId method=${request.method} path=${request.path}", exception)
    now(ApiError.internal("the server could not complete the request", request))
