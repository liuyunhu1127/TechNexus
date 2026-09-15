# TechNexus V1 API 设计规范

文档编号：`DES-API-001`  
版本：`v1.0.1`  
状态：`已基线化`  
API 负责人：`技术负责人`  
OpenAPI 版本：`3.1.0`  
服务名称：`technexus-server`

## 1 API 目标与边界

- 消费者：`technexus-web`、`technexus-admin`；不承诺 V1 第三方开放 API。
- 业务能力：认证、内容、需求/方案/闭环、审核、定价、文件、搜索、通知、运营配置和 AI 建议。
- 不支持：支付/订单/权益、视频上传、复杂 IM、客户端直接访问数据库或对象 Key。
- 契约文件：`docs/technexus-solution-closure/02-设计阶段/openapi.yaml`。

本文定义语义和策略；同目录 OpenAPI 是机器契约。路径、认证、枚举、错误与本文不一致视为设计缺陷。

### 1.1 接口所有权命名

| 路径前缀 | 所有工程/模块 | Java 根包 |
|---|---|---|
| `/auth` | `technexus-auth` | `com.technexus.auth` |
| `/me` | `technexus-user` | `com.technexus.user` |
| `/contents` | `technexus-content`；评论命令委派 `technexus-community` | `com.technexus.content` / `com.technexus.community` |
| `/demands`、`/lineages` | `technexus-demand` | `com.technexus.demand` |
| `/files` | `technexus-file` | `com.technexus.file` |
| `/search`、`/notifications` | `technexus-community` | `com.technexus.community` |
| `/admin/audit-tasks` | `technexus-audit` | `com.technexus.audit` |
| `/admin/prices` | `technexus-pricing` | `com.technexus.pricing` |
| `/admin/config`、`/admin/dashboard`、`/admin/ai-suggestions` | `technexus-server` 子包 | `com.technexus.server.config`、`com.technexus.server.operations`、`com.technexus.server.ai` |

HTTP 控制器由 `technexus-server` 装配，但业务操作委派给上述模块公开 `api`；不得另建用户图片清单之外的同级工程。

## 2 基础约定

| 项目 | 约定 |
|---|---|
| Base URL | `/api/v1`；反向代理负责域名与 HTTPS |
| 数据格式 | `application/json`；错误为 `application/problem+json` |
| 时间 | RFC 3339 UTC，例如 `2026-09-14T08:00:00Z` |
| ID | 小写 UUID 字符串；不暴露 bigint 主键 |
| 字符编码 | UTF-8 |
| 关联 ID | 请求可传 `X-Request-Id`，服务返回 `X-Trace-Id` |
| 幂等键 | `Idempotency-Key`，8..128 字符，写入结果保留 24h |
| 乐观锁 | PATCH/状态命令请求体传 `expectedVersion`，冲突返回 409 |

## 3 认证与授权

| 调用方 | 认证 | 授权模型 | Scope/角色 | 生命周期 |
|---|---|---|---|---|
| 游客 | 无 | 公共资源过滤 | public | 不适用 |
| 用户/服务者 | Bearer Access JWT | RBAC + Resource/Action/Scope + 参与关系 | self/participant/public | Access 默认 15m |
| Web 刷新 | HttpOnly Secure SameSite Cookie | 会话/账号状态 | session | Refresh 默认 30d、单次轮换 |
| 审核/运营/管理员 | Bearer JWT + 显式权限 | RBAC + 对象/分配范围 | assigned/operations/admin | 高风险另需 5m 确认令牌 |

刷新与退出使用 Cookie 时必须校验受信 Origin 和 CSRF Token。未认证返回 401；已认证但无功能权限返回 403；对象存在性敏感时统一 404。

## 4 资源和命名规范

- 路径用复数 kebab-case，字段用 camelCase，枚举使用大写下划线。
- 金额是 `{amount: string, currency: "CNY"}`，禁止浮点数；数据库为 decimal(12,2)。
- 列表响应 `{items, nextCursor}`；`pageSize` 默认 20、最大 50。管理端必要时可用 page/size，但最大深度 10,000 行。
- PATCH 仅允许契约列出的字段；`null` 与缺失含义不同。未知字段拒绝。
- 列表和详情先按可见性/对象关系过滤，再序列化；联系方式不进入通用 Demand Schema。

## 5 接口清单

| 编号 | 方法 | 路径 | 目的 | 权限 | 幂等 | 需求 |
|---|---|---|---|---|---|---|
| API-AUTH-001 | POST | `/auth/register` | 注册 | public | 键 | FR-ACC-01 |
| API-AUTH-002 | POST | `/auth/login` | 登录 | public+限流 | 否 | FR-ACC-01 |
| API-AUTH-003 | POST | `/auth/refresh` | 轮换刷新 | cookie+CSRF | 一次性 | FR-ACC-01/03 |
| API-AUTH-004 | POST | `/auth/logout` | 撤销会话 | user | 键 | FR-ACC-03 |
| API-ME-001 | GET/PATCH | `/me` | 读取/更新自己 | user:self | PATCH 键 | FR-ACC-02 |
| API-CNT-001 | GET/POST | `/contents` | 列表/创建 | public / user | POST 键 | FR-CNT-01 |
| API-CNT-002 | GET/PATCH | `/contents/{contentId}` | 详情/修改 | 可见 / owner | PATCH 键+版本 | FR-CNT-01/06 |
| API-CNT-003 | POST | `/contents/{contentId}/submit` | 提交审核 | owner | 键+版本 | FR-CNT-06/07 |
| API-CNT-004 | POST | `/contents/{contentId}/comments` | 评论 | user | 键 | FR-CNT-03 |
| API-LOOP-001 | POST | `/contents/{contentId}/demands` | 从问题创建需求 | owner/authorized | 键 | FR-LOOP-01 |
| API-DMD-001 | GET/POST | `/demands` | 列表/创建 | 按可见性 / user | POST 键 | FR-DMD-01/07 |
| API-DMD-002 | GET/PATCH | `/demands/{demandId}` | 详情/修改 | 关系策略 / owner | PATCH 键+版本 | FR-DMD-01/07 |
| API-DMD-003 | POST | `/demands/{demandId}/submit` | 提交审核 | owner | 键+版本 | FR-DMD-02 |
| API-DMD-004 | POST | `/demands/{demandId}/proposals` | 提交方案 | provider/visible | 键 | FR-DMD-03 |
| API-DMD-005 | POST | `/demands/{demandId}/transitions` | 业务状态转换 | 按动作 | 键+版本 | FR-DMD-04/06 |
| API-LOOP-002 | GET | `/lineages/{targetType}/{targetId}` | 查询完整来源链 | 逐节点可见 | 否 | FR-LOOP-02/04 |
| API-FILE-001 | POST | `/files/uploads` | 创建直传会话 | user | 键 | FR-FILE-01/02 |
| API-FILE-002 | POST | `/files/uploads/{uploadId}/complete` | 完成并触发扫描 | owner | 键 | FR-FILE-03 |
| API-FILE-003 | POST | `/files/{fileId}/download-url` | 获取短期下载 URL | relation policy | 键 | FR-FILE-05/06 |
| API-SCH-001 | GET | `/search` | 跨域公开搜索 | public | 否 | FR-SCH-01/02、RCM-01 |
| API-MSG-001 | GET | `/notifications` | 通知列表 | user:self | 否 | FR-MSG-01 |
| API-MSG-002 | POST | `/notifications/{notificationId}/read` | 标记已读 | user:self | 键 | FR-MSG-01 |
| API-AUD-001 | GET | `/admin/audit-tasks` | 审核待办 | reviewer | 否 | FR-AUD-01 |
| API-AUD-002 | POST | `/admin/audit-tasks/{taskId}/decisions` | 人工决定 | reviewer:assigned | 键+版本 | FR-AUD-02~05 |
| API-PRC-001 | POST | `/admin/prices/{targetType}/{targetId}/confirmations` | 确认价格 | pricing:manage | 键+确认令牌 | FR-PRC-01~06 |
| API-OPS-001 | GET | `/admin/dashboard` | 核心待办摘要 | operations | 否 | FR-AIOPS-03 |
| API-OPS-002 | GET/PUT | `/admin/config/{key}` | 参数读取/更新 | config:manage | PUT 键+确认令牌 | FR-CFG-01/02 |
| API-AI-001 | POST | `/admin/ai-suggestions` | 创建建议任务 | operations | 键 | FR-AIOPS-01/04 |
| API-AI-002 | POST | `/admin/ai-suggestions/{suggestionId}/decisions` | 采纳/编辑/拒绝 | operations | 键+版本 | FR-AIOPS-01/04 |

## 6 单接口契约

### 6.1 API-CNT-003 提交内容审核

- 前置：主体为 owner；内容处于 DRAFT 或已发布内容的新 DRAFT 修订；附件 AVAILABLE 且关系有权。
- 请求：`{expectedVersion: 3}`，头含 Bearer 与 Idempotency-Key。
- 成功：`202 {data:{contentId,state:"PENDING_REVIEW",version:4,auditTaskId}}`。
- 错误：`CONTENT_STATE_INVALID` 409、`VERSION_CONFLICT` 409、`FILE_UNAVAILABLE` 422、`ACCESS_DENIED` 404。
- 幂等：同主体/路径/键/请求 Hash 返回原 202；不同 Hash 返回 `IDEMPOTENCY_CONFLICT`。
- 审计：记录 actor、content、targetVersion、trace；不记录正文。

### 6.2 API-DMD-005 需求状态转换

- 请求：`{action:"CONFIRM_PROPOSAL", proposalId, expectedVersion}`。
- 授权：不同 action 绑定 owner/provider/system/admin 策略；客户端不能声明角色。
- 成功：`200 {data:{demandId,state:"CONFIRMED",version:8}}`。
- 过期竞态：到期作业与确认同时更新时只有一个 version 条件成功，另一方返回 409 并读取最新状态。
- 取消：CONFIRMED 之后只有显式高权限/业务规则允许，必须带 reason 并审计。

### 6.3 API-AUD-002 审核决定

- 请求：`{decision:"REJECTED", reasonCode:"INCOMPLETE", note:"...", expectedTaskVersion:2}`。
- 规则：OTHER 必须有 note；只能处理 assigned/授权任务；决定绑定 immutable targetVersionId。
- 成功：`200` 返回任务状态；目标模块若版本失配，整体返回 `AUDIT_TARGET_VERSION_CONFLICT`，不得将任务伪标成功。
- 高风险：AI suggestionId 可作证据但不能替代 decision；决策者必须是人类账号。

### 6.4 API-FILE-001/002/003 文件三段式

1. 创建：提交文件名、大小、声明 MIME、SHA-256、用途；服务校验上限并生成固定 Key/15 分钟上传 URL。
2. 完成：只接受会话 ID，服务端 HEAD 后进入 SCANNING；客户端不可直接设 AVAILABLE。
3. 下载：提交 relation 对应的 fileId，服务端重新校验主体、业务可见性和 AVAILABLE，返回 5 分钟 URL；不返回 objectKey。

### 6.5 API-PRC-001 最终定价

- 请求：Money、mode、validFrom、reason、expectedVersion；必须带绑定该动作与目标的 `X-Confirmation-Token`。
- V1：`mode` 仅允许 FREE；PAID/PREVIEW/ATTACHMENT_PAID 返回 `FEATURE_DISABLED`，但数据库保留枚举。
- 成功事务追加 price_history 后更新当前价并发布事件；重复键返回同一结果。

## 7 数据 Schema

| Schema | 关键字段 | 必填 | 约束 | 敏感性 |
|---|---|---|---|---|
| Content | id,type,state,visibility,title,summary,publishedAt,version | 是 | state 与 DB 枚举一致 | 公开/按可见性 |
| Demand | id,state,title,description,budget,deadlineAt,visibility,version | 是 | 联系方式不在通用响应 | 受限 |
| Proposal | id,demandId,provider,plan,techStack,periodDays,quote,state | 是 | 仅参与者可读完整方案 | 受限 |
| FileUpload | id,state,uploadUrl,expiresAt | 是 | URL 仅创建时返回 | 机密 URL |
| AuditDecision | decision,reasonCode,note,expectedTaskVersion | 是 | OTHER→note 必填 | 受限 |
| Money | amount,currency | 是 | decimal string；currency=CNY | 受限 |
| Problem | type,title,status,code,detail,traceId,violations | 是 | RFC 7807 扩展 | 脱敏 |

## 8 错误模型

| 错误码 | HTTP | 场景 | 可重试 | 客户端动作 | 日志 |
|---|---:|---|---|---|---|
| VALIDATION_FAILED | 400 | 字段/枚举/未知字段错误 | 否 | 显示字段错误 | INFO |
| AUTH_REQUIRED/TOKEN_EXPIRED | 401 | 未认证/Access 到期 | 条件 | 刷新一次或登录 | INFO |
| ACCESS_DENIED | 403/404 | 无功能/对象权限 | 否 | 不重试 | WARN/审计 |
| RESOURCE_NOT_FOUND | 404 | 可公开的资源不存在 | 否 | 返回列表 | INFO |
| STATE_TRANSITION_INVALID | 409 | 非法状态动作 | 否 | 刷新状态 | WARN |
| VERSION_CONFLICT | 409 | 乐观锁冲突 | 否 | 获取最新并合并 | INFO |
| IDEMPOTENCY_CONFLICT | 409 | 同键不同请求 | 否 | 使用新键 | WARN |
| FEATURE_DISABLED | 409 | 功能开关关闭 | 否 | 隐藏入口 | INFO |
| RATE_LIMITED | 429 | 频率超限 | 是 | 按 Retry-After | WARN |
| FILE_UNAVAILABLE | 422 | 未扫描/阻断/过期 | 条件 | 等待或重传 | WARN |
| DEPENDENCY_UNAVAILABLE | 503 | DB/对象等依赖故障 | 是 | 指数退避 | ERROR |

## 9 分页、排序与过滤

- 用户信息流/搜索采用不透明 base64url 游标，内部含 `sort, lastValue, lastPublicId, filterHash, issuedAt` 并 HMAC 签名。
- 排序白名单：内容 `relevance/latest/hottest/favorites`（favorites V1 关闭）；需求 `relevance/latest/budget/deadline`。运营置顶优先级不可被用户排序绕过。
- 页大小 1..50；过滤变化后旧游标返回 400。排序必须以 publicId 作为最终稳定 tie-breaker。
- 管理端 page 默认 0、size≤100、`page*size≤10000`；更深查询要求游标或导出任务。

## 10 超时、重试与限流

| 场景 | 服务端超时 | 重试条件 | 最大 | 退避 | 限流 |
|---|---:|---|---:|---|---|
| 普通 API | 3s | 仅幂等且网关未收到响应 | 2 | 100/300ms | user+IP |
| 登录 | 2s | 客户端不自动 | 0 | 不适用 | 账号+IP 渐进 |
| 对象签名 | 2s | 5xx | 2 | 指数 | user 30/min |
| AI 创建 | 2s 接受，后台 30s | 后台超时/5xx | 3 | 指数+抖动 | user 5/min |
| 批量管理 | 10s、≤100 项 | 未处理子项 | 1 | 1s | role+action |

429 必须返回 `Retry-After`；客户端不得对 400/401/403/404/409 无脑重试。

## 11 异步任务、事件与回调

- 长任务创建返回 202 与资源 ID，客户端 GET 资源状态或使用通知，不提供 V1 外部 Webhook。
- 事件 envelope：`eventId,eventType,schemaVersion,aggregateType,aggregateId,occurredAt,traceId,data`。
- 至少一次投递；消费者以 eventId 去重。schemaVersion 只增加，不原地改变字段语义。
- AI/扫描/Outbox FAILED 进入管理端待办；人工重放生成审计，不能跳过授权或安全校验。

## 12 版本与兼容性

- URI major 版本 `/v1`；向后兼容地新增可选字段或枚举需消费者容错验证。
- 删除/重命名/收紧约束属于破坏性变更，必须新 major 或至少两个发布版本的弃用窗口。
- 错误 code 稳定，detail 可变且不可被客户端作为分支条件。
- OpenAPI 与实现 PR 同步；破坏性差异在 CI 阻断。

## 13 安全检查

- 每个路径明确定义 public 或 Bearer；后台路径同时要求权限，不因 admin 前缀自动授权。
- 所有 `{id}` 执行对象级授权；敏感对象无权时使用 404 防枚举。
- 请求严格 Schema、服务端富文本净化、SQL 参数化；返回 DTO 不直接序列化 ORM 实体。
- 管理高风险操作二次确认；Refresh Cookie 接口校验 CSRF/Origin；上传下载使用最短必要签名。
- 限流按账号、IP、动作组合；审计记录 trace/主体/目标/结果但脱敏。

## 14 契约验证

| 验证项 | 工具/方法 | 环境 | 通过条件 | 证据 |
|---|---|---|---|---|
| YAML 解析/OpenAPI 根结构 | PyYAML + 自定义检查 | 本阶段 | 无解析错误；3.1.x；paths/operations/schema 存在 | 02 评审报告 |
| operationId/路径唯一 | 自定义脚本 | 本阶段/CI | 无重复、无空操作 | 02 评审报告 |
| 文档路径同步 | 文本集合对照 | 本阶段/CI | 接口清单路径均在契约内 | 02 评审报告 |
| 实现一致性 | Spring MockMvc + schema validator | 03/04 | 请求/响应/错误符合契约 | 测试报告 |
| 破坏性差异 | OpenAPI diff | CI | 未批准 breaking change 为 0 | CI 记录 |

## 15 变更记录

| API | 版本 | 变更 | 兼容性 | 需求 |
|---|---|---|---|---|
| 全部 | v0.1 | 初始 HTTP 契约设计 | 初始 | REQ-SRS-001 v1.0 |
| 全部 | v0.2 | 服务、消费者和接口所有权统一为指定 `technexus-*` 工程及 `com.technexus.*` 包名 | 兼容 | DES-USER-002 |
| 全部 | v1.0 | 用户通过第 3 轮设计门禁，无修改基线化 | 兼容 | 阶段通过 |
| 提交审核响应 | v1.0.1 | 按 6.1 已批准语义，将可选 `auditTaskId` 补入 Content/Demand 机器 Schema | 向后兼容勘误 | DEV-ISSUE-010 |
