package controllers

import ecs.{ApiError, Db, GoodsRepo, Paging, Sql}
import javax.inject.Inject
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents}

/** `GET /api/v1/categories/{id}/goods` — maps PHP `category.php?id=`. */
class CategoryController @Inject() (cc: ControllerComponents, db: Db, goodsRepo: GoodsRepo)
  extends AbstractController(cc) with ApiSupport:

  def goods(id: Long, page: Option[Int], page_size: Option[Int], sort: Option[String]): Action[AnyContent] =
    Action { req =>
      orFail(Paging.of(page, page_size), req) { p =>
        db.readOnly { conn =>
          if !goodsRepo.categoryVisible(conn, id) then ApiError.notFound("category not found", req)
          else
            // A category page shows its own and its immediate children's goods,
            // including goods attached through the many-to-many goods_cat table.
            val ids = goodsRepo.categorySubtreeIds(conn, id)
            val marks = Sql.marks(ids.size)
            val clause =
              s"""(g.cat_id IN ($marks)
                 |  OR EXISTS (SELECT 1 FROM goods_cat gc
                 |             WHERE gc.goods_id = g.goods_id AND gc.cat_id IN ($marks)))""".stripMargin
            Ok(goodsRepo.list(conn, clause, ids ++ ids, p, sort))
        }
      }
    }
