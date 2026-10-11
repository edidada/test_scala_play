package ecs

import javax.inject.{Inject, Singleton}
import play.api.libs.json.JsValue
import play.api.mvc._

import scala.concurrent.{ExecutionContext, Future}

/** The authenticated principal, resolved from the bearer token only. */
final case class AuthUser(id: Long, userName: String, email: String)

final class AuthedRequest[A](val user: AuthUser, request: Request[A]) extends WrappedRequest[A](request):
  /** The controller mix-in reads the request through this alias. */
  def requestHeader: Request[A] = request

/**
 * `Authorization: Bearer <token>` action builder.
 *
 * User identity always comes from the session row behind the token hash, never
 * from the request body or query string.
 */
@Singleton
class AuthedAction @Inject() (val parser: BodyParsers.Default, db: Db)(implicit val executionContext: ExecutionContext)
  extends ActionBuilder[AuthedRequest, AnyContent]:

  private val bearer = """^\s*Bearer\s+(\S+)\s*$""".r

  /** None when the header is missing, malformed, unknown, or expired-looking. */
  def userOf(header: Headers): Option[AuthUser] =
    header.get("Authorization").flatMap { value =>
      value match
        case bearer(token) =>
          val sql = """SELECT u.user_id, u.user_name, u.email
                      |FROM sessions s JOIN users u ON u.user_id = s.user_id
                      |WHERE s.token_hash = ?""".stripMargin
          db.readOnly { conn => db.row(conn, sql, Seq(Crypto.sha256Hex(token))) }
        case _ => None
    }.map(row => AuthUser(row.long("user_id"), row.str("user_name"), row.str("email")))

  override def invokeBlock[A](request: Request[A], block: AuthedRequest[A] => Future[Result]): Future[Result] =
    userOf(request.headers) match
      case Some(user) => block(new AuthedRequest(user, request))
      case None       => Future.successful(ApiError.unauthorized("a valid bearer token is required", request))

  /*
   * Endpoints use the builder directly, which keeps the synchronous `Result`
   * style of the rest of the API:
   *   `authed { req => ... }` and `authed(parse.json) { req => ... }`.
   */

/** JSON body helper: every API mutation reads its fields through this. */
final class Body(private val json: JsValue):
  import play.api.libs.json.*

  private def field(name: String): Option[JsValue] = json match
    case o: JsObject => o.value.get(name)
    case _           => None

  def missingField: FieldError = FieldError("body", "a JSON object body is required")

  def str(name: String, maxBytes: Int = 4096, allowEmpty: Boolean = false): Either[FieldError, String] =
    field(name) match
      case None | Some(JsNull) => Left(FieldError(name, s"$name is required"))
      case Some(JsString(v)) =>
        if v.getBytes("UTF-8").length > maxBytes then Left(FieldError(name, s"$name must be at most $maxBytes bytes"))
        else if !allowEmpty && v.trim.isEmpty then Left(FieldError(name, s"$name must not be blank"))
        else Right(v)
      case Some(other) => Left(FieldError(name, s"$name must be a string"))

  /** Optional string: absent or null yields None, present values are validated. */
  def optStr(name: String, maxBytes: Int = 4096): Option[Either[FieldError, String]] =
    field(name) match
      case None | Some(JsNull) => None
      case Some(_)             => Some(str(name, maxBytes, allowEmpty = true))

  def long(name: String, min: Long = Long.MinValue, max: Long = Long.MaxValue): Either[FieldError, Long] =
    field(name) match
      case None | Some(JsNull) => Left(FieldError(name, s"$name is required"))
      case Some(JsNumber(v)) =>
        if v.isValidLong then
          val l = v.toLong
          if l < min || l > max then Left(FieldError(name, s"$name must be between $min and $max")) else Right(l)
        else Left(FieldError(name, s"$name must be an integer"))
      case Some(JsString(text)) =>
        text.toLongOption match
          case Some(l) if l >= min && l <= max => Right(l)
          case Some(_)                         => Left(FieldError(name, s"$name must be between $min and $max"))
          case None                            => Left(FieldError(name, s"$name must be an integer"))
      case Some(_) => Left(FieldError(name, s"$name must be an integer"))

  def optLong(name: String, min: Long = Long.MinValue, max: Long = Long.MaxValue): Either[FieldError, Option[Long]] =
    field(name) match
      case None | Some(JsNull) => Right(None)
      case Some(_)             => long(name, min, max).map(Some(_))

  /** Money arrives as a decimal string and is converted to integer cents. */
  def cents(name: String, minInclusive: BigDecimal = BigDecimal(0.01)): Either[FieldError, Long] =
    str(name, maxBytes = 32)
      .flatMap { text =>
        ApiJson.toCents(text) match
          case Some(c) if BigDecimal(c) / 100 >= minInclusive => Right(c)
          case Some(_) => Left(FieldError(name, s"$name must be at least ${ApiJson.money(minInclusive)}"))
          case None    => Left(FieldError(name, s"$name must be a decimal string like \"10.00\""))
      }

  def bool(name: String, default: Boolean = false): Either[FieldError, Boolean] =
    field(name) match
      case None | Some(JsNull) => Right(default)
      case Some(JsBoolean(v))  => Right(v)
      case Some(_)             => Left(FieldError(name, s"$name must be a boolean"))

  def ids(name: String, min: Int = 1, max: Int = 100): Either[FieldError, List[Long]] =
    field(name) match
      case None | Some(JsNull) => Left(FieldError(name, s"$name is required"))
      case Some(arr: JsArray) =>
        val parsed = arr.value.toList.collect { case JsNumber(v) if v.isValidLong => v.toLong }
        if parsed.length != arr.value.length then Left(FieldError(name, s"$name must contain integers"))
        else if parsed.isEmpty then Left(FieldError(name, s"$name must contain at least $min item"))
        else if parsed.length > max then Left(FieldError(name, s"$name must contain at most $max items"))
        else if parsed.distinct.length != parsed.length then Left(FieldError(name, s"$name must not contain duplicates"))
        else Right(parsed)
      case Some(_) => Left(FieldError(name, s"$name must be an array"))

  def optIds(name: String, max: Int = 100): Either[FieldError, List[Long]] =
    field(name) match
      case None | Some(JsNull) => Right(Nil)
      case Some(_)             => ids(name, max = max)

  /** Reject unknown keys on strict endpoints. */
  def rejectUnknown(allowed: Set[String]): Option[FieldError] = json match
    case o: JsObject =>
      o.value.keys.find(k => !allowed.contains(k)).map(k => FieldError(k, s"unknown field $k"))
    case _ => Some(missingField)

object Body:
  def apply(json: JsValue): Body = new Body(json)

/** Query-string pagination following docs/02_url_api_list.md. */
object Paging:
  val DefaultSize = 20
  val MaxSize     = 100

  final case class Page(page: Int, pageSize: Int):
    def offset: Long = (page - 1).toLong * pageSize.toLong

  def of(page: Option[Int], pageSize: Option[Int]): Either[FieldError, Page] =
    page.getOrElse(1) match
      case p if p < 1 => Left(FieldError("page", "page must be greater than or equal to 1"))
      case p =>
        pageSize.getOrElse(DefaultSize) match
          case s if s < 1 || s > MaxSize => Left(FieldError("page_size", s"page_size must be between 1 and $MaxSize"))
          case s                         => Right(Page(p, s))

  def parse(raw: Option[String], name: String, min: Int, max: Int): Either[FieldError, Option[Int]] =
    raw match
      case None | Some("") => Right(None)
      case Some(text) =>
        text.toIntOption match
          case Some(v) if v >= min && v <= max => Right(Some(v))
          case Some(_) => Left(FieldError(name, s"$name must be between $min and $max"))
          case None    => Left(FieldError(name, s"$name must be an integer"))

  /** Whitelisted ORDER BY fragment; unknown sorts fall back to the default. */
  def sort(requested: Option[String], allowed: Map[String, String], default: String): String =
    requested.flatMap(allowed.get).getOrElse(default)

/** Email shape check used by register/reset/newsletter endpoints. */
object EmailAddress:
  private val pattern = """^[^@\s]+@[^@\s.]+(\.[^@\s.]+)+$""".r
  def valid(text: String): Boolean = text.length <= 120 && pattern.matches(text)
