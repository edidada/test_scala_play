package controllers

import ecs.ApiJson
import javax.inject.Inject
import play.api.libs.json.Json
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents}

/** M0 health probe: docs/README.md milestone table, `GET /healthz` -> 200. */
class HealthController @Inject() (cc: ControllerComponents)
  extends AbstractController(cc) with ApiSupport:

  def healthz : Action[AnyContent] = Action { _ =>
    Ok(Json.obj("status" -> "ok", "time" -> ApiJson.nowUtc))
  }
