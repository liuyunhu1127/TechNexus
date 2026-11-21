# TechNexus

TechNexus 是面向技术内容、真实需求、交付方案和知识沉淀闭环的 Alpha 工程。后端采用 Java 21、Spring Boot 4.0、Jetty、DDD 模块化单体和 MySQL；用户端采用 Nuxt 4，管理端采用 Vue 3/Vite。

> 当前状态：阶段四测试尚未通过，不可直接用于生产或真实用户上线。`TNX-GATE-001/002` 与 `TNX-FUNC-001/002` 已完成；剩余阻断项包括非同主机备份、系统级业务闭环和候选服务器/外部专项验证。详见[阶段四测试报告](docs/technexus-solution-closure/04-测试阶段/测试报告.md)。

## 1. 工程结构

工程名严格固定为：

| 工程 | 作用 |
|---|---|
| `technexus-web` | Nuxt 4 用户端 |
| `technexus-admin` | Vue 3/Vite 管理端 |
| `technexus-server` | Spring Boot 组合根、HTTP 入口和基础设施适配器 |
| `technexus-common` | 共享内核 |
| `technexus-auth` | 认证与会话上下文 |
| `technexus-user` | 用户与权限上下文 |
| `technexus-community` | 评论、标签等社区上下文 |
| `technexus-content` | 帖子、文章和内容版本上下文 |
| `technexus-demand` | 需求、方案和闭环关系上下文 |
| `technexus-audit` | 审核任务上下文 |
| `technexus-pricing` | 定价上下文 |
| `technexus-file` | 文件对象、关系和扫描上下文 |

Java 根包统一为 `com.technexus`。数据库表统一使用 `tn_` 前缀、小写 snake_case 和单数名词。数据库迁移位于 `technexus-server/src/main/resources/db/migration`，由 Flyway 在后端启动时执行。

## 2. 启动条件

### 2.1 必需软件

| 软件 | 最低/推荐版本 | 检查命令 | 用途 |
|---|---|---|---|
| JDK | 21 | `java -version` | 编译和运行后端 |
| Maven | 3.9+ | `mvn -version` | 多模块构建 |
| Node.js | 20+，推荐 22/24 LTS | `node --version` | 双前端构建与开发服务器 |
| npm | 10+ | `npm --version` | 前端依赖 |
| MySQL | 8.4 | `mysql --version` | 核心事实数据源 |

Windows PowerShell 是本文主要示例环境。Linux/macOS 可将 `$env:NAME='value'` 换成 `export NAME='value'`。

### 2.2 服务依赖

| 依赖 | 最小启动 | 完整功能 | 说明 |
|---|---|---|---|
| MySQL | 必须在线 | 必须在线 | 数据库和账号必须预先创建；Flyway 自动建表/升级 |
| S3/MinIO | 可暂时离线 | 文件功能必须在线 | 后端启动仍要求合法的 endpoint、bucket 和非空凭据；服务离线时文件完成接口返回 503 |
| ClamAV daemon | 可暂时离线 | 文件功能必须在线 | 默认 `localhost:3310`，仅 CLEAN 文件可进入可用态 |
| OpenAI 兼容 AI 服务 | 不需要 | 启用 AI 建议时需要 | 默认 `TECHNEXUS_AI_ENABLED=false` |

对象存储的非本机 endpoint 必须使用 HTTPS。Bucket 名必须符合 S3 命名规则，Secret Key 至少 16 个字符。

### 2.3 默认端口

| 服务 | 默认地址 |
|---|---|
| 后端 API | `http://localhost:8080` |
| 健康检查 | `http://localhost:8080/actuator/health` |
| 用户端 | `http://localhost:3000` |
| 管理端 | `http://localhost:5173` |
| MySQL | `localhost:3306` |
| S3/MinIO API | `http://localhost:9000` |
| ClamAV | `localhost:3310` |

启动前确认这些端口没有被其他程序占用。

## 3. 首次初始化

### 3.1 创建 MySQL 数据库和开发账号

以有建库权限的 MySQL 管理账号连接：

```powershell
mysql -uroot -p
```

执行以下 SQL，并把示例密码替换成仅用于本机的强密码：

```sql
CREATE DATABASE technexus
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_0900_ai_ci;

CREATE USER 'tn_app'@'localhost' IDENTIFIED BY 'replace-with-a-strong-local-password';
GRANT ALL PRIVILEGES ON technexus.* TO 'tn_app'@'localhost';
FLUSH PRIVILEGES;
```

开发账号需要 DDL 权限，因为应用启动时会运行 Flyway。生产环境应限制账号来源地址，并按运维方案分离 migration 和运行时权限。

### 3.2 配置环境变量

`.env.example` 是变量清单，不包含有效密钥，也不会由 Spring Boot 自动加载。不要把真实 `.env` 或密码提交到 Git。

PowerShell 示例：

```powershell
$env:TECHNEXUS_DB_URL='jdbc:mysql://localhost:3306/technexus?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC'
$env:TECHNEXUS_DB_USER='tn_app'
$env:TECHNEXUS_DB_PASSWORD='replace-with-a-strong-local-password'
$env:TECHNEXUS_JWT_SECRET='replace-with-at-least-32-random-bytes'
$env:TECHNEXUS_BOOTSTRAP_ADMIN_EMAIL='admin@example.com'
$env:TECHNEXUS_ALLOWED_ORIGINS='http://localhost:3000'

$env:TECHNEXUS_STORAGE_ENDPOINT='http://localhost:9000'
$env:TECHNEXUS_STORAGE_INTERNAL_ENDPOINT='http://localhost:9000'
$env:TECHNEXUS_STORAGE_BUCKET='technexus-files'
$env:TECHNEXUS_STORAGE_REGION='us-east-1'
$env:TECHNEXUS_STORAGE_ACCESS_KEY='replace-with-access-key'
$env:TECHNEXUS_STORAGE_SECRET_KEY='replace-with-secret-key'

$env:TECHNEXUS_CLAMAV_HOST='localhost'
$env:TECHNEXUS_CLAMAV_PORT='3310'
$env:TECHNEXUS_AI_ENABLED='false'
```

关键变量：

| 变量 | 必填 | 规则 |
|---|---|---|
| `TECHNEXUS_DB_URL` | 是 | MySQL JDBC URL，服务与数据库统一使用 UTC |
| `TECHNEXUS_DB_USER` / `TECHNEXUS_DB_PASSWORD` | 是 | 数据库账号和密码 |
| `TECHNEXUS_JWT_SECRET` | 是 | 至少 32 个 UTF-8 字节；更换后已有 Token 全部失效 |
| `TECHNEXUS_BOOTSTRAP_ADMIN_EMAIL` | 建议 | 与管理员注册邮箱完全一致，不区分大小写 |
| `TECHNEXUS_ALLOWED_ORIGINS` | 是 | Refresh/Logout 允许的 Origin；多个值用逗号分隔 |
| `TECHNEXUS_STORAGE_*` | 是 | `ENDPOINT` 是返回浏览器的 HTTPS 地址；`INTERNAL_ENDPOINT` 可覆盖为后端可信内网地址；其余存储配置即使暂不使用文件也必须合法 |
| `TECHNEXUS_CLAMAV_*` | 文件功能必填 | daemon 地址、端口、超时和轮询周期 |
| `TECHNEXUS_AI_*` | AI 开启时必填 | 默认关闭；开启时必须配置兼容 API 和 Key |

### 3.3 本地测试邮箱

默认关闭发信。先在本机启动仅监听回环地址的 SMTP 测试收件箱，以下配置适用于在 1025 端口接收邮件的本地收件箱。

```powershell
$env:TECHNEXUS_ACCOUNT_MAIL_ENABLED='true'
$env:TECHNEXUS_ACCOUNT_MAIL_FROM='notifications@example.test'
$env:TECHNEXUS_ACCOUNT_WEB_ORIGIN='http://localhost:3000'
$env:TECHNEXUS_SMTP_HOST='127.0.0.1'
$env:TECHNEXUS_SMTP_PORT='1025'
$env:TECHNEXUS_SMTP_AUTH='false'
$env:TECHNEXUS_SMTP_STARTTLS='false'
```

启动后端和用户端后，用测试账号在「个人中心」验证邮箱、换绑邮箱，或从登录页进入「忘记密码」。邮件在本地收件箱查看。普通用户已验证的邮箱可用于自助找回密码。使用完整头像功能时，还需启动对象存储和 ClamAV。

后端的 `AccountCenterHttpIntegrationTest` 会启动临时本地 SMTP 收件箱，并验证验证码、重置链接、会话撤销和 Bootstrap 管理员限制。

## 4. 构建和验证

在仓库根目录执行后端全量验证：

```powershell
mvn -s .mvn/settings.xml clean verify
```

安装并验证双前端：

```powershell
npm --prefix technexus-web ci
npm --prefix technexus-web run lint
npm --prefix technexus-web run typecheck
npm --prefix technexus-web run build

npm --prefix technexus-admin ci
npm --prefix technexus-admin run lint
npm --prefix technexus-admin run typecheck
npm --prefix technexus-admin run build
```

生产构建失败时不要改用 `npm install` 绕开 lockfile；应先确认 Node/npm 版本和 `package-lock.json` 是否一致。

## 5. 如何启动

建议依次启动 MySQL、对象存储/ClamAV（如需文件功能）、后端、用户端、管理端。三个应用分别占用一个终端。

### 5.1 启动后端

先构建可运行 Jar：

```powershell
mvn -s .mvn/settings.xml -pl technexus-server -am -DskipTests package
```

再启动：

```powershell
java -jar technexus-server/target/technexus-server-0.1.0-SNAPSHOT.jar
```

看到 Flyway 验证成功和 Jetty 监听 8080 后，在新终端检查：

```powershell
Invoke-RestMethod http://localhost:8080/actuator/health
```

期望返回 `status: UP`。首次启动会自动执行 V1 至当前版本的 migration；不要修改已经执行过的 migration，应新增更高版本脚本。

### 5.2 启动用户端

用户端默认把 `/api/**` 同源代理到 `http://localhost:8080/api/**`，避免本地开发跨域。可通过 `TECHNEXUS_API_PROXY_TARGET` 覆盖代理目标。

```powershell
$env:NUXT_PUBLIC_API_BASE='/api/v1'
$env:TECHNEXUS_API_PROXY_TARGET='http://localhost:8080/api/**'
npm --prefix technexus-web run dev
```

打开 `http://localhost:3000`。

### 5.3 启动管理端

Vite 开发服务器已经把 `/api` 代理到 `http://localhost:8080`：

```powershell
$env:VITE_API_BASE='/api/v1'
npm --prefix technexus-admin run dev
```

打开 `http://localhost:5173`。生产部署时应由反向代理把两个前端的 `/api` 转发到后端，不建议直接从浏览器访问跨域 API。

### 5.4 最小启动检查

```powershell
Invoke-RestMethod http://localhost:8080/actuator/health
Invoke-RestMethod http://localhost:8080/api/v1/contents
Invoke-RestMethod http://localhost:8080/api/v1/demands
```

也可以运行阶段四系统脚本。该脚本会创建测试数据，只能对隔离测试库执行：

```powershell
./scripts/system-smoke.ps1 -BaseUrl http://127.0.0.1:8080
```

短性能预检同样只能用于测试环境：

```powershell
node ./scripts/performance-smoke.mjs
```

候选服务器容量门禁使用 `scripts/candidate/`。本机可执行缩短预演，但输出会固定标记为非门禁；正式候选运行要求独立负载机、非 loopback HTTPS URL、指标 Token、数据库快照和至少 30 分钟稳定窗口：

```powershell
./scripts/candidate/Invoke-TechNexusCandidateGate.ps1 `
  -Classification LOCAL_REHEARSAL `
  -BaseUrl http://127.0.0.1:8080 `
  -SustainedSeconds 20 -BurstSeconds 10 -StabilitySeconds 60
```

完整候选机执行方式和目标机并行资源采集见 `scripts/candidate/README.md`。

用户端浏览器、移动视口、键盘、可访问性和 SSR/SEO 本机技术检查：

```powershell
npm --prefix technexus-web run test:quality-smoke
```

该命令会启动临时 Nuxt/Mock API 和本机 Edge、Chrome 无窗口实例。移动结果是 390×844 视口仿真，不能替代真实 Android 和 Safari iOS 的候选环境验收。

## 6. 首个管理员账号

管理员身份由 `TECHNEXUS_BOOTSTRAP_ADMIN_EMAIL` 与登录账号邮箱匹配产生。初始化流程：

1. 启动后端前设置 `TECHNEXUS_BOOTSTRAP_ADMIN_EMAIL`；
2. 使用同一邮箱调用注册接口；
3. 打开管理端并使用该邮箱、注册密码登录；
4. 管理端会调用后端验证管理员权限，验证通过后进入运营总览。普通账号不能进入管理台。

PowerShell 示例：

```powershell
$adminEmail = 'admin@example.com'
$adminPassword = 'replace-with-a-strong-password'
$headers = @{ 'Idempotency-Key' = [guid]::NewGuid().ToString() }
$registerBody = @{
  email = $adminEmail
  password = $adminPassword
  displayName = 'TechNexus Admin'
} | ConvertTo-Json

Invoke-RestMethod `
  -Method Post `
  -Uri 'http://localhost:8080/api/v1/auth/register' `
  -Headers $headers `
  -ContentType 'application/json' `
  -Body $registerBody

```

如果该邮箱已经注册，不要重复注册，直接在管理端登录即可。修改 bootstrap 邮箱会改变后续 Token 的管理员权限判定，因此生产环境必须受控变更。

## 7. 用户操作手册

### 7.1 游客

1. 打开用户端首页；
2. 选择“探索知识”查看已发布的公开内容；
3. 选择“浏览需求”查看已审核且完成免费定价的公开需求；
4. 列表加载失败时使用页面上的“重试”。

草稿、待审核内容和未完成定价的需求不会向游客展示。

### 7.2 普通用户登录

1. 新用户打开 `http://localhost:3000/register`，填写显示名称、邮箱和至少 12 位的密码；
2. 注册成功后进入登录页；已有账号可直接打开 `http://localhost:3000/login`；
3. 输入注册邮箱和密码；
4. 登录成功后进入技术内容页。页面会在同一浏览器会话中通过 Refresh Cookie 恢复登录态，也可从导航栏安全退出。

Access Token 仅保存在 Nuxt 内存状态中；页面刷新时使用 `HttpOnly` Refresh Cookie 与会话级 CSRF Token 换取新 Token，不把 Access Token 写入持久化存储。

### 7.3 内容与需求操作边界

用户端已经支持内容和需求的公开列表/详情、草稿创建与编辑、提交审核、已发布需求的方案提交、评论提交，以及标签选择、文件直传、安全扫描、内容绑定和授权下载。标签创建、启停、别名和合并属于管理端运营能力；其余完整业务状态流仍在阶段四待办中。尚未提供的操作应使用受控 API 客户端，并以 `docs/technexus-solution-closure/02-设计阶段/openapi.yaml` 为准。

按 OpenAPI 契约提交 `Idempotency-Key`。登录、刷新、申请邮箱验证码和提交新密码可直接发送。更新、提交、审核和定价操作还需提交服务端返回的当前版本，旧版本会返回 409。

### 7.4 会话和 CSRF

- Access Token 通过 `Authorization: Bearer <token>` 发送；
- Refresh Token 位于 `tn_refresh` 的 `HttpOnly + Secure + SameSite=Strict` Cookie；
- 刷新或退出还必须发送允许的 `Origin`；
- 将 `tn_csrf` Cookie 的值放入 `X-CSRF-Token` 请求头；
- Refresh Token 重用会撤销整个令牌族，用户需要重新登录。

完整会话 Cookie 使用 `Secure` 属性。除 localhost 开发外，应通过 HTTPS 访问。

### 7.5 个人中心与找回密码

- 在「个人中心」选择 JPEG 或 PNG 图片，预览并保存头像。图片大小上限为 5 MiB，宽高各不超过 4096 像素。通过安全扫描后显示新头像，移除后显示默认头像。
- 未验证的账号先用当前密码申请原邮箱验证码。换绑时输入当前密码和新邮箱，分别确认两个邮箱收到的验证码，完成后使用新邮箱重新登录。
- 忘记密码时，在登录页选择「忘记密码」，填写当前已验证邮箱。打开邮件中的一次性链接并输入两遍新密码。链接有效期 15 分钟，重置后所有旧会话失效。
- Bootstrap 管理员可以在管理端个人中心更换头像。邮箱换绑和密码恢复由人工流程处理。

## 8. 运营管理员操作手册

### 8.1 运营总览

1. 按“首个管理员账号”步骤完成管理员注册；
2. 打开 `http://localhost:5173`；
3. 输入管理员邮箱和密码并登录；
4. 登录后自动验证管理员权限并显示运营总览；
5. 使用右上角“退出登录”安全结束会话。

管理端不再要求人工复制 Token。Access Token 与 CSRF Token 仅保存在当前浏览器的 `sessionStorage`，Refresh Token 使用 `HttpOnly`、`Secure` Cookie；会话过期或服务端返回 401 时页面自动回到登录界面。不要在截图、工单或聊天中发送 Token。

### 8.2 审核、定价、配置与运营

管理端已经提供以下真实 API 页面操作：

- 审核任务查询、状态筛选和人工决定；
- V1 免费定价确认；
- 系统参数与独立 Feature Flag 的版本化更新；
- AI 建议任务创建、状态查询和人工接受、编辑或拒绝；
- 标准标签创建、启停、别名维护和合并；
- 特权操作审计查询。

写操作会自动携带 `Idempotency-Key`，版本冲突返回 409。标签合并会迁移文章/帖子关系并保留源标准名和别名；执行前应从列表读取源、目标当前版本。

AI 输出只能作为建议，不得直接覆盖审核、最终价格、权限或封禁事实。

## 9. 常见问题

### 后端启动时报数据库连接失败

- 确认 MySQL 正在监听 JDBC URL 中的主机和端口；
- 确认数据库已经创建，账号密码和授权正确；
- 检查 URL 是否包含 `serverTimezone=UTC`；
- 检查 3306 是否被其他 MySQL 实例占用。

### 后端提示 JWT Secret 长度不足

`TECHNEXUS_JWT_SECRET` 必须至少包含 32 个 UTF-8 字节。不要使用 README 或 `.env.example` 中的占位值部署。

### 反向代理后的 HTTPS 被识别为 HTTP

默认 `TECHNEXUS_FORWARD_HEADERS_STRATEGY=none`，防止客户端伪造 `X-Forwarded-*`。只有后端端口不对公网开放且所有流量均经过可信反向代理时，才设置为 `framework`；同时由代理完成 80→443 跳转、证书、HSTS 和真实客户端地址覆盖。

### 后端提示 Object storage credentials are missing

即使只浏览内容，也需要提供非空 Access Key 和至少 16 字符的 Secret Key。完整文件功能还要求对象存储 bucket 已存在并且 ClamAV 在线。

### 用户端显示“内容暂时无法加载”

- 先访问后端健康检查；
- 确认用户端 `NUXT_PUBLIC_API_BASE=/api/v1`；
- 确认 `TECHNEXUS_API_PROXY_TARGET=http://localhost:8080/api/**`；
- 修改环境变量后重启 Nuxt 开发服务器。

### 管理端无法读取运营看板

- 确认 Vite 与后端都已启动；
- 登录邮箱必须与 `TECHNEXUS_BOOTSTRAP_ADMIN_EMAIL` 对应；
- 普通用户登录后会显示“没有管理端权限”；过期或无效会话会自动返回登录页；
- 清除该站点的 Cookie 与 `sessionStorage` 后重新登录，不要手工拼接或粘贴 JWT。

### Refresh 或 Logout 返回 403

确认请求同时包含：允许的 `Origin`、`tn_refresh` Cookie、`tn_csrf` Cookie，以及值与 `tn_csrf` 相同的 `X-CSRF-Token` 请求头。

### 文件上传完成返回 503

这表示对象存储或扫描依赖不可用。不要把上传记录手工改成 AVAILABLE。检查 S3/MinIO endpoint、bucket、凭据、网络和 ClamAV。

## 10. 停止服务

开发终端中按 `Ctrl+C` 停止 Nuxt、Vite 和 Spring Boot。应用启用了 graceful shutdown；等待后端完成连接池和 Jetty 关闭后再停止 MySQL、MinIO 或 ClamAV。

## 11. 交付资料和接口契约

- 工作流状态：`docs/technexus-solution-closure/工作流状态.md`
- 需求基线：`docs/technexus-solution-closure/01-需求阶段/需求规格说明书.md`
- 架构与详细设计：`docs/technexus-solution-closure/02-设计阶段/`
- OpenAPI：`docs/technexus-solution-closure/02-设计阶段/openapi.yaml`
- 开发规范：`docs/technexus-solution-closure/03-开发阶段/`
- 测试计划、用例、报告和 RTM：`docs/technexus-solution-closure/04-测试阶段/`
- 剩余工作与 Multica 迁移清单：`docs/technexus-solution-closure/04-测试阶段/Multica迁移待办.md`
- Git 工作流：`docs/technexus-solution-closure/03-开发阶段/Git工作流规范.md`

## 12. 当前已知限制

- 阶段四第 1 轮评分 69，尚未通过阶段门禁；
- `TNX-GATE-001` 标准 Java 21/Maven、双前端、覆盖率、SBOM/OSV 与 Gitleaks 门禁已于 2026-10-28 通过；
- `TNX-GATE-002` 已完成真实 S3 兼容存储 + ClamAV 上传、Magic/Hash 校验、扫描、隔离、授权下载、同 Hash/IDOR、清理和故障恢复 E2E；证据见 `文件安全E2E证据-20261028.md`；
- 缺少非同主机备份，禁止真实用户上线；
- 备份/恢复脚本和本机隔离演练已完成，运行方式、远端目标约束及告警要求见[异机备份与恢复运行手册](docs/technexus-solution-closure/04-测试阶段/异机备份与恢复运行手册.md)；本机演练不能替代真实异机证据；
- `TNX-FUNC-001` 已完成注册、会话恢复/退出、内容和需求创建编辑/提交审核、方案、评论、标签选择、文件上传/扫描/绑定/下载，并有 Chromium UI smoke；完整后端业务闭环仍归 `TNX-VAL-001`；
- 管理端已实现运营总览、审核任务查询/人工决定、V1 免费定价、配置并发更新、独立 Feature Flag、分类/推荐、AI 建议列表/人工决定、标签运营、操作审计和批量逐项结果；系统级业务闭环继续由 `TNX-VAL-001` 验证；
- 浏览器/a11y/SEO 与可观测性应用侧本机专项已通过；真实 Android/Safari、候选 Prometheus/Alertmanager/日志平台、告警送达及渗透测试仍待补充；可观测性检查见 `scripts/observability/`，规则和处置见[可观测性与告警运行手册](docs/technexus-solution-closure/04-测试阶段/可观测性与告警运行手册.md)。

在这些事项关闭并通过测试与上线评审前，不应将当前 Alpha 构建解释为生产就绪。
