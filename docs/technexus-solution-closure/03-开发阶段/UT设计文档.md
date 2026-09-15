# UT单元测试设计文档

文档编号：`DEV-UT-001`  
版本：`v0.4`  
状态：`评审中/未达门禁`  
被测版本：`DEV-BUILD-20260915-04`（HEAD `163f8cd` + 当前未提交工作区）  
测试框架：JUnit Jupiter 5、Spring Boot Test、ArchUnit 1.4.1、JaCoCo 0.8.13、Spring Mock、vue-tsc

## 1. 目标与范围

测试覆盖领域聚合、Auth/安全组件、用户/内容/需求/社区/Admin/File 应用服务、审核跨上下文编排、文件扫描 Worker、API 路由表面、架构边界和数据库命名。生产 JDBC、MySQL 方言/事务、S3/ClamAV 协议、完整 HTTP Schema 与前端交互测试尚未形成集成证据。

## 2. 测试策略

- Domain：覆盖主要不变量、正常转换、非法转换、权限和版本边界。
- Application：以内存端口替身验证用例编排、稳定错误码、版本冲突与状态结果。
- Security：覆盖 Token 签名/过期/规范编码、Refresh 重用撤销、锁定、Origin+双提交 CSRF。
- File：覆盖上传完成校验、下载授权、扫描 clean/infected/失败重试与最终隔离。
- Static/component：验证 domain 依赖规则、35 个 API 方法/路径、`tn_` 表名及 V1—V9 连续迁移。
- Coverage：JaCoCo 仅采集 `com.technexus.*`，避免运行 JDK 26 时对 JDK 自身类插桩；项目仍以 `--release 21` 编译。

## 3. 测试结构与替代

Java 测试位于各模块 `src/test/java`，采用 `*Test.java`。当前 25 个测试文件共执行 56 个测试。内存 Repository 只在测试 profile 或显式 Fixture 中使用；生产 profile 仍装配 JDBC/S3/ClamAV 适配器。

| 依赖 | 单元测试替代 | 已验证 | 未验证 |
|---|---|---|---|
| Auth/User/Content/Demand/Community Repository | `InMemory*` | 主要用例、冲突、权限 | MySQL 方言与并发 |
| Admin Repository | `InMemoryAuditTaskRepository`、`InMemoryPriceObjectRepository`、`InMemoryOperationsRepository` | 领取/决定、目标版本回写、定价、配置、AI 决定约束 | JDBC 事务与重启恢复 |
| Object storage/File record | Fake/InMemory | 元数据、授权、状态 | 真实 S3/MinIO |
| Malware scanner | Lambda Fake | clean、infected、异常重试、最终 BLOCKED | 真实 ClamAV INSTREAM |
| Clock | 固定 Clock | 到期与扫描重试时间 | 时钟漂移 |

## 4. 核心用例

| UT编号 | 行为 | 预期 | 代码位置 |
|---|---|---|---|
| UT-AUTH-001 | 注册/登录/Refresh/旧 Refresh 重用 | 单次轮换，重用撤销令牌族 | `AuthApplicationServiceTest` |
| UT-SEC-001 | JWT 签名、过期、篡改和非规范编码 | 非法 Token 拒绝 | `BearerTokenServiceTest` |
| UT-SEC-002 | Cookie 变更请求 | Origin 与 CSRF 同时匹配才继续 | `CookieMutationGuardFilterTest` |
| UT-CNT-001 | Article/Post 版本与提交 | 快照、状态、乐观版本正确 | Content 测试 |
| UT-DMD-001 | Demand/Proposal/Lineage | 状态、预算、权限和血缘正确 | Demand 测试 |
| UT-COM-001 | 评论与通知 | 发布和对象所有权正确 | Community 测试 |
| UT-ADM-001 | 审核领取/决定 | 仅领取人可决定，结果绑定任务 | `AdminApplicationServiceTest` |
| UT-ADM-002 | 定价、配置与 AI 人工决定 | 输入/版本/状态约束正确 | `AdminApplicationServiceTest` |
| UT-AUD-001 | Content/Demand 提交审核 | 创建并复用绑定版本的 AuditTask，返回 taskId | Content/Demand 应用服务测试 |
| UT-AUD-002 | 审核决定回写 | 通过/拒绝推进目标；失配时任务不终结 | `AdminApplicationServiceTest` |
| UT-PRC-001 | V1 需求定价 | 仅 FREE；APPROVED 在确认定价后 PUBLISHED | `AdminApplicationServiceTest` |
| UT-FILE-001 | 上传完成/下载 | 元数据一致并处于 AVAILABLE 且有权时才下载 | `FileApplicationServiceTest` |
| UT-FILE-002 | 扫描 clean/infected/error | AVAILABLE、BLOCKED、有界重试/最终 BLOCKED | `FileScanWorkerTest` |
| UT-ARCH-001 | DDD 依赖方向 | domain 不依赖框架；web 不依赖 infrastructure | `ArchitectureTest` |
| UT-API-001 | API 表面 | 35 个方法/路径集合一致 | `ApiContractSurfaceTest` |
| UT-DB-001 | 表名与 migration | `tn_`/核心表，V1—V9 连续命名 | `DatabaseNamingTest` |

## 5. 故障与恢复覆盖

| 故障 | 期望 | 状态 |
|---|---|---|
| 错误密码/锁定/Refresh 重用 | 稳定错误且不泄露账号；撤销令牌族 | 已覆盖 |
| JWT/CSRF 篡改 | 401/403，不继续业务链 | 已覆盖单元级 |
| 聚合旧版本写入 | `VERSION_CONFLICT`，不覆盖新状态 | 已覆盖主要路径 |
| 文件污染 | `BLOCKED` 且不能下载 | 已覆盖 Worker 单元级 |
| 扫描服务异常 | 指数退避；5 次后失败关闭 | 已覆盖单元级 |
| MySQL 不可用/死锁/双 Refresh | 有界失败；不能双成功 | 未覆盖 |
| 内容/需求提交后的审核任务创建 | 同版本只创建一次并返回 taskId | 已覆盖应用级 |
| 审核决定回写目标版本 | 版本匹配才推进，失配拒绝且任务不终结 | 已覆盖应用级 |

## 6. 覆盖率结果

| 范围 | 目标 | 当前 | 结论 |
|---|---:|---:|---|
| Domain 行 | ≥85% | 未配置聚合独立门槛 | 未通过 |
| Domain 分支 | ≥80% | 未形成可复核聚合值 | 未通过 |
| 全部手写 Java 行 | ≥70% | **43.0%（1096/2546）** | **未通过** |
| 关键路径 | 100% | 审核应用编排已覆盖；MySQL 并发和 AI Worker 缺失 | 未通过 |

模块行覆盖率：audit 73.5%、auth 37.5%、common 38.8%、community 40.7%、content 33.2%、demand 39.4%、file 50.8%、pricing 65.0%、server 43.1%、user 47.6%。当前只接入报告，尚未把低于阈值配置为 Maven 失败，避免在未补测试前将“报告存在”误写为“门禁达标”。

## 7. 执行结果

| 构建 | 测试 | 通过 | 失败 | 跳过 | 其他门禁 |
|---|---:|---:|---:|---:|---|
| DEV-BUILD-20260915-04 | 56 | 56 | 0 | 0 | 11 模块 verify、Spotless、ArchUnit、API/DB 静态检查均通过 |

前端：`technexus-web` Nuxt 3.21.11/Nitro build 通过；`technexus-admin` vue-tsc + Vite 7.3.6 build 通过。前端构建不计入 56 个 Java 测试。OpenAPI 3.1.0/v1.0.1 使用 Node `yaml` 实际解析通过，共 29 paths。

## 8. 后续测试任务

1. 增加领域、应用服务、Controller 契约和 JDBC Adapter 测试，将总行覆盖率提升至 ≥70%，Domain 分支提升至 ≥80%。
2. 在隔离 MySQL 8.4 执行 V1—V9、跨 Repository 事务、唯一约束、乐观锁和双 Refresh 并发测试。
3. 使用真实 S3/MinIO 与 ClamAV 验证上传、扫描、隔离、重试、恢复与下载授权。
4. 实现并验证 AI 后台执行状态机。
5. 为 Nuxt/Vue 增加 Vitest、Playwright、axe 与 ESLint 阻断。

## 9. 变更记录

| 日期 | 版本 | 说明 |
|---|---|---|
| 2026-09-14 | v0.1 | 首轮测试策略与 29/29 结果 |
| 2026-09-14 | v0.2 | 核心应用服务、安全与文件边界增测，47/47 |
| 2026-09-15 | v0.3 | Admin/文件扫描增测，52/52；接入 JaCoCo/Spotless并记录 42.0% 真实覆盖率 |
| 2026-09-15 | v0.4 | 审核创建/决定/版本失配与需求定价发布增测，56/56；覆盖率 43.0% |
