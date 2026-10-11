# Scala Play 实际架构图

本文的每张图都由**当前代码**反推而来，不是设计愿景：`app/`、`conf/routes`、`conf/application.conf`
里能找到的东西才会出现在图上。图后附有对应的核对命令。

对照说明：[04_architecture.md](04_architecture.md) 描述的是更早的 C++ 方案（Crow + Redis +
UseCase/Handler 分层，订单还有 `shipped`、`refunding` 写入路径）。那套东西本仓库没有实现，
本文取代它在 Scala 侧的位置；04 原文保持不动。

## 一、分层总览

```mermaid
flowchart TD
    C["客户端<br/>curl / 前端 / 支付网关回调"]

    subgraph PLAY["Play 运行时（conf/application.conf）"]
        FIL["请求过滤器链<br/>CSRF 已关闭 · allowedHosts=localhost/127.0.0.1/::1"]
        EH["ecs.ApiErrorHandler<br/>play.http.errorHandler"]
    end

    R["conf/routes<br/>102 条端点"]

    subgraph CTRL["表现层：app/controllers/ · 29 个 Controller + ApiSupport mix-in"]
        ACT["AuthedAction（Bearer 解析）<br/>或普通 Action"]
        TX["事务边界在这里开：db.readOnly / db.inTransaction<br/>参数校验、状态码、JSON 组装"]
    end

    subgraph DOM["业务层：app/ecs/*Repo.scala · 27 个 Repo"]
        REPO["方法签名都是 conn 打头<br/>业务规则 + 手写 SQL + Either 失败类型/JsObject<br/>不自己提交，也不自己回滚"]
    end

    subgraph DATA["数据访问层：app/ecs/Db.scala"]
        DB["Db：readOnly / inTransaction<br/>rows·row·count·scalar·execute·insertReturningKey"]
        SQL["Sql：forUpdate / insertIgnore / upsert / random / marks<br/>唯一允许拼方言片段的地方"]
    end

    POOL["HikariCP（Play jdbc + JNDI DefaultDS）"]

    subgraph STORE["数据库（方言由 db.default.url 前缀决定）"]
        LITE[("SQLite<br/>dev + CI + 测试")]
        MY[("MySQL / MariaDB<br/>生产目标，本仓库未实测")]
    end

    BOX[("email_outbox<br/>MailRepo 写的邮件出站表")]

    C --> FIL --> R --> ACT --> TX --> REPO
    EH -.->|"错误体 code/message/request_id/details"| C
    REPO --> DB --> SQL --> POOL
    POOL --> LITE
    POOL --> MY
    REPO --> BOX

    BOOT["ecs.AppModule → ecs.Bootstrap（eager singleton）<br/>启动时按方言执行对应的 boot + seed 脚本"]
    BOOT -.-> LITE
    BOOT -.-> MY
```

横切工具不属于任何一层，被上层广泛复用（引用文件数由 `grep -rl` 实测）：

| 工具 | 位置 | 职责 | 被引用文件数 |
|---|---|---|---|
| `ApiJson` | `ecs/ApiError.scala` | 金额两位小数字符串、分/元换算、UTC 时间、`page()` 信封 | 31 |
| `Paging` | `ecs/Auth.scala` | 分页（默认 20、上限 100）+ `sort` 白名单 | 28 |
| `Body` | `ecs/Auth.scala` | 请求体取字段：`str/optStr/long/cents/bool/ids/rejectUnknown` | 21 |
| `Sql` | `ecs/Db.scala` | 方言片段（锁、忽略插入、upsert、随机排序） | 18 |
| `Views` | `ecs/Views.scala` | 商品价/摘要/时间戳的共享渲染 | 17 |
| `Crypto` | `ecs/Crypto.scala` | PBKDF2 口令、Bearer token、SHA-256、HMAC、常量时间比较、指纹 | 8 |
| `Abort` | `ecs/Abort.scala` | 在事务闭包内抛出以触发回滚，`Abort.guarded` 转成 `Left` | 6 |

一个和常见 Play 教程不同的约定：**事务边界属于 Controller**。读路径写
`db.readOnly(conn => repo.xxx(conn, …))`，写路径写
`Abort.guarded(db.inTransaction(conn => repo.xxx(conn, …)))`，27 个 Repo 的方法签名因此一律以
`conn: Connection` 开头，自己不提交也不回滚；跨 Repo 复用（如 `OrderRepo` 调
`CartRepo.pricedLines`）靠的是把同一个 `conn` 传下去，而不是各自开连接。
唯一把 SQL 片段写在 Controller 里的是 `CategoryController`，它用 `Sql.marks(n)` 生成 `IN (?)`
占位符串，值仍然全部走绑定参数。

核对：

```bash
grep -cE "^(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS) " conf/routes     # 102
ls app/controllers/*.scala | wc -l                                     # 30 个文件：29 个 Controller + ApiSupport.scala
ls app/ecs/*Repo.scala | wc -l                                         # 27
grep -rhoiE "CREATE TABLE" conf/sql/sqlite_boot.sql | wc -l             # 53 张表
```

## 二、一次下单请求的完整生命周期

`POST /api/v1/orders` 是最能体现约束的路径：身份只来自 token、写操作在事务里、库存用条件
UPDATE、幂等靠 key + 指纹。

```mermaid
sequenceDiagram
    autonumber
    participant CL as 客户端
    participant AC as AuthedAction
    participant OC as OrderController
    participant OR as OrderRepo
    participant DB as Db
    participant SQL as 数据库

    CL->>AC: Authorization: Bearer t, Idempotency-Key: k, JSON body
    AC->>DB: 取 session 行 WHERE token_hash = SHA-256(t)
    Note over AC,DB: 用户身份只来自这行；body/query 里的用户 id 一律不读<br/>缺失或失效 → 401 unauthenticated
    AC->>OC: req.user.id
    OC->>OC: Body 校验（含 rejectUnknown）+ key 必填且 ≤ 100，否则 400 validation_error
    OC->>OC: Crypto.fingerprint(address_id / shipping_id / payment_id / remark)
    OC->>DB: db.inTransaction(conn => orders.create(conn, …))
    DB->>SQL: SELECT order_id … WHERE user_id=? AND idempotency_key=? FOR UPDATE
    alt 同 key 且指纹相同
        OR->>SQL: 重新读取该订单当前 summary（不重放旧响应体）
        OC-->>CL: 200 + replayed=true
    else 同 key 但指纹不同
        OC-->>CL: 409 conflict（指纹按字段名排序，与请求里的字段顺序无关）
    else 新 key
        OR->>SQL: SELECT cart 行 … FOR UPDATE
        OR->>SQL: UPDATE goods SET goods_number = goods_number - ?<br/>WHERE goods_id = ? AND goods_number >= ?
        Note over OR,SQL: 影响行数 ≠ 1 → throw Abort(OutOfStock) → 整个事务回滚
        OR->>SQL: INSERT order_info（'pending_payment','unshipped','unpaid'<br/>+ idempotency_key + request_fingerprint）与 order_goods 快照
        OR->>SQL: 金额/运费/支付费、积分、优惠券、审计写入同一事务
        OR->>DB: 返回 Right，由 Db 提交
        OC-->>CL: 201 order
    end
    Note over OC,DB: Abort 在闭包外让 Db 回滚，再由 Abort.guarded 转成 Left<br/>控制器统一映射成 409：code=conflict（幂等冲突）/ out_of_stock（缺货）/ invalid_state（状态不符）<br/>500 响应里没有 SQL 与堆栈
```

## 三、支付回调的结算路径

回调是系统里唯一被外部触发的写路径，所以它绕开 JSON 解析器直接吃原始字节。

```mermaid
sequenceDiagram
    autonumber
    participant GW as 支付网关
    participant PC as PaymentCallbackController
    participant DB as Db
    participant PR as PaymentCallbackRepo
    participant SQL as 数据库

    GW->>PC: POST /api/v1/payments/{provider}/callback<br/>X-Payment-Signature: hex(HMAC-SHA256(raw body))
    Note over PC: 签名对**原始请求体字节**计算（Action(parse.raw)），不是重新序列化后的字符串<br/>密钥 payment.callback_hmac_secret，生产由 PAYMENT_CALLBACK_SECRET 注入
    PC->>PC: Crypto.constantTimeEquals(签名, 期望值)，缺失或不符 → 401
    PC->>PC: 之后才 Json.parse(payload)，取 provider_trade_no / order_sn / amount
    PC->>DB: db.inTransaction(conn => callbacks.settle(conn, …))
    DB->>SQL: SELECT pay_log WHERE provider=? AND provider_trade_no=?
    alt 同一 trade_no + 同订单 + 同金额
        PC-->>GW: 200 replayed=true
    else 同一 trade_no 但订单或金额不同
        PC-->>GW: 409 conflict（拒绝跨订单重放）
    else 首次通知
        PR->>SQL: SELECT order_info WHERE order_sn=? FOR UPDATE
        Note over PR,SQL: 订单不存在 → 404；金额 ≠ order_amount → 409 conflict；<br/>状态不是 pending_payment → 409 + code=invalid_state（422 的 ApiError.invalidState 用在这条路径之外）
        PR->>SQL: INSERT INTO pay_log …（Sql.insertIgnore 去重，status='paid'）
        Note over PR,SQL: 插入 0 行说明并发通知刚抢先提交 → 复查后按 replayed=true 返回
        PR->>SQL: UPDATE order_info SET order_status='paid', pay_status='paid', pay_time=?<br/>WHERE order_id=? AND order_status='pending_payment'
        PR->>SQL: INSERT order_action（actor_type='system'）
        PC-->>GW: 200 pay_status=paid, replayed=false
    end
```

回调没有任何库存扣减或积分发放的副作用 —— 那些都在下单事务里已经完成了；回调只做「标记已付 +
留凭证」。两处 `throw Abort` 会让整个回调事务回滚，网关拿不到成功语义就会重试，这正是想要的行为。

`PaymentCallbackController` 是**唯一**直接注入 `play.api.Configuration` 的控制器，为的就是读
`payment.callback_hmac_secret`；另一个读配置的是 `ecs.FeedRepo`（拼 feed 的绝对 URL）。

原始 payload **不写应用日志**（代码注释里明确了原因：它带 trade number 和精确金额），但它会原样
存进 `pay_log.raw_payload` 供对账 —— 排查时看的是表，不是日志。

## 四、真实的订单状态机

只有下图这些迁移在代码里存在。`shipped` 只在读侧出现（`order_info.shipping_status`），
没有任何写入路径 —— 后台发货不在本仓库范围内。

```mermaid
stateDiagram-v2
    [*] --> pending_payment: POST /api/v1/orders

    pending_payment --> paid: 回调验签通过<br/>WHERE order_status='pending_payment'
    pending_payment --> pending_payment: 余额抵扣未清完<br/>order_amount 递减，pay_status 仍为 unpaid
    pending_payment --> paid: 余额抵扣把 order_amount 降到 ≤ 0<br/>同一条 UPDATE 里置为 paid
    pending_payment --> cancelled: 用户取消（仅此状态可取消）<br/>回补库存 + 退回已抵扣余额 + 记流水

    paid --> received: POST orders/{id}/received<br/>WHERE order_status='paid'
    paid --> paid: 回调重放 → 200 replayed=true
    received --> [*]
    cancelled --> [*]

    pending_payment --> pending_payment: 改支付方式 / 改地址要求 unshipped<br/>合并订单要求两边都没用过余额抵扣
```

| 迁移 | 触发端点 | 守卫条件 |
|---|---|---|
| 创建 | `POST /api/v1/orders` | 幂等 key + 库存条件 UPDATE |
| → `paid` | `POST /api/v1/payments/{provider}/callback` | `order_status='pending_payment'` |
| 余额抵扣 | `PATCH /api/v1/me/orders/:id/surplus` | `pending_payment` 且 `pay_status='unpaid'`，`available_cents >= ?` |
| → `cancelled` | `POST /api/v1/me/orders/:id/cancel` | `order_status='pending_payment'`；订单行保留为 `cancelled`，只删 `order_balance_payment` |
| → `received` | `POST /api/v1/me/orders/:id/received` | `order_status='paid'` |
| 改支付方式 | `PATCH /api/v1/me/orders/:id/payment` | `pending_payment` 且 `shipping_status='unshipped'`，并且新的 `pay_id` 必须与当前不同、且 `enabled = 1` |
| 改地址 | `PATCH /api/v1/me/orders/:id/address` | `pending_payment` 且 `shipping_status='unshipped'` |
| 合并订单 | `POST /api/v1/me/orders/merge` | 两单都是自己的 `pending_payment`、都还没用过余额抵扣、且不是同一单；合并后两条原订单行被删除 |
| 重新加入购物车 | `POST /api/v1/me/orders/:id/cart` | 不校验状态，也不改状态，只把仍可售的商品合回购物车 |

## 五、依赖注入关系

Controller 只依赖 `Db`/`AuthedAction` 与若干 Repo；Repo 之间只有下面这几条真实注入边，
其余 19 个 Repo 的构造函数只有 `Db`。没有 ORM，没有 service 层。

```mermaid
graph LR
    subgraph CTRL["Controller（29）"]
        OrderC["OrderController"]
        PayC["PaymentCallbackController"]
        CheckC["CheckoutController"]
        CartC["CartController"]
        AddrC["AddressController"]
        NewsC["NewsletterController"]
        VerC["EmailVerification / PasswordReset"]
        FeedC["FeedController"]
        WidgetC["WidgetController"]
        CatC["Catalog / Category / GoodsController"]
        PromC["PromotionController"]
    end

    OrderC --> OrderRepo
    OrderC --> PointsRepo
    OrderC --> GroupBuyRepo
    OrderC --> AffiliateRepo
    PayC --> PaymentCallbackRepo
    CheckC --> CheckoutRepo
    CartC --> CartRepo
    AddrC --> AddressRepo
    NewsC --> NewsletterRepo
    VerC --> VerificationRepo
    PromC --> MarketingRepo
    FeedC --> FeedRepo
    WidgetC --> WidgetRepo
    CatC --> CatalogRepo
    CatC --> GoodsRepo

    OrderRepo --> CartRepo
    CheckoutRepo --> CartRepo
    CheckoutRepo --> RegionRepo
    AddressRepo --> RegionRepo
    MarketingRepo --> GoodsRepo
    FeedRepo --> GoodsRepo
    WidgetRepo --> GoodsRepo
    NewsletterRepo --> MailRepo
    VerificationRepo --> MailRepo

    OrderRepo --> DbX["Db"]
    CartRepo --> DbX
    GoodsRepo --> DbX
    MailRepo --> DbX
    RegionRepo --> DbX
    DbX --> Hik["JDBC DataSource"]

    MailRepo -->|"INSERT"| OUT[("email_outbox")]
```

每个 Repo 都以 `Db` 作为构造参数，图上底部只画了 5 条示例边，否则 27 条线会糊成一团；
`CatC` 一个节点代表 `CatalogController`（`CatalogRepo` + `GoodsRepo`）、`CategoryController`
和 `GoodsController`（后两个只用 `GoodsRepo`）。

`ecs/ApiSupport.scala` 是共享 mix-in（29 个 Controller 全部 `with ApiSupport`；状态码助手、
错误构造、`notReady`），不是运行时依赖，所以不在图上。`grep -rn "notReady(" app/` 现在只剩它自己的定义，
说明 102 条端点全部有实现。`README.md` 目录结构里写的「30 controllers」数的是
`ls app/controllers/*.scala` 的文件数，那个多出来的文件就是这份 mix-in。

## 六、HTTP 接口面

102 条端点按用途分桶，非 `/api` 的 5 条是 PHP 时代遗留的入口脚本：

| 分组 | 数量 | 覆盖范围 |
|---|---|---|
| `/api/v1/me**` 账户域 | 49 | 资料与密码、购物车、地址、余额与提现、红包、收藏、标签、预订、评论、留言、订单全套、团购、推广分成、浏览历史 |
| 商品读取与试算 | 5 | `goods` 列表、详情、价格试算、`compare`、`quotation` |
| 商品互动 | 4 | 评论读取/写入、商品标签、图集 |
| 促销与活动 | 11 | `promotions`、`activities`、`packages`、`topics`、`votes`、`exchange-goods`、`ads` |
| 认证与凭据恢复 | 8 | `auth` 注册/登录/登出/可用性探测、邮箱验证、密码重置 |
| 目录与内容 | 7 | `home`、`catalog`、分类商品、品牌列表与品牌商品、文章与分类文章 |
| 社区与辅助读取 | 9 | newsletter 订阅与退订及确认、`messages`、`tags` 云、`shipping-options`、`regions` |
| 结算与下单 | 4 | `checkout/options`、`checkout/quote`、`POST /api/v1/orders`、`POST /api/v1/payments/{provider}/callback` |
| 非 `/api` 入口 | 5 | `/healthz`、`/sitemap.xml`、`/feed.xml`、`/goods-widget.js`、`/cycle-image.xml` |

`me**` 之所以占 49 条，是因为 PHP 那边「我的」相关功能分散在 `user.php`、`flow.php`、
`cart.php`、`group_buy.php` 等多个入口，每个动作都是一条独立 URL；这里按 REST 约定收拢成一个
前缀，动作放在子路径上。

## 七、配置与运行时的边界

```mermaid
flowchart LR
    ENV["环境变量<br/>DB_DRIVER · DB_URL · DB_USER · DB_PASSWORD<br/>PAYMENT_CALLBACK_SECRET · SITE_BASE_URL · APPLICATION_SECRET"]
    CONF["conf/application.conf<br/>db.default.url 决定方言<br/>ecshop.boot 下配 ddl 与 seed 两个脚本路径"]
    MOD["ecs.AppModule<br/>DB_DRIVER 为空或含 sqlite 时先建 ./data · 绑定 Bootstrap"]
    BS["ecs.Bootstrap<br/>IF NOT EXISTS DDL + INSERT OR IGNORE seed（幂等）"]
    RUN["Play dev/prod<br/>sbt run :9000"]

    ENV --> CONF --> MOD --> BS --> RUN
    CONF -->|"jdbc:mysql / jdbc:mariadb 前缀 → MySQL<br/>否则 SQLite"| DIA["ecs.Db.dialect"]
    DIA -->|"只影响片段语法"| SQLX["ecs.Sql"]
    DIA -->|"决定读哪份 boot/seed"| BS
```

需要注意的四点：

1. `conf/application.conf` 里的 `payment.callback_hmac_secret` 和 `play.http.secret.key` 都是
   开发占位值，生产分别用 `PAYMENT_CALLBACK_SECRET`、`APPLICATION_SECRET` 覆盖。
2. 方言只在 `ecs.Sql` 与 `Bootstrap` 里起作用；业务 SQL 本身两边通用，因为所有语句都走占位符。
3. MySQL 这条路只有代码与脚本，没有实跑过：本机没有服务，CI 用 SQLite（`sqlite-jdbc` 自带）。
   这一点在 `README.md` 的「已验证与未验证」里同样标注着。
4. `build.sbt` 里留着 `jdbc` 与 `evolutions` 两个 Play 依赖，但
   `play.evolutions.db.default.enabled = false` 且 `autoApply = false`：建表与种子数据由
   `ecs.Bootstrap` 直接执行 `conf/sql/*_boot.sql` / `*_seed.sql`，语句本身是
   `IF NOT EXISTS` + `INSERT OR IGNORE`，重复启动安全，因此不需要 evolution 脚本。
