# UT单元测试设计文档

文档编号：`DEV-UT-001`  
版本：`v0.5`  
状态：`通过/待用户确认`  
被测版本：`DEV-BUILD-20260915-05`（HEAD `163f8cd` + 当前工作区）  
测试框架：JUnit Jupiter 5、Spring Boot Test、ArchUnit 1.4.1、JaCoCo 0.8.13、Spring Mock、ESLint、vue-tsc

## 1. 目标与范围

覆盖领域聚合、应用编排、安全、HTTP 契约、JDBC/MySQL、异步 Worker、架构依赖、命名、双前端 lint/build 和供应链扫描。生产 MySQL 路径使用隔离的 MySQL 8.4.11；外部 S3/ClamAV daemon、性能、可访问性和端到端浏览器测试进入阶段四。

## 2. 分层策略

- Domain：主要不变量、合法/非法状态转换、权限、金额、时间和版本边界。
- Application：审核、定价、文件、AI、用户路径及稳定错误码。
- Security：JWT、Refresh 轮换/重用、Origin+CSRF、对象授权、共享限流和幂等。
- Integration：Flyway V1—V11、JDBC Repository、跨 Repository 事务、并发 Refresh、持久化幂等。
- Contract/static：OpenAPI 3.1 Schema/状态码/幂等头、ArchUnit、工程与数据库命名。
- Supply chain：CycloneDX SBOM + OSV、npm audit、Gitleaks。

## 3. 核心用例

| 测试域 | 关键行为 | 预期 |
|---|---|---|
| Auth | 注册、登录、轮换、旧 Refresh 重用及并发 | 同一 Refresh 只成功一次；重用撤销令牌族 |
| Rate limit | 同账号/IP 连续登录 | 前 10 次允许，第 11 次返回 `AUTH_RATE_LIMITED`，状态由 MySQL 共享 |
| HTTP 幂等 | 首次、重放、不同请求体、处理中和 5xx | 重放原响应；Hash 冲突拒绝；失败释放占用 |
| Content/Demand | 状态、版本、提交审核与目标回写 | 同版本任务唯一；版本匹配才推进 |
| Pricing | V1 免费定价 | 仅 `FREE`；审核通过后确认定价才发布 |
| File | 元数据、授权、clean/infected/error | 仅 AVAILABLE 且有权可下载；污染/最终失败均 BLOCKED |
| AI | 成功、空输出、重试、最终失败 | `QUEUED→RUNNING→SUCCEEDED/FAILED`，不自动代替人工决定 |
| API | 29 paths/35 operationId、请求/响应 Schema和状态码 | Controller 与 OpenAPI 完全一致；写操作具备幂等键 |
| Database | V1—V11、命名、JDBC 全链路 | migrate/validate 成功；表名均为 `tn_` 小写单数 |
| Architecture | Domain/Web 依赖方向 | Domain 不依赖框架；Web 不越层访问 infrastructure |

## 4. MySQL 集成环境

| 项目 | 结果 |
|---|---|
| 数据库 | MySQL 8.4.11，隔离 schema `technexus_it` |
| 迁移 | 11 个 migration 全部 validate，schema 位于 V11 |
| 时间 | 服务端和连接均使用 UTC |
| 集成测试 | 迁移/命名、并发 Refresh、JDBC 业务工作流、幂等、共享限流 |
| 结果 | 3/3 `MySqlPersistenceIntegrationTest` 通过 |

## 5. 覆盖率门禁

| 范围 | 目标 | 实际 | 结论 |
|---|---:|---:|---|
| 全部手写 Java 行 | ≥70% | 77.1%（2157/2796） | 通过 |
| Domain 分支 | ≥80% | 90.1%（209/232） | 通过 |

`scripts/check-coverage.ps1` 聚合各模块 JaCoCo XML，在本地和 CI 中低于任一阈值即返回非零状态。

## 6. 执行结果

| 构建 | Java 测试 | 通过 | 失败 | 跳过 |
|---|---:|---:|---:|---:|
| DEV-BUILD-20260915-05 | 94 | 94 | 0 | 0 |

附加门禁：

- 11 个 Maven 模块在 Spring Boot 3.5.16 + Jetty 最终组合下 `clean install` 成功。
- `technexus-web` ESLint、Nuxt 生产构建、npm production audit 通过，0 vulnerability。
- `technexus-admin` ESLint、vue-tsc/Vite 生产构建、npm production audit 通过，0 vulnerability。
- CycloneDX/OSV 扫描 105 个后端组件、779 个用户端包、223 个管理端包，No issues found。
- Gitleaks 源码/配置/锁文件扫描无泄漏；生成目录按受控配置排除。

## 7. 阶段四测试范围

1. 真实 S3/MinIO 与 ClamAV daemon 的上传、扫描、隔离、重试、恢复和下载授权 E2E。
2. 浏览器端关键旅程、可访问性、兼容性与视觉回归。
3. Alpha 容量、10/20 QPS 护栏、慢查询、故障注入与恢复。
4. 渗透测试、目标服务器硬件记录、异地备份和 RPO/RTO 恢复演练。

这些项目不降低阶段三已验证的实现门禁，但必须在测试/上线门禁按设计基线关闭。

## 8. 变更记录

| 日期 | 版本 | 说明 |
|---|---|---|
| 2026-09-14 | v0.1 | 首轮测试策略与 29/29 |
| 2026-09-14 | v0.2 | 核心应用、安全和文件边界，47/47 |
| 2026-09-15 | v0.3 | Admin/文件扫描，52/52；覆盖率 42.0% |
| 2026-09-15 | v0.4 | 审核/定价，56/56；覆盖率 43.0% |
| 2026-09-15 | v0.5 | 94/94；MySQL、API/幂等、AI、覆盖率、前端及供应链门禁全部通过 |
