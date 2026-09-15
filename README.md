# TechNexus

TechNexus 是面向技术内容、真实需求、交付方案和知识沉淀闭环的 Alpha 工程。当前基线使用 Java 21、Spring Boot 3、DDD 限界上下文、MySQL 8.4、Nuxt 3 和 Vue 3。

## 工程结构

工程名严格使用以下固定名称：

- `technexus-web`：Nuxt 用户端
- `technexus-admin`：Vue/Vite 管理端
- `technexus-server`：Spring Boot 组合根与入站/出站适配器
- `technexus-common`：共享内核
- `technexus-auth`、`technexus-user`、`technexus-community`、`technexus-content`、`technexus-demand`、`technexus-audit`、`technexus-pricing`、`technexus-file`：DDD 业务模块

Java 根包统一为 `com.technexus`。数据库表统一使用 `tn_` 前缀、小写 snake_case 和单数名词；核心建表脚本位于 `technexus-server/src/main/resources/db/migration`。

## 本地构建

要求：JDK 21+、Maven 3.9+、Node.js 20+、npm 10+。

```powershell
mvn -s .mvn/settings.xml clean verify
npm --prefix technexus-web ci
npm --prefix technexus-web run build
npm --prefix technexus-admin ci
npm --prefix technexus-admin run build
```

## 运行后端

复制 `.env.example` 中的变量到本机安全的环境变量存储，不要提交真实密钥。数据库用户应只拥有 TechNexus schema 所需的最小权限。

```powershell
mvn -s .mvn/settings.xml -pl technexus-server -am spring-boot:run
```

Refresh Token 仅写入 `HttpOnly + Secure + SameSite=Strict` Cookie。调用 `/api/v1/auth/refresh` 或 `/api/v1/auth/logout` 时，客户端还必须提供允许的 `Origin`，并将 `tn_csrf` Cookie 的值放入 `X-CSRF-Token` 请求头。

## 设计与交付资料

受控需求、设计、阶段评审和工作流状态位于 `docs/technexus-solution-closure`。阶段二的 `openapi.yaml` 是 V1 HTTP 契约基线。

## 当前限制

该仓库仍处于开发阶段。除认证账号和会话外，部分业务接口目前使用进程内应用服务作为可执行原型；MinIO/S3 预签名地址尚未接入真实适配器。这些项目在阶段三评审中属于未关闭阻断项，不可据此部署生产环境。
