package controllers

import javax.inject.Inject
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents}

/** Tag endpoints — maps PHP tag_cloud.php / user.php?act=add_tag|tag_list|act_del_tag. */
class TagController @Inject() (cc: ControllerComponents) extends AbstractController(cc) with ApiSupport:
  def cloud : Action[AnyContent] = Action { req => notReady("GET /api/v1/tags")(req) }

  def addForGoods(id: Long) : Action[AnyContent] = Action { req => notReady("POST /api/v1/goods/{id}/tags")(req) }

  def mine(page: Option[Int], page_size: Option[Int]) : Action[AnyContent] = Action { req => notReady("GET /api/v1/me/tags")(req) }

  def destroyMine : Action[AnyContent] = Action { req => notReady("DELETE /api/v1/me/tags")(req) }
