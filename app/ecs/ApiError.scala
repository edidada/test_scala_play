package ecs

import play.api.libs.json.{JsObject, JsValue, Json}
import play.api.mvc.{RequestHeader, Result, Results}

import java.time.{Instant, ZoneOffset, format}
import java.util.UUID
import scala.math.BigDecimal.RoundingMode

/** Machine-readable error codes from docs/03_api_contract.md. */
object ErrorCode:
  val Validation       = "validation_error"
  val Unauthenticated  = "unauthenticated"
  val Forbidden        = "forbidden"
  val NotFound         = "not_found"
  val Conflict         = "conflict"
  val OutOfStock       = "out_of_stock"
  val InvalidState     = "invalid_state"
  val Internal         = "internal_error"
  val NotImplemented   = "not_implemented"

/**
 * The single place that turns a failure into an HTTP response, so status code,
 * machine code, client message and `request_id` always stay consistent
 * (docs/03_api_contract.md, docs/10_api_error_factories.md).
 */
object ApiError:
  private val RequestIdHeader = "X-Request-Id"

  /** Reuse the caller-supplied request id so logs, body and audit rows agree. */
  def requestIdOf(req: RequestHeader): String =
    req.headers.get(RequestIdHeader).filter(_.trim.nonEmpty).getOrElse(UUID.randomUUID().toString)

  private def body(code: String, message: String, requestId: String, details: Option[JsValue]): JsObject =
    Json.obj("code" -> code, "message" -> message, "request_id" -> requestId) ++
      details.map(d => Json.obj("details" -> d)).getOrElse(Json.obj())

  private def result(status: Results.Status, code: String, message: String, req: RequestHeader, details: Option[JsValue]): Result =
    status(body(code, message, requestIdOf(req), details)).withHeaders(RequestIdHeader -> requestIdOf(req))

  def badRequest(message: String, req: RequestHeader, field: Option[String] = None): Result =
    result(Results.BadRequest, ErrorCode.Validation, message, req, field.map(f => Json.obj("field" -> f)))

  def unauthorized(message: String, req: RequestHeader): Result =
    result(Results.Unauthorized, ErrorCode.Unauthenticated, message, req, None)

  def forbidden(message: String, req: RequestHeader): Result =
    result(Results.Forbidden, ErrorCode.Forbidden, message, req, None)

  def notFound(message: String, req: RequestHeader): Result =
    result(Results.NotFound, ErrorCode.NotFound, message, req, None)

  def conflict(code: String, message: String, req: RequestHeader): Result =
    result(Results.Conflict, code, message, req, None)

  def outOfStock(message: String, req: RequestHeader): Result =
    conflict(ErrorCode.OutOfStock, message, req)

  def invalidState(message: String, req: RequestHeader): Result =
    result(Results.UnprocessableEntity, ErrorCode.InvalidState, message, req, None)

  def internal(message: String, req: RequestHeader): Result =
    result(Results.InternalServerError, ErrorCode.Internal, message, req, None)

  /** Placeholder used by the URL skeleton before an endpoint is implemented. */
  def notImplemented(endpoint: String, req: RequestHeader): Result =
    result(Results.NotImplemented, ErrorCode.NotImplemented, s"$endpoint is not implemented yet", req, None)

/** Value-level failure: which field, and what the client did wrong. */
final case class FieldError(field: String, message: String)

object FieldError:
  def toResult(fe: FieldError, req: RequestHeader): Result = ApiError.badRequest(fe.message, req, Some(fe.field))

/** Response helpers shared by every controller. */
object ApiJson:
  private val Rupee = java.util.Locale.ROOT

  /** Money is always a two-decimal string; the API never exposes floats. */
  def money(value: BigDecimal): String =
    value.setScale(2, RoundingMode.HALF_UP).bigDecimal.toPlainString

  def money(cents: Long): String = money(BigDecimal(cents) / 100)

  /** Integer cents of a decimal money string; None when it is not valid money. */
  def toCents(text: String): Option[Long] =
    parseDecimal(text).map(_.setScale(2, RoundingMode.HALF_UP).bigDecimal.movePointRight(2).longValueExact)

  def parseDecimal(text: String): Option[BigDecimal] =
    try
      val v = BigDecimal(text.trim)
      if v.scale > 4 then None else Some(v)
    catch case _: NumberFormatException => None

  val utcFormatter: format.DateTimeFormatter =
    format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC)

  def nowUtc: String = utcFormatter.format(Instant.now())

  def nowUnix: Long = Instant.now().getEpochSecond

  /** Parse a `yyyy-MM-dd HH:mm:ss` UTC stamp into epoch seconds (0 when absent). */
  def unixOf(text: String): Long =
    try java.time.LocalDateTime.parse(text, utcFormatter).toEpochSecond(ZoneOffset.UTC)
    catch case _: Exception => 0L

  /** Envelope used by every list endpoint (docs/02_url_api_list.md). */
  def page(page: Int, pageSize: Int, total: Long, items: Seq[JsValue]): JsObject =
    Json.obj("page" -> page, "page_size" -> pageSize, "total" -> total, "items" -> Json.toJson(items))
