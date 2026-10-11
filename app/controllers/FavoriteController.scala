package controllers

import javax.inject.Inject
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents}

/** Favourites — maps PHP user.php?act=collection_list|add_to_attention|delete_collection. */
class FavoriteController @Inject() (cc: ControllerComponents) extends AbstractController(cc) with ApiSupport:
  def index(page: Option[Int], page_size: Option[Int]) : Action[AnyContent] = Action { req => notReady("GET /api/v1/me/favorites")(req) }

  def create : Action[AnyContent] = Action { req => notReady("POST /api/v1/me/favorites")(req) }

  def update(id: Long) : Action[AnyContent] = Action { req => notReady("PATCH /api/v1/me/favorites/{id}")(req) }

  def destroy(id: Long) : Action[AnyContent] = Action { req => notReady("DELETE /api/v1/me/favorites/{id}")(req) }
