package controllers

import javax.inject.Inject
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents}

/** Bonus (red packet) endpoints — maps PHP user.php?act=bonus|act_add_bonus. */
class BonusController @Inject() (cc: ControllerComponents) extends AbstractController(cc) with ApiSupport:
  def index : Action[AnyContent] = Action { req => notReady("GET /api/v1/me/bonuses")(req) }

  def claim : Action[AnyContent] = Action { req => notReady("POST /api/v1/me/bonuses/claim")(req) }
