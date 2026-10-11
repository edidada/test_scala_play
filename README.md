# ECShop Play — PHP ECShop 业务能力的 Scala Play JSON API 重实现

把参考项目 ECShop（PHP，`third_party/shopex-ecshop` 子模块）的入口脚本能力，按
`docs/` 里的蓝图重写成一套**只做 JSON 的 REST API**：Play Framework 3.0 + Scala 3，
一套仓储同时跑 SQLite 与 MySQL 两种方言。`docs/README.md` 是原始设计蓝图（面向早期的
C++ 尝试，文末有本实现的追加说明），本文件只讲**当前仓库怎么跑起来、怎么验**。

![CI](https://github.com/edidada/test_scala_play/actions/workflows/ci.yml/badge.svg)

## 技术栈

| 项 | 值 |
| --- | --- |
| 框架 | Play Framework 3.0.12（Apache Pekko，Guice DI，InjectedRoutesGenerator） |
| 语言 | Scala 3.3.8 |
| 构建 | sbt 1.11.7 |
| JDK | 17（开发用）；CI 覆盖 17 与 21 |
| 数据库 | SQLite（`org.xerial:sqlite-jdbc`，开发默认）／ MySQL 8（`com.mysql:mysql-connector-j`） |
| 测试 | ScalaTest + scalatestplus-play 7.0.2 |

## 快速开始

前置只有 JDK 17+ 与 sbt；数据库不需要手工建，见下。

```bash
sbt "run 9000"                       # 开发服务器，监听 127.0.0.1:9000
curl --noproxy '*' -sS http://127.0.0.1:9000/healthz
# {"status":"ok","time":"..."}
```

启动时 `ecs.Bootstrap` 会以 eager singleton 执行 `conf/sql/sqlite_boot.sql`（`IF NOT EXISTS`
建表）与 `conf/sql/sqlite_seed.sql`（`INSERT OR IGNORE` 演示数据），所以**第一次请求前不需要
任何手工迁移**，重复启动也安全。开发库落在 `data/ecshop.sqlite3`（已被 `.gitignore` 忽略）。

Windows 上注意：`sbt "run 9000"` 还活着时另开终端跑 `sbt -batch compile/test`，会因为抢不到
sbt 的 named-pipe lock 而失败，需要先结束 9000 端口那棵进程树。

内置演示数据（验收脚本都以它为准）：商品 `12`（49.90，属性 1001/1005）、`14`（79.90）、
`13`（已下架）、`15`（促销 129.00 + 积分）；配送 `1`=8.00、`2`=15.00；支付 `1`=0.00、
`2`=2.00；红包序列号 `202609230001`；地区 `1 中国 → 2 北京市 → 52 东城区 → 500 东华门街道`。

## 验证

```bash
sbt -batch test                                  # 19 个用例，2 个 suite
bash scripts/curl_cart_example.sh                # 对着 live server 的购物车验收，18 项
```

- `ecs.ContractSpec` — 不碰数据库的契约单测：金额格式化、时间戳往返、分页边界、排序白名单、
  `Body` 字段校验、PBKDF2、回调 HMAC、token 唯一性、`Abort` 语义。
- `routes.ApiFlowSpec` — 全链路集成测试：在**临时 SQLite 文件**上启动整个应用，走
  注册 → 购物车 → 地址 → 报价（99.80 + 8.00 = 107.80）→ 下单 → 幂等重放 → 同 key 改内容冲突 →
  网关回调（含 401 签名错误与 409 金额不符）→ 确认收货 → logout 后 token 失效。
  回调签名在测试里用独立实现计算，避免"用自己的实现验自己"。
- `scripts/curl_cart_example.sh` 只读写购物车，不下单，因此可以反复跑；一旦把流程延伸到真实下单，
  就会消耗种子库存、唯一红包序列号 `202609230001` 和按 IP 记名的投票名额，重复验收前需要重置这三类状态。

CI 为 ubuntu / windows / macos × JDK 17 / 21 六组合（`fail-fast: false`），见
[.github/workflows/ci.yml](.github/workflows/ci.yml)。

## 目录结构

```
app/controllers/   30 个 controller，只做「解析请求 → 调仓储 → 映射状态码」
app/ecs/           35 个文件：*Repo 仓储 + Db/Sql 方言层 + Crypto/Auth/ApiError 契约件
conf/routes        102 条 URL
conf/sql/          sqlite_boot / sqlite_seed 与 mysql_boot / mysql_seed 两套对齐脚本
docs/              蓝图、URL 清单、API 契约、curl 手册、部署模板
scripts/           可执行验收脚本
third_party/       shopex-ecshop（PHP 参考源，CI 不 checkout）
test/              ContractSpec 与 ApiFlowSpec
```

## 接口面

| 分组 | 数量 | 覆盖范围 |
| --- | --- | --- |
| `/api/v1/me**` | 49 | 资料与密码、购物车、地址、余额与提现、红包、收藏、标签、预订、评论、留言、订单全套（含取消/收货/退回购物车/改支付方式/改地址/合并/余额抵扣）、团购、推广分成、浏览历史 |
| 商品读取与试算 | 5 | `goods` 列表、详情、价格试算、`compare`、`quotation` |
| 商品互动 | 4 | 评论读取/写入、商品标签、图集 |
| 促销与活动 | 11 | `promotions`、`activities`、`packages`、`topics`、`votes`、`exchange-goods`、`ads` |
| 认证与凭据恢复 | 8 | `auth` 注册/登录/登出/可用性探测、邮箱验证、密码重置 |
| 目录与内容 | 7 | `home`、`catalog`、分类商品、品牌列表与品牌商品、文章与分类文章 |
| 社区与辅助读取 | 9 | newsletter 订阅与退订及其确认、`messages`、`tags` 云、`shipping-options`、`regions` |
| 结算与下单 | 4 | `checkout/options`、`checkout/quote`、`POST /api/v1/orders`、`POST /api/v1/payments/{provider}/callback` |
| 非 `/api` 入口 | 5 | `/healthz`、`/sitemap.xml`、`/feed.xml`、`/goods-widget.js`、`/cycle-image.xml` |

合计 102 条，全部有实现：`grep -rn "notReady(" app/` 只剩 `ApiSupport.scala` 里那个没人调用的工厂。

## 契约要点（改代码前先读）

- **鉴权只有 Bearer**：`Authorization: Bearer <token>`，身份一律取自 token 背后的 session 行，
  绝不从 body/query 读用户 id；sessions 表只存 token 的 SHA-256，明文只在注册/登录响应里出现一次。
- **错误体统一**：`{code,message,request_id,details?}`；`X-Request-Id` 由调用方提供时会被复用，
  便于日志与响应互联。500 响应不含 SQL 与堆栈。
- **金额是两位小数字符串**，内部一律按整数分计算；库存扣减用条件 `UPDATE ... WHERE goods_number >= ?`，
  购物车与订单写操作都在事务内并带版本/归属校验。
- **下单必须带 `Idempotency-Key`**（≤100 字符，否则 400）；同 key 同内容重放返回 200 且
  `replayed=true`，同 key 改内容返回 409。
- **回调验签**：对**原始请求体字节**做 HMAC-SHA256，放在 `X-Payment-Signature`；密钥来自
  `payment.callback_hmac_secret`，生产用 `PAYMENT_CALLBACK_SECRET` 注入（`conf/application.conf`
  里那个值只是开发占位）。回调 payload 永远不写日志。
- **SQL 只走占位符**；`ORDER BY` 与方言片段只能来自白名单常量。
- 投票的记名依据是 socket 远端地址，不采信 `X-Forwarded-For`，所以伪造头刷不出第二票。
- 明确**不复刻**的历史包袱：`affiche.php?act=js` 输出、`type=collection&u=<任意用户 id>` 的老
  widget 模式、MyISAM/MD5 密码/拼接 SQL。

## 换到 MySQL

```bash
export DB_DRIVER=com.mysql.cj.jdbc.Driver
export DB_URL="jdbc:mysql://127.0.0.1:3306/ecshop?useSSL=false&serverTimezone=UTC&characterEncoding=utf8"
export DB_USER=... DB_PASSWORD=...
sbt "run 9000"
```

方言由 `db.default.url` 前缀判定，`Sql` 负责 `FOR UPDATE` / `INSERT IGNORE` / `ON DUPLICATE KEY`
与 `ON CONFLICT` 的差别；启动脚本换成 `conf/sql/mysql_boot.sql` 与 `mysql_seed.sql`，与 SQLite
版逐行对齐。

## 已验证与未验证

已实测且可复现：`sbt -batch test` 19/19；`bash scripts/curl_cart_example.sh` 18/18；
CI 六条腿全绿（ubuntu 与 windows 的 job 日志里都有 `All tests passed`）。此外开发过程中对
全部 102 条 URL 逐条跑过临时无代理 curl 验收（145 项与 72 项两轮，均 0 失败），但那两组脚本
依赖临时工具，没有作为仓库文件提供——仓库里能一键复跑的是上面两条。

未实测：MySQL 方言分支。本机没有 MySQL 服务，`mysql_boot.sql` 与 MySQL 侧 `Sql` 片段只经过编译和
代码审查，CI 也只跑 SQLite。要在真库上验，按上面环境变量起服务后再跑同一套 curl 脚本。

## 文档索引

- [docs/02_url_api_list.md](docs/02_url_api_list.md)：PHP 入口 → REST URL 对应表
- [docs/03_api_contract.md](docs/03_api_contract.md)：请求/响应/状态码/鉴权约定
- [docs/04_architecture.md](docs/04_architecture.md)：分层与模块边界
- [docs/05_detailed_design.md](docs/05_detailed_design.md)：从路由到 SQL 的实现细节
- [docs/06_curl_testing.md](docs/06_curl_testing.md)：可复制的 HTTP 验证流程（文末有本实现的实测补记）
- [docs/09_url_completion_matrix.md](docs/09_url_completion_matrix.md)：PHP 能力覆盖审计清单
- [docs/sql/README.md](docs/sql/README.md)：原始 88 张表与导入次序
- [docs/deploy/README.md](docs/deploy/README.md)：Docker / Nginx / systemd 部署模板
- [docs/11_scala_play_architecture.md](docs/11_scala_play_architecture.md)：按当前代码画的分层图、下单/回调时序图与订单状态机
