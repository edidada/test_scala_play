package controllers

import ecs.{ApiError, AuthedAction, Body, Db, EmailAddress, FieldError, UserRepo}
import javax.inject.Inject
import play.api.libs.json.{JsObject, JsValue, Json}
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents}

/** Session endpoints — maps PHP user.php?act=act_register|act_login|logout|is_registered|check_email. */
class AuthController @Inject() (cc: ControllerComponents, db: Db, users: UserRepo, authed: AuthedAction)
  extends AbstractController(cc) with ApiSupport:

  /** `POST /api/v1/auth/register` — 201 with the new identity and one bearer token. */
  def register: Action[JsValue] = Action(parse.json) { req =>
    val body = Body(req.body)
    val parsed =
      for
        username <- AuthController.username(body.str("username", maxBytes = 60))
        email <- AuthController.emailField(body.str("email", maxBytes = 120))
        password <- AuthController.password(body.str("password", maxBytes = 1024))
        agreement <- body.bool("agreement_accepted", default = true)
        _ <- if agreement then Right(()) else Left(FieldError("agreement_accepted", "the user agreement must be accepted"))
      yield (username, email, password)

    orFail(parsed, req) { case (username, email, password) =>
      val created: Either[FieldError, (JsObject, String)] = db.inTransaction { conn =>
        users.register(conn, username, email, password).flatMap { case (id, token) =>
          users.profile(conn, id) match
            case Some(profile) => Right((profile, token))
            case None          => Left(FieldError("username", "the account could not be created"))
        }
      }
      orFail(created, req) { case (profile, token) =>
        Created(profile ++ Json.obj("access_token" -> token, "token_type" -> "Bearer"))
      }
    }
  }

  /** `POST /api/v1/auth/availability/username` — advisory only; the unique key decides. */
  def usernameAvailability: Action[JsValue] = Action(parse.json) { req =>
    orFail(AuthController.username(Body(req.body).str("username", maxBytes = 60)), req) { username =>
      Ok(Json.obj("available" -> !db.readOnly(conn => users.usernameTaken(conn, username))))
    }
  }

  /** `POST /api/v1/auth/availability/email` — advisory only; the unique key decides. */
  def emailAvailability: Action[JsValue] = Action(parse.json) { req =>
    orFail(AuthController.emailField(Body(req.body).str("email", maxBytes = 120)), req) { email =>
      Ok(Json.obj("available" -> !db.readOnly(conn => users.emailTaken(conn, email))))
    }
  }

  /** `POST /api/v1/auth/login` — the same answer for an unknown name and a wrong password. */
  def login: Action[JsValue] = Action(parse.json) { req =>
    val body = Body(req.body)
    val parsed =
      for
        identifier <- AuthController.identifier(body.str("username", maxBytes = 120))
        password <- body.str("password", maxBytes = 1024)
      yield (identifier, password)

    orFail(parsed, req) { case (identifier, password) =>
      db.inTransaction(conn => users.login(conn, identifier, password)) match
        case Some((id, name, token)) =>
          Ok(Json.obj("user_id" -> id, "username" -> name, "access_token" -> token, "token_type" -> "Bearer"))
        case None => ApiError.unauthorized("username or password is incorrect", req)
    }
  }

  /** `POST /api/v1/auth/logout` — revokes exactly the session carrying this token. */
  def logout: Action[AnyContent] = authed { req =>
    AuthedAction.bearerOf(req.headers).foreach { token =>
      db.inTransaction(conn => users.revokeSession(conn, token))
    }
    NoContent
  }

object AuthController:
  def username(either: Either[FieldError, String]): Either[FieldError, String] =
    either.flatMap { raw =>
      val value = raw.trim
      if value.length < 3 || value.length > 60 then Left(FieldError("username", "username must be 3 to 60 characters"))
      else Right(value)
    }

  def emailField(either: Either[FieldError, String]): Either[FieldError, String] =
    either.flatMap { raw =>
      val value = raw.trim.toLowerCase
      if !EmailAddress.valid(value) then Left(FieldError("email", "email must be a valid address"))
      else Right(value)
    }

  def identifier(either: Either[FieldError, String]): Either[FieldError, String] =
    either.flatMap { raw =>
      val value = raw.trim
      if value.isEmpty then Left(FieldError("username", "username or email is required"))
      else if value.length > 120 then Left(FieldError("username", "username or email is too long"))
      else Right(value)
    }

  def password(either: Either[FieldError, String]): Either[FieldError, String] =
    either.flatMap { value =>
      val bytes = value.getBytes("UTF-8").length
      if bytes < 8 || bytes > 1024 then Left(FieldError("password", "password must be 8 to 1024 bytes"))
      else Right(value)
    }
