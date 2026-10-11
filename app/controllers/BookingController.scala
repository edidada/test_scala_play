package controllers

import javax.inject.Inject
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents}

/** Out-of-stock bookings — maps PHP user.php?act=booking_list|act_add_booking|act_del_booking. */
class BookingController @Inject() (cc: ControllerComponents) extends AbstractController(cc) with ApiSupport:
  def index(page: Option[Int], page_size: Option[Int]) : Action[AnyContent] = Action { req => notReady("GET /api/v1/me/bookings")(req) }

  def create : Action[AnyContent] = Action { req => notReady("POST /api/v1/me/bookings")(req) }

  def destroy(id: Long) : Action[AnyContent] = Action { req => notReady("DELETE /api/v1/me/bookings/{id}")(req) }
