# 工程规范

本页按接口、身份权限、异常日志、测试四部分查阅。业务规则以[模块 Spec](specs/README.md)为准，持久化约束见[数据库设计](DATABASE.md)，部署和事务边界见[架构设计](ARCHITECTURE.md)。

## RESTful API 接口规范

### 通用契约

| 项目 | 约定 |
| --- | --- |
| 基础路径 | 浏览器同源访问 `/api/v1`。下表路径省略此前缀。跨进程接口仅由 Java 服务端调用，按对应 Spec 定义请求、响应和超时。 |
| 资源与动作 | `GET` 查资源，`POST` 创建资源或提交独立业务动作，`PATCH` 修改草案及允许部分更新的资料。实际领件、签收、更正和返站各有写入入口。 [HTTP 语义](https://www.rfc-editor.org/rfc/rfc9110.html) |
| 请求与响应 | 正常响应使用 JSON。详情返回对象，列表返回 `{items, page, pageSize, total}`。`page` 从 1 起，默认 20 条，上限 100 条，按业务时间及 ID 稳定排序。`total` 限当前权限和筛选条件。二进制凭证按专用上传入口提交。 |
| 字段类型 | JSON 字段用 `lowerCamelCase`。运单号和 `BIGINT` 业务标识用字符串，版本号同样用十进制字符串。时间用 ISO 8601 UTC 时间，班次日期用 `YYYY-MM-DD`。外部显示时间按业务单位时区转换。金额用带小数的字符串和币种。 |
| 成功状态 | 查询及产生结果的动作返回 `200`。创建可独立查询的资源返回 `201` 并给出 `Location`。注销和无响应体的更新返回 `204`。模拟验证码投递受理返回 `202`。各 Spec 锁定自己接口的状态码和响应字段。 |
| 幂等与追踪 | 扫码、发布、批签、投递、返站和计件等可重试写入带 `Idempotency-Key`。超时后复用原键及请求内容。服务端为每次 HTTP 请求返回 `X-Request-Id`，用作日志关联。 |
| 版本与竞争 | 调整草案、发布、批签、改派和状态更正提交 `expectedVersion` 或已观察的逐票版本。事务内重新核对当前版本、权限、持有人和授权。选中的多票作为一次原子提交，变更冲突返回 `409`。 |
| 演进 | 已有请求字段及响应的业务含义保持兼容。会破坏现有调用的变更通过版本演进。每份 Spec 实现前锁定 DTO、字段必填条件、枚举、样例及错误码。 |

重试时，先校验当前身份及访问该操作回执的权限，再按操作者、动作和幂等键读取已提交回执。相同请求重放原业务结果和状态码；改变语义的请求使用新键。键相同、内容不同时返回 `IDEMPOTENCY_KEY_REUSED`。同一请求的校验结果未知时可查回执或重试，回执尚未找到时页面继续显示「结果待确认」。 [操作回执表](DATABASE.md#计划现行授权趟次与交接)

### 核心接口目录

操作账号、执行单位和角色由服务端会话解析。表内出现的 `*Id` 用于指定目标资源。以 `GET /operations/{command}/{operationKey}` 查询本人的操作回执，敏感的登录、验证码和客服请求使用各自的校验与重试策略。

| 模块 | 方法与路径 | 主要输入与可核对结果 |
| --- | --- | --- |
| 身份 | `GET /auth/csrf`、`GET /auth/session` | 前者返回 CSRF 头名与令牌。后者返回当前身份类型、岗位、所属单位及改密状态。 |
| 身份 | `POST /employee-auth/login`、`POST /employee-auth/password-changes` | 登录名、密码；首登或重置后提交临时密码和新密码。成功后建立或更新员工会话。 |
| 身份 | `POST /recipient-auth/otp-challenges`、`POST /recipient-auth/login`、`POST /auth/logout` | 挑战、手机号与验证码校验、注销。验证码投递结果使用统一提示。 |
| 身份 | `GET /staff/accounts`、`POST /staff/accounts`、`POST /staff/accounts/{id}/password-resets`、`PATCH /staff/accounts/{id}` | 按管理人范围查账号、开户、重置、停用。停用或重置使旧会话失效。 |
| 网点与运力 | `GET /staff/areas`、`GET /staff/shifts`、`GET /staff/workers` | 读取片区、责任人和指定班次的在岗人员。管理工作台按所属单位新增或更新具体资源。 |
| 入站 | `POST /staff/inbound-batches`、`GET /staff/inbound-batches/{batchId}` | 导入可信演示清单。详情分列应到、有效实收、异常及核对后未收。 |
| 入站 | `POST /staff/inbound-batches/{batchId}/receipts`、`GET /staff/inbound-receipts/{receiptId}` | 提交 `waybillNo`。返回 `RECEIVED`、`PENDING_AREA` 或 `ALREADY_RECEIVED`、片区与实收时间，能在同一页继续扫码。详情可核对该次实收及作废状态。 |
| 入站 | `POST /staff/parcels/{parcelId}/area-assignments`、`POST /staff/inbound-receipts/{receiptId}/voids`、`GET /staff/me/sort-credits` | 补分提交片区；更正或作废提交理由和版本。本人计件按业务日期查询。 |
| 内部查件 | `GET /staff/parcels?waybillNo=…`、`GET /staff/parcels/{parcelId}` | 在授权单位内按完整运单号查件。明细展示状态、当前保管和有权看到的履约时间线。 |
| 计划 | `POST /staff/delivery-plans`、`GET /staff/delivery-plans/{planId}`、`PATCH /staff/delivery-plans/{planId}` | 选批次和班次生成草案，查询分组或显式调整受影响条目。草案保存拟领件人及版本。 |
| 计划 | `POST /staff/delivery-plans/{planId}/releases`、`POST /staff/delivery-plans/{planId}/revocations` | 提交明确选中的运单、草案和包裹版本。发布或撤销返回当前授权结果，已领件转实际交接流程。 |
| 配送 | `GET /staff/me/pickup-items`、`GET /staff/me/delivery-trips/current` | 查本人候领和当前趟次。网点直派与驿站上门共用入口，根据账号单位及授权过滤数据。 |
| 配送 | `POST /staff/me/pickups`、`POST /staff/delivery-trips/{tripId}/load-returns` | 连续领件提交运单号与已见授权版本，首件成功时返回新趟次。复扫回库返回实领数和实物状态。 |
| 配送 | `POST /staff/parcels/{parcelId}/delivery-attempts`、`POST /staff/parcels/{parcelId}/delivery-results`、`POST /staff/parcels/{parcelId}/station-returns` | 登记实际尝试、交付结果及出发单位扫码收回。失败待返站保留原持有人。结果更正由授权岗位追加记录。 |
| 配送 | `POST /staff/courier-transfers`、`POST /staff/courier-transfers/{id}/handoffs`、`POST /staff/courier-transfers/{id}/receipts`、`POST /staff/delivery-trips/{tripId}/closures` | 调度确认转交方案，原配送员确认交出，接手人扫码接收。关趟返回未结清运单或已结清结果。 |
| 分拨 | `POST /staff/transfer-batches`、`GET /staff/transfer-batches/{batchId}`、`POST /staff/transfer-batches/{batchId}/dispatches` | 目标驿站、逐票清单和司机指派；实际装车提交实发清单及外观记录。详情分别展示应收、实发、实收及差异。 |
| 分拨 | `POST /staff/transfer-batches/{batchId}/acceptances`、`POST /staff/transfer-batches/{batchId}/return-receipts` | 站长提交正常批签和逐票差异。同批未签收实物实际回网点后由网点确认收回。 |
| 驿站 | `POST /staff/pickup-stations/{stationId}/shelvings`、`POST /staff/pickup-stations/{stationId}/pickup-confirmations` | 已实收件提交 `waybillNo` 与货架信息。备好后具备自取条件可显示待领取，实际核销后才显示已领取。 |
| 驿站 | `POST /staff/home-delivery-services`、`POST /staff/home-delivery-services/{id}/assignments`、`POST /staff/home-delivery-services/{id}/redelivery-consents`、`GET /staff/me/home-delivery-credits` | 站长记录来电约定、分配本站配送员、登记收费续派同意。配送员查实际完成计件。 |
| 异常 | `GET /staff/parcel-exceptions`、`POST /staff/parcel-exceptions`、`GET /staff/parcel-exceptions/{id}`、`POST /staff/parcel-exceptions/{id}/actions` | 按角色及异常阶段查询。登记时可用临时编号，跟进提交操作类型、当前版本和所需证明。找回和赔付分别按履约权限与异常状态校验。 |
| 在途 | `POST /staff/delivery-trips/{tripId}/positions`、`POST /staff/delivery-trips/{tripId}/stop-orders`、`GET /staff/parcels/{parcelId}/route-estimate` | 上报带采集时间、坐标系和精度的趟次最近位置，提交确认站序。内部按票读取有依据的剩余站数、道路数据和预计时段。 |
| 收件人 | `GET /me/parcels`、`GET /me/parcels/{parcelId}`、`POST /me/parcel-follows`、`GET /me/notices` | 本人列表、授权详情、按运单号关注和站内消息。展示实际履约及可用预计时段。其他收件人的运单与不存在的运单对调用方返回同一结果。 |
| AI 客服 | `POST /me/parcels/{parcelId}/support-messages` | 在本票会话提交问题。仅返回授权事实、可用核实摘要及对应联系入口。客服故障保留已知事实和人工入口。 |

### 接口样例

网点操作员连续扫码时，先提交 `POST /api/v1/staff/inbound-batches/18/receipts`。授权身份从会话取得，请求头带 CSRF 令牌及本次扫码的 `Idempotency-Key`。

```json
{
  "waybillNo": "000726050018"
}
```

第一次实收返回 `201`，`Location` 是 `/api/v1/staff/inbound-receipts/391`。重复提交同一键返回原业务结果。换键再扫已到站的件返回 `200` 和 `ALREADY_RECEIVED`，扫码页面接着收下一票。

```json
{
  "receiptId": "391",
  "parcelId": "218",
  "waybillNo": "000726050018",
  "outcome": "RECEIVED",
  "area": { "id": "2", "name": "东片区" },
  "receivedAt": "2026-11-25T01:24:00Z",
  "version": "1"
}
```

## 用户认证和权限设计

### 登录、会话和 CSRF

- 员工使用登录名与密码验证，数据库保存 BCrypt 哈希。首登或重置后，受限会话仅允许查询自身会话、改密及注销，改密成功重新建立完整会话。账号停用或重置更新 `sessionVersion`，旧会话在后续受保护请求中失效。 [员工账号 Spec](specs/identity/0001-station-accounts-and-access.md)
- 收件人通过已验证的手机号登录。验证码绑定规范化手机号、用途和挑战，设置有效期、发送频率及尝试上限。成功原子消费并建立会话。模拟发送只用于合成账号验收，验证码及登录凭证从业务响应和日志中排除。 [收件人账号 Spec](specs/identity/0002-recipient-accounts-and-access.md)
- 使用 Spring Security 服务端会话。会话识别为员工或收件人之一；登录或改密轮换会话 ID，注销即作废。Cookie 使用 `HttpOnly`、`SameSite=Lax`，HTTPS 部署启用 `Secure`。身份切换重新登录。并发角色测试使用独立浏览器会话。 [Spring Security 会话管理](https://docs.spring.io/spring-security/reference/servlet/authentication/session-management.html)
- 同源浏览器先读取 CSRF 令牌，修改请求带服务端返回的头名和值。登录、注销和改密后重新获取令牌。API 认证失败返回 JSON 错误，由前端决定是否回到自己的登录页。 [Spring Security CSRF](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html)

员工登录提交 `loginName`、`password`，首登改密提交 `temporaryPassword`、`newPassword`。登录、改密及员工会话查询使用相同响应，包含 `type=EMPLOYEE`、`employeeId`、`role`、`unitId`、`unitName`、`timeZone`、`networkId`、可空的 `workerId` 和 `mustChangePassword`。员工空闲超时配置 `delivery.employee.session-timeout`，默认 `30m`。同源 HTTPS 部署可通过 `server.servlet.session.cookie.secure=true` 显式启用安全 Cookie。

第一批员工查询开放 `GET /staff/areas` 和 `GET /staff/workers`。后者要求 ISO 8601 时点 `at`，两者均接受 `page`、`pageSize`。单位和岗位取自员工会话；操作员可查片区，在岗配送员查询仅向网点调度开放。

### 岗位与资源授权

先判断登录身份类型和岗位，再核对资源归属，写入前在业务事务里重查授权和实际状态。隐藏前端按钮只负责交互显示。用户填写的角色、网点、人员 ID 或运单号均由服务端校验。

| 身份 | 允许的业务操作与范围 |
| --- | --- |
| 网点操作员 | 本网点实收、分堆、补分、实际分拨出库与实际返站收回。可查本人分拣记录及本网点经手异常。 |
| 网点调度 | 管理本网点配置、当班人员、本网点角色账号、直派与分拨计划。可追查发往下属驿站的批次和差异，驿站实际验收与派工由该驿站办理。 |
| 驿站分拣员 | 只处理本站已签收件的上架、备货和异常反馈。备好且符合自取约定的件可显示待领取。批签、实际领取核销及改上门派工由站长办理。 |
| 网点配送员 | 只查看与扫码接手本人在所属网点的直派授权，登记实际持有件的交付、失败、回库或转交。 |
| 驿站配送员 | 共用配送工作台，只操作本站已分给本人的上门任务和本人持有的件，可查本人上门计件。 |
| 网点转运司机 | 本人获指派的网点分拨批次。确认实际接手和现场差异，查看驿站签收结果与异常实物回交。 |
| 驿站站长 | 本驿站批量签收、按实物确认领取和返站、管理本站分拣员及配送员、确认改上门和收费续派。 |
| 收件人 | 每次列表、详情、关注、提醒和客服均从有效会话读取已验证手机号，核对可信运单资料。客服只读取绑定运单的授权事实，业务变更走已有的运营入口。 |

两类分拣人员复用分拣工作台，两类配送员复用配送工作台。所属单位和业务权限由服务端决定。[岗位与组织实体](DATABASE.md#网点运力账号)

## 异常处理和日志规范

### 错误返回与前端动作

错误统一以 `application/problem+json` 返回。`type`、`title`、`status`、`detail` 按 Problem Details 定义。项目定义稳定的 `code` 和 `requestId`，参数异常可带 `errors`，批量并发冲突可带当前调用者有权查看的 `conflicts`。响应提供现场可执行的下一步，避免出现内部异常文本、堆栈和数据库语句。 [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457.html)

| HTTP | 项目错误码示例 | 页面处理 |
| --- | --- | --- |
| `400` | `INVALID_REQUEST` | 高亮可修正字段；处理字段格式、缺失及违反请求约束。 |
| `401` | `SESSION_REQUIRED`、`SESSION_EXPIRED`、`INVALID_CREDENTIALS`、`INVALID_OTP` | 登录入口给统一验证失败提示。已登录页面的会话失效时，重新登录后读取业务数据。 |
| `403` | `FORBIDDEN`、`CSRF_INVALID`、`PASSWORD_CHANGE_REQUIRED` | 按类型提示无权执行、会话校验或改密。CSRF 更新后由用户确认原动作，幂等写入沿用原键。 |
| `404` | `RESOURCE_NOT_FOUND` | 资源不存在和资源超出访问范围使用统一提示。 |
| `409` | `VERSION_CONFLICT`、`PARCEL_STATE_CONFLICT`、`IDEMPOTENCY_KEY_REUSED` | 显示冲突票件和可重核范围。系统保留输入，等人工重新确认再发起新操作。 |
| `429` | `RATE_LIMITED` | 等待限流窗口，适用时读取 `Retry-After`。 |
| `500` / `503` | `INTERNAL_ERROR`、`DEPENDENCY_UNAVAILABLE` | 显示可提供给运维的请求号。写结果未知时先查回执，无法确认再用原键重试。 |

核心数据读取成功时保留页面可用性。最近位置过期、地图算路失败或估时服务超时时，查件保留已提交的进度，受影响的指标返回空值及原因。已提交的投递事实与外部消息或客服调用分别处理。重复入站、找不到片区、装不下回库及联系不上等场景使用各自业务结果或状态。只有事务成功提交后，页面才显示实际出库、交接、签收或计件成功。

```json
{
  "type": "urn:delivery:problem:parcel-state-conflict",
  "title": "包裹状态已变化",
  "status": 409,
  "detail": "有运单刚被扫码领走，本次批量改派未提交。请复核剩余可改派运单。",
  "code": "PARCEL_STATE_CONFLICT",
  "requestId": "01JDD09QV8Y9STXG4BP8CG41DZ",
  "conflicts": [{ "parcelId": "218", "reason": "ALREADY_PICKED_UP" }]
}
```

### 日志与审计

| 层级 | 记录内容 |
| --- | --- |
| HTTP 和应用日志 | 结构化记录 `timestamp`、`level`、`requestId`、`module`、`route`（路径模板）、`method`、`status`、`durationMs`、`errorCode`。适用时附内部账号 ID、单位 ID 和业务 ID。异常可按请求号关联。 |
| Java 到外部服务 | 传递请求号，记录依赖名、耗时、失败类型及降级结果。地图、模型、短信等调用的请求或响应正文默认排除。 |
| 业务审计 | 包裹的入站、授权变更、转交、返站、投递、更正及计件走同事务的持久化事件。记录操作人、时间、受影响对象及必要前后状态。账号重置和停用记录操作者与目标。使用数据库审计追溯实际责任，应用日志用于排障。 |
| 分级与数据范围 | 正常完成及预期业务冲突用 `INFO`，依赖降级和需关注的失败用 `WARN`，未处理异常或数据一致性故障用 `ERROR`。异常只在统一边界记录一次。密码、验证码、Cookie、令牌、完整手机号、详细地址、取件码、原始凭证和客服正文从应用日志中排除。 |

## 测试策略

每个 Spec 的测试围绕「谁执行什么，预期看见什么，落库后还剩什么」设计。先运行会失败的固定场景，再实现并跑回归。对外接口测试同时验证 HTTP 响应和持久化结果。验收证据随 Spec 或 Ticket 记录。 [协作规则](../AGENTS.md)

| 层级 | 验什么 | 工具及方法 |
| --- | --- | --- |
| 单元测试 | 自动分区、状态流转、免费次数、估时输入和响应映射。 | JUnit，时间和外部接口使用可控输入。覆盖正常、边界和拒绝路径。 |
| HTTP 契约 | 会话类型、资源归属、CSRF、请求字段、状态码、幂等重试和 Problem Details。 | Spring Boot 测试及 MockMvc，从实际入口发请求。定点故障用可控替身验证返回与状态。 |
| MySQL 集成 | 唯一键、行锁、库存与计件事务、Flyway 空库和有数据升级。 | MySQL 8.4 Testcontainers，固定镜像版本。并发使用独立事务和可控同步点，核对授权、保管、事件、计件和回执。 [MySQL Testcontainers](https://java.testcontainers.org/modules/databases/mysql/) |
| 跨进程与缓存 | Redis 位置过期或乱序、路线失败、FastAPI 超时、模型不可用。 | TTL 和原子更新用独立 Redis 验证。地图、短信和模型可用固定响应。Java 与 FastAPI 同时联调正常及失败响应。 |
| 前端端到端 | 角色入口、跨角色结果一致、扫码枪回车后缀、焦点、前导零、连扫与弱网重试。 | 先用模拟数据跑页面，再用合成账号和独立测试库跑实际 HTTP。浏览器验证操作反馈，数据库核对事件数量和实际状态。 |

### 必验业务场景

| 场景 | 验收证据 |
| --- | --- |
| 甲乙账号及两类员工会话 | 用两个单位、两名配送员及两名收件人交叉查件和扫码。核对权限拒绝及状态保持；停用、重置或注销后的旧会话失败。 |
| 10 次连续输入，包含重复运单和待分区 | 始终保持扫码就绪。正常件正确分堆，重复件只产生一笔有效实收及计件，待分区保留实收。 |
| 改派确认与原配送员扫码并发 | 分别运行两种先提交顺序，核对选中范围的原子性、当前授权和实际保管人。 |
| 领件超时重试与复扫回库 | 同键只确认一次，换键重扫按现状响应；装车回库一次后实领减一，并保留交接历史。 |
| 转运实发 20 件、驿站正常批签 18 件 | 破损与缺件可逐票追溯，破损回交后才更新实际保管。两票差异不入驿站正常库存。 |
| 驿站自取与来电改上门竞争，及已分配上门件的计件 | 两者互斥，站长与分拣员权限分开。实际送达最多产生一笔计件，注入计件写入失败时交付状态及事件一起回滚。 |
| 投递失败、实际返站及找回 | 失败待返站由配送员保管，实际收回才转移。遗失超时和找回并发只提交符合当前状态的结果。 |
| 收件人查件、定位过期或客服超时 | 本人能查看真实进度，同一票与内部履约一致。失效估时不展示数值，未知事实用人工联系入口承接。 |

任务单记录环境、合成数据、测试命令、执行数和实际结果，并分别填自动验证、本人实测和待验证项。空库、迁移、并发及真实依赖测试的通过状态以执行记录为准。合并评审使用[交付评审规则](../.agents/skills/review-delivery/SKILL.md)。
