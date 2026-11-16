# TechNexus 剩余工作项与 Multica 迁移清单

文档编号：`TST-BACKLOG-001`  
版本：`v0.2`  
更新时间：2026-10-28  
适用基线：`chore/phase-04-testing` 阶段四整改提交  
当前阶段：`04 测试阶段 / 第 2 轮整改中`

配套 Agent 执行提示词：`Multica-Agent-迁移提示词.md`

## 1. 当前可交接状态

- 后端保持 Java 21、Spring Boot 4.0、DDD 模块化单体；模块、`com.technexus` 包名和 `tn_` 表名约束不变；
- 本地 MySQL 8.4.13 的 `technexus_it` 已位于 Flyway V14，共 40 张表，业务表继续使用 `tn_` snake_case 命名；
- 20 个 server 测试类 51/51 通过、0 跳过；同 Hash 持有证明、独立 File Relation 和第三用户 IDOR 已关闭；
- `TNX-GATE-001` 已完成：Maven 11 模块、全仓 Java 99/99、双前端 lint/typecheck/build、覆盖率、SBOM/OSV、npm audit 和 Gitleaks 均通过；
- `TST-RISK-001/003`、`RTM-GAP-001/002` 已关闭；阶段四仍因 `TST-RISK-002/004` 和 `RTM-GAP-003~006` 不满足准出；
- 数据库凭据仅保存在开发机 `D:\DevelopTools\mysql-8.4.13-winx64\credentials.txt`。迁移到 Multica 时只登记变量名，不上传该文件或任何值。

## 2. Multica 字段约定

迁移时建议保留以下通用字段；若 Multica 字段名不同，只做字段映射，不修改本清单中的稳定编号。

| 本清单字段 | 用途 | 建议值 |
|---|---|---|
| ID | 跨系统稳定标识 | `TNX-*`，不得迁移后重编号 |
| 类型 | 工作层级 | Epic、Task、Milestone |
| 优先级 | 排序和门禁 | P0、P1、P2 |
| 状态 | 当前执行态 | READY、IN_PROGRESS、TODO、BLOCKED_EXTERNAL、GATED、DEFERRED、DONE |
| 负责人角色 | 不绑定个人账号 | Dev、QA、SRE、Security、Frontend、Product |
| 依赖 | 前置项 | 使用本清单 ID |
| 来源 | 需求/风险追溯 | TST-RISK、RTM-GAP、FR、NFR |
| 完成定义 | 关闭条件 | 可复核结果和证据位置 |

状态含义：`READY` 可在当前仓库继续；`IN_PROGRESS` 已开始但完成定义尚未全部满足；`TODO` 尚未开始；`BLOCKED_EXTERNAL` 需要账号、主机或外部服务；`GATED` 等待前置门禁；`DEFERRED` 已批准延期；`DONE` 已验证关闭。

## 3. 总清单

| ID | 工作项 | 类型 | 优先级 | 状态 | 负责人角色 | 前置依赖 | 来源 |
|---|---|---|---|---|---|---|---|
| TNX-MIG-001 | 建立 Multica 项目和导入追溯数据 | Task | P1 | BLOCKED_EXTERNAL | Product/Dev | Multica 工作区与权限 | 本文档 |
| TNX-GATE-001 | 恢复标准 Maven/Java 21 全量质量门禁 | Task | P0 | DONE | Dev/QA | 无 | 阶段四完整门禁 |
| TNX-GATE-002 | 真实 S3/MinIO + ClamAV 文件安全 E2E | Epic | P0 | DONE | Dev/QA/Security | 联调服务与凭据 | TST-RISK-003、RTM-GAP-002 |
| TNX-GATE-003 | 非同主机备份、告警与恢复演练 | Epic | P0 | BLOCKED_EXTERNAL | SRE/QA | 异地存储位置与权限 | TST-RISK-002、RTM-GAP-003 |
| TNX-FUNC-001 | 补齐用户端 V1 核心操作页面 | Epic | P0 | DONE | Frontend/Dev/QA | API 基线 | RTM-GAP-004 |
| TNX-FUNC-002 | 补齐管理端 V1 运营页面 | Epic | P0 | DONE | Frontend/Dev/QA | API 基线 | RTM-GAP-004 |
| TNX-VAL-001 | 补齐业务闭环 API/浏览器自动化 | Epic | P0 | DONE | QA/Dev | TNX-FUNC-001、TNX-FUNC-002 | RTM-GAP-004 |
| TNX-GATE-004 | 候选服务器部署、容量和稳定性验证 | Epic | P0 | BLOCKED_EXTERNAL | SRE/QA | TNX-GATE-001、目标机权限 | TST-RISK-004、RTM-GAP-005 |
| TNX-GATE-005 | 浏览器兼容、移动端、a11y 与 SEO 专项 | Epic | P1 | BLOCKED_EXTERNAL | Frontend/QA | TNX-FUNC-001、目标 URL | TST-RISK-004、RTM-GAP-006 |
| TNX-GATE-006 | 日志、指标、Trace 和告警闭环 | Epic | P0 | BLOCKED_EXTERNAL | SRE/Dev/QA | 候选环境 | TST-RISK-004、RTM-GAP-006 |
| TNX-GATE-007 | HTTPS、端口暴露与基础渗透测试 | Epic | P0 | BLOCKED_EXTERNAL | Security/SRE/QA | 候选环境、域名/证书 | TST-RISK-004、RTM-GAP-006 |
| TNX-REV-001 | 阶段四第 2 轮正式复评 | Milestone | P0 | GATED | QA/Product | 所有 P0 阶段四项 | 工作流门禁 |
| TNX-REL-001 | 阶段五上线准备与执行 | Milestone | P1 | GATED | SRE/Product | TNX-REV-001 用户通过 | 05 上线阶段 |
| TNX-CLOSE-001 | 阶段六总体评审和项目收口 | Milestone | P1 | GATED | Product/Tech Lead | 已启用工程阶段完成 | 06 总体评审 |
| TNX-P1-001 | 已批准延期的 P1 产品能力 | Epic | P2 | DEFERRED | Product/Dev | V1 Alpha 门禁后重新准入 | RTM-GAP-007 |

## 4. 阶段四必关工作包

### TNX-GATE-001 恢复标准构建门禁

完成结果（2026-10-28）：

1. Maven 3.9.16 使用 Microsoft JDK 21.0.13 执行 `clean install`，11/11 模块成功；
2. 全仓 Java 37 个测试套件、99/99 通过、0 失败、0 错误、0 跳过；server 51/51，含 MySQL 8.4.13 集成 3/3；
3. Spotless、ArchUnit、OpenAPI 契约和数据库命名检查通过；总 Java 行覆盖率 78.6%（2246/2858），领域分支覆盖率 90.1%（209/232）；
4. Web 端 Nuxt 4.6.3/Vite 8.3.5 与 Admin Vite 7.3.7 的 lint、typecheck、build 均通过；两个生产依赖审计均无高危或严重项；
5. CycloneDX SBOM 130 个后端组件；OSV Scanner 2.7.0 扫描后端 130、Web 695、Admin 223 个包后无未处置问题；
6. Nuxt 官方公告已确认 4.6.2 修复 `GHSA-vjh5-4x84-xrqp`，项目固定 4.6.3 且启用 `router.options.sensitive=true`。针对 OSV 元数据误报建立到 2027-01-31 的可到期复核项；
7. `deepmerge` 固定到官方修复提交并通过原型污染 PoC；Gitleaks 扫描 41 个提交、约 3.46 MB，0 泄漏；CI 同步为 OSV 2.7.0 和统一配置。

完成定义：标准命令可重复执行，全部 required checks 成功；不能只用临时 JUnit 运行器替代 Maven 门禁。

### TNX-GATE-002 真实文件安全链

子任务：

1. 提供独立 MinIO/S3 Bucket、最小权限访问密钥和生命周期规则；
2. 部署 ClamAV daemon 并记录病毒库版本、更新时间和超时配置；
3. 验证正常文件的预签名上传、大小/MIME/Magic Number/Hash 校验、扫描、绑定和授权下载；
4. 使用安全测试样本验证恶意文件被隔离且不能绑定或下载；
5. 验证 Hash、大小、MIME、Magic Number 不一致时失败且不创建关系；
6. 复跑同 Hash 双用户持有证明、独立关系、第三用户 404 和单物理对象；
7. 验证对象存储/ClamAV 超时、不可用、重复回调和临时对象清理；
8. 保存 API 结果、服务日志、对象状态和数据库关系证据。

完成定义：`TC-FILE-004` 全部通过，`TST-RISK-003`、`RTM-GAP-002` 关闭。

完成结果（2026-10-28）：使用回环绑定的 SeaweedFS 4.51 S3 API、ClamAV 1.5.4 和 MySQL 8.4.13，完成干净文件、恶意签名、Magic/Hash/大小/MIME、同 Hash 双用户/第三用户 IDOR、幂等、过期清理及 S3/ClamAV 故障恢复；全量 Maven 104/104。证据为 `文件安全E2E证据-20261028.md` 和 `scripts/test-file-security-e2e.ps1`。

### TNX-GATE-003 异地备份与恢复

子任务：

1. 选定非同主机存储并建立加密、最小权限、保留和删除策略；
2. 自动执行 MySQL `--hex-blob` 全量备份，并按能力增加 binlog/增量保护；
3. 将备份复制到异地主机或对象存储，禁止只留在应用主机；
4. 增加成功、失败、过期和容量告警；
5. 从异地副本恢复到隔离数据库，核对最新 Flyway 版本（当前 V14）、40 张表及关键业务行；
6. 记录实际 RPO/RTO、执行人、时间、校验值和恢复步骤。

完成定义：异地副本和完整恢复均有证据，满足已确认 RPO/RTO，`TST-RISK-002`、`RTM-GAP-003` 关闭。

本机准备结果（2026-10-29）：已增加 `scripts/backup/` 下的备份、认证加密、健康检查和隔离恢复脚本，并完成真实 MySQL 8.4.13 非门禁演练；Flyway V12、37 表、关键行和 SHA-256 一致，本机 RTO 1.74 秒。证据见 `备份恢复本机演练证据-20261029.md`。由于当前没有真实非同主机目标和告警接收地址，输出明确为 `LOCAL_TEST_ONLY`、`gatingEvidence=false`，工作项保持 `BLOCKED_EXTERNAL`。

用户决定（2026-10-29）：当前没有多台机器，本项暂时遗留。该决定仅允许继续其他整改项，不等同于接受上线风险；`TST-RISK-002`、`RTM-GAP-003` 和禁止真实用户上线约束保持不变。

用户决定（2026-11-08）：当前轮次先跳过本项，并继续执行其他工作项。本项仍作为 P0 外部待办保留，状态为 `BLOCKED_EXTERNAL`，不从 `TNX-REV-001` 的前置依赖中移除；该决定不构成风险接受、门禁关闭或真实用户上线授权。获得非同主机备份目标后，应优先恢复正式执行。

### TNX-FUNC-001 用户端核心页面

至少补齐：注册、登录态恢复、退出、内容创建/编辑/提交、需求创建/编辑/提交、方案提交、评论/标签主流程、文件上传状态和错误反馈。每个页面覆盖加载、空状态、权限拒绝、校验错误、依赖故障和成功反馈。

完成定义：Must 用户故事可以通过真实 UI 完成，关键流程有浏览器自动化，页面不得直接绕过 Spring Boot API 或权限模型。

2026-10-29 完成：在既有注册、会话、内容/需求、方案和评论页面基础上，补齐标签列表/选择、文件扫描状态、内容绑定和授权下载；后端持久化会验证 owner、`AVAILABLE` 状态和公开已发布内容授权。Nuxt lint、typecheck、生产构建通过；零新增依赖的 Chromium/CDP smoke 4/4 覆盖登录、标签+附件保存、标签依赖故障、阻断附件和 403 反馈；后端定向 16/16、MySQL 8.4.13 集成 3/3 通过。Epic 改为 `DONE`；mock API UI smoke 不冒充系统 E2E，完整业务闭环继续由 `TNX-VAL-001` 承接。

### TNX-FUNC-002 管理端运营页面

至少补齐：审核任务列表/详情/决定、定价中心、分类与推荐配置、Feature Flag/参数配置、AI 建议人工接受/拒绝、操作审计和批量任务结果展示。

完成定义：管理员/审核员权限隔离、版本冲突、部分失败和审计记录均有自动化证据。

2026-10-29 完成：审核、免费定价、参数/Feature Flag、强类型分类/推荐、AI 建议列表/决定、操作审计、标签创建/启停/别名/合并、标签批处理和逐项结果均已接真实 API。V13/V14 增加 `tn_tag_alias` 与批次结果表；OpenAPI 42 路径/53 操作，Admin lint/typecheck/build、Chromium smoke 4/4、MySQL 集成 3/3、全量 Maven 40 套件 111/111 通过。权限隔离、409、部分失败和审计证据满足完成定义，Epic 改为 `DONE`。

### TNX-VAL-001 系统级业务闭环

测试范围：富文本安全、评论删除、标签运营、需求到方案/文档 lineage、搜索排序和分页、运营推荐、通知产生/已读、AI 真实提供方的人工决定边界、Feature Flag 关闭/灰度行为，以及管理端批量/筛选。

完成定义：`RTM-GAP-004` 中每个 Must 项有系统或浏览器证据；不能仅以代码存在、编译通过或组件单测代替业务验收。

2026-10-29 完成：安全 Markdown 渲染在真实 Chromium 中以恶意 `img/onerror` 载荷验证不生成可执行节点；评论列表、回复通知、软删除父评论保留子回复、通知已读在 MySQL 8.4.13 闭环通过；lineage 可从问题递归返回需求、方案、解决内容和知识文档；搜索覆盖公开内容、需求、用户和标签以及相关度/最新/运营热度/预算/截止时间排序与页码分页；运营置顶结果优先于自然结果；`registration.enabled`、`comments.enabled`、`ai.enabled` 开始实际约束入口，并支持稳定百分比灰度。OpenAI-compatible HTTP 适配器契约、重试和人工决定不修改文章事实已验证；当前环境没有外部厂商 API 凭据，因此不把回环 HTTP provider 当作真实厂商实网证据，`FR-AIOPS-01` 的实网 smoke 作为 Should 限制保留。OpenAPI 达 44 路径/56 操作，Chromium 5/5、MySQL 集成 3/3、全量 Maven 42 套件 115/115、Nuxt lint/typecheck/build 均通过。所有 P0 Must 已有系统或浏览器证据，Epic 改为 `DONE`，`RTM-GAP-004` 对 Must 关闭。详细证据见 `业务闭环验收证据-20261029.md`。

### TNX-GATE-004 候选服务器专项

子任务：记录 CPU、内存、磁盘、网络、OS、Java 21、MySQL 和容器版本；部署候选构建；准备接近 Alpha 规模的数据；复跑 10 QPS 持续、20 QPS 突发和至少 30 分钟稳定性测试；采集 JVM、GC、线程、Hikari、SQL、磁盘和队列指标；形成容量结论和扩容阈值。

2026-10-29 本机准备：新增 `scripts/candidate/` 候选机门禁工具包和 Prometheus registry。候选模式拒绝 loopback、缩短窗口、缺失指标 Token及跳过数据库快照；自动通过后仍要求 SRE/QA 人工趋势复核。本机缩短预演为 10 QPS/20s、20 QPS/10s、10 QPS/60s，共 1000 个正式请求，P95 约 56ms、0 错误，线程 29→29、Hikari pending 最大 0，但 JVM heap 短窗增加约 50.5MB，必须在 30 分钟候选窗口复核。证据明确为 `NON_GATING_LOCAL_REHEARSAL`；无目标机，因此状态和完成定义不变。

完成定义：候选机满足性能护栏且没有资源持续增长、连接泄漏或错误率超标，`RTM-GAP-005` 关闭。

### TNX-GATE-005~007 专项验证

- 浏览器/a11y/SEO：主流 PC 和移动视口核心流程、键盘操作、焦点、标签、对比度、SSR 元数据、404/错误页；
- 可观测性：结构化日志和脱敏、Trace/Request ID、readiness/liveness、JVM/Hikari/MySQL/文件队列指标、慢 SQL、备份失败和磁盘告警；
- 安全：TLS、只开放必要端口、安全头、认证/授权/CSRF/IDOR、上传绕过、SSRF、限流、依赖和密钥扫描，并记录修复复测。

完成定义：`TST-RISK-004`、`RTM-GAP-006` 有完整报告和可定位原始证据。

2026-11-04 浏览器本机准备：修复 skip link、可见焦点、44px 目标、移动端换行、登录标题和自定义 404/noindex；新增等价 a11y/SEO 自动检查。Edge 156 与 Chrome 156 在 1440×900、390×844 下完成 24/24 页面检查，5/5 SSR 路由元数据通过，Nuxt lint/typecheck/build 和原 UI smoke 5/5 通过。由于移动视口仅为桌面内核仿真，且无候选 URL、真实 Android/Safari，状态改为 `BLOCKED_EXTERNAL` 而非 DONE。证据见 `浏览器可访问性SEO本机证据-20261104.md`。

2026-11-04 可观测性本机准备：新增安全 Request/Trace ID、MDC 清理、JSON 日志、liveness/readiness、MySQL/队列/JVM/Hikari/HTTP 指标、候选预检脚本、Prometheus 告警规则和处置手册。真实 MySQL 8.4.13 本机演练探针 200、指标齐全、敏感测试值未泄漏；Maven 43 套件 117 项 0 失败/0 错误，首次全量的 3 个 MySQL 条件测试因变量名误用被跳过，随后用正确 `TECHNEXUS_MYSQL_IT=true` 补跑 3/3、0 跳过。无候选采集平台、慢 SQL/磁盘/异机备份告警送达及恢复证据，因此 `TNX-GATE-006` 继续为 `BLOCKED_EXTERNAL`。证据见 `可观测性本机演练证据-20261104.md`。

2026-11-04 安全本机准备：API 和 Nuxt 增加防御性安全头，代理头默认不信任，Cookie 的 Secure/HttpOnly/SameSite 自动回归通过；修复 Vue/Nitro 版本不一致造成的生产产物无法启动，Vue 对齐 3.5.44 后生产首页 200。新增候选 TLS/证书/HSTS/跳转/端口预检脚本。当前 Maven 43 套件 118/118、npm 生产依赖 0 high/critical。无域名/证书/候选防火墙和授权渗透环境，`TNX-GATE-007` 保持 `BLOCKED_EXTERNAL`。证据见 `安全暴露面本机准备证据-20261104.md`。

2026-11-08 继续执行预检：仓库和当前进程环境未发现候选 URL、目标主机权限、`TECHNEXUS_METRICS_TOKEN`、域名证书、真实 Android/Safari 设备或候选告警接收配置；本机应用服务当前也未运行。候选门禁脚本明确拒绝 loopback，故不以本机重跑冒充正式证据。`TNX-GATE-004~007` 保持 `BLOCKED_EXTERNAL`，现有工具包和本机证据保留，待上述输入到位后可直接执行正式验证。

2026-11-16 状态复核：`RTM-GAP-004` 已由 `TNX-FUNC-001/002` 与 `TNX-VAL-001` 关闭；当前正式未关闭范围统一为 `RTM-GAP-003/005/006` 和 `TST-RISK-002/004`。第 1 轮 69 分作为历史事实保留，第 2 轮正式复评尚未启动；工作区质量门禁和 Git 收口已完成。

2026-11-16 本机质量门禁复跑：Java 43 套件 118/118，真实 MySQL 8.4.13 集成 3/3、0 跳过，Flyway V1—V14 validate 通过；总行覆盖率 79.3%（3211/4048）、领域分支 88.3%（219/248）；双前端 lint/typecheck/build 和 high/critical audit 通过，Web 核心 UI 5/5、Edge/Chrome 本机矩阵 24/24、Admin 4/4；聚合 SBOM 137组件、实际暂存区 Gitleaks 0泄漏、`git diff --cached --check` 通过。本机 `artifacts/` 仅保留执行证据并已排除源码提交。

## 5. 评审、上线和收口

### TNX-REV-001 阶段四第 2 轮正式复评

前置条件：所有 P0 项完成或具有用户书面批准的例外；严重未关闭缺陷为 0；Must 需求全部有有效验证或批准例外。复评需要更新测试计划、用例、报告、性能报告、RTM、评分和专家结论，并由用户回复通过后才能进入阶段五。

### TNX-REL-001 阶段五

仅在阶段四通过后建立：发布方案、环境变量/Secret 管理、数据库备份点和回滚、灰度/健康检查、上线验证、监控值守、变更记录。禁止把本地 `credentials.txt` 提交或导入 Multica。

### TNX-CLOSE-001 阶段六

汇总 PRD→设计→代码→测试→发布追溯、未清技术债、运行手册、资产清单和最终评分；确认仓库、Multica、部署环境和文档状态一致。

## 6. 已批准延期，不阻断 V1 阶段四

`TNX-P1-001` 仅迁移为 Deferred backlog，不应被误排进当前门禁：

- `FR-ACC-04`：账号注销、历史保留与匿名化；
- `FR-CNT-04`：点赞和收藏；
- `FR-MSG-02`：通知偏好页面；
- `FR-LOOP-03`：完整专家证据主页；
- PAID/PREVIEW/ATTACHMENT_PAID、订单、支付和 Entitlement；
- 安全工程精选方向及其他已确认 P1 运营扩展。

## 7. Multica 实际迁移步骤

1. 创建 `TechNexus` 项目，关联 GitHub 仓库 `liuyunhu1127/TechNexus`；
2. 导入同目录的 `multica-backlog.csv`，把 `ID` 保存为外部编号/自定义字段；
3. 建立 `Phase 04 Gate`、`V1 Alpha Release`、`P1 Deferred` 三个里程碑；
4. 按本文件第 2 节映射状态、优先级、负责人角色和依赖；
5. 把 SRS、设计、OpenAPI、测试报告和 RTM 作为项目文档链接，不复制出第二套不可追溯正文；
6. 在 Multica Secret/环境管理中重新配置变量值，只迁移变量名；严禁上传 `credentials.txt`、`.env`、访问密钥或私钥；
7. 当前分支的阶段四整改已提交；迁移或合并前仍由人工确认目标分支策略并保留可恢复 Git 基线；
8. 导入后抽查全部稳定 ID、依赖和验收条件，再把本文件标记为已迁移，避免双系统同时作为事实源。

## 8. 需要用户或外部环境提供的输入

- Multica 工作区、项目权限、字段/导入格式和 GitHub 连接方式；
- 候选/生产环境的 S3、ClamAV 地址及 Secret 配置入口（本机文件安全门禁已完成）；
- 非同主机备份目标和保留策略确认；
- 候选服务器登录权限、域名、证书和允许开放的端口；
- 浏览器/移动设备范围、告警接收渠道和渗透测试授权范围；
- 阶段四例外是否接受以及最终门禁决定。
