# Git 工作流规范

文档编号：`DEV-GIT-001`  
版本：`v1.0`  
状态：`已生效`  
仓库：`liuyunhu1127/TechNexus`  
默认分支：`master`  
工作流模式：**GitHub Flow（受保护主干 + 短生命周期分支）**  
维护角色：TechNexus Maintainer

## 1. 目标

本规范用于确保 TechNexus 的所有代码和文档变更具备：

- 可追溯性
- 可审查性
- 可重复验证
- 线性提交历史
- 安全与依赖门禁
- 明确的分支清理规则

`master` 作为唯一长期分支，应始终保持可构建、可验证状态。

## 2. 分支模型

长期分支仅保留：

```text
master
```

短生命周期分支使用：

```text
feature/<short-name>
fix/<short-name>
hotfix/<short-name>
refactor/<short-name>
docs/<short-name>
chore/<short-name>
```

所有短期分支必须从最新 `master` 创建：

```bash
git switch master
git pull --ff-only origin master
git switch -c feature/<short-name>
```

不维护长期 `develop` 或 `release` 分支。

## 3. Commit 规范

推荐格式：

```text
<type>(<scope>): <summary>
```

允许的常用类型：

```text
feat
fix
refactor
test
docs
build
ci
chore
perf
revert
```

要求：

- 单 Commit 尽量只表达一个逻辑变更。
- 禁止提交真实密钥、`.env`、构建产物、依赖目录。
- 禁止把大规模无关格式化与功能修改混合。
- 提交前执行 `git status` 与 `git diff --check`。

## 4. Pull Request

所有进入 `master` 的变更都必须通过 Pull Request。

PR 至少包含：

- 目的与背景
- 主要变更
- 影响范围
- 数据库 / API / 配置 / 安全影响
- 验证命令与结果
- 风险
- 回滚方法（适用时）
- UI 截图（适用时）

仓库使用 `.github/pull_request_template.md` 统一 PR 描述。

## 5. CI 门禁

当前 `master` 强制要求以下 Job 级 Status Check 全部通过：

```text
backend
frontend (technexus-admin)
frontend (technexus-web)
dependency-review
secret-scan
```

### 5.1 Backend

`backend` 当前覆盖：

- Java / Maven 构建与测试
- MySQL migration 验证
- Coverage Gate
- CycloneDX SBOM 生成
- OSV 依赖漏洞扫描

### 5.2 Frontend

两个前端均执行：

- `npm ci`
- lint
- production build
- `npm audit --omit=dev --audit-level=high`

`technexus-web` 的 lockfile 一致性校验以 CI 环境为最终基准；当前 CI 使用 Node.js 22，排查 lockfile 差异时使用 npm `10.9.8`。

### 5.3 Security

- `secret-scan` 使用 Gitleaks。
- `dependency-review` 检查 PR 引入的依赖风险。
- 后端 SBOM 与 OSV 扫描作为 `backend` 的组成部分。

## 6. `master` Ruleset

Ruleset：`master-branch-protection`

必须保持以下规则：

1. 禁止删除 `master`。
2. 禁止 non-fast-forward / force push。
3. 要求 linear history。
4. 所有变更必须经过 Pull Request。
5. Required Approvals 在单人阶段为 `0`。
6. 必须解决所有 Review Conversation。
7. 仅允许 Squash Merge。
8. 强制 5 个 Required Status Checks。
9. 启用 `Require branches to be up to date before merging`。

管理员绕过仅用于真正的紧急场景，并应保留审计和复盘记录。

## 7. Required Status Checks 管理原则

Required Check 使用 **Job 名称**，当前为：

```text
backend
frontend (technexus-admin)
frontend (technexus-web)
dependency-review
secret-scan
```

不要将 Job 内部 Step 名称（如 `npm ci`、`npm run lint`、`Verify backend and MySQL migrations`）作为独立 Required Check。

当 CI Job 重命名、拆分或删除时，必须同步更新 Ruleset，否则可能导致 PR 永久等待不存在的检查。

## 8. 合并策略

唯一允许的合并方式：

```text
Squash Merge
```

原因：

- 保持 `master` 线性历史。
- 将功能分支上的修正性 Commit 压缩为一个业务完整变更。
- 便于回滚与审计。

Squash Commit 标题应保持清晰，例如：

```text
feat(auth): add login rate limiting
fix(web): sync Nuxt lockfile
docs: formalize Git workflow
```

## 9. 合并后的同步与清理

PR 合并后先同步本地主干：

```bash
git switch master
git pull --ff-only origin master
git status
git log --oneline --decorate -5
```

确认本地 `master` 与 `origin/master` 一致。

由于使用 Squash Merge，Git 可能不会将原功能分支识别为“已直接合并”，因此删除本地短期分支时允许使用：

```bash
git branch -D <branch>
```

删除远程分支：

```bash
git push origin --delete <branch>
git fetch --prune
```

最终验证：

```bash
git branch -a
git ls-remote --heads origin <branch>
```

理想长期状态：

```text
* master
  remotes/origin/HEAD -> origin/master
  remotes/origin/master
```

## 10. 单人阶段的评审机制

当前 TechNexus 处于单人开发阶段，无法实现真实的双人审批，因此：

- Required Approvals 保持为 `0`。
- PR 仍然强制存在。
- CI 必须全部通过。
- Review Conversation 必须清零。
- 重要变更必须在 PR 中记录风险、验证和回滚策略。
- 不伪造 Reviewer 或 Approval。

当第二位稳定维护者加入后，应调整为：

- Required Approvals ≥ `1`
- 按模块拆分 CODEOWNERS
- 逐步开启 Require code owner review

## 11. Hotfix

`hotfix/*` 从最新 `master` 创建：

```bash
git switch master
git pull --ff-only origin master
git switch -c hotfix/<name>
```

Hotfix 原则上仍必须通过 PR、Required Checks 和 Squash Merge。

生产事故下若必须使用管理员 bypass：

- 必须记录 bypass 原因。
- 必须保留变更 SHA。
- 必须在恢复后补齐完整测试。
- 必须补充事故 / 风险复盘。

## 12. 版本与 Tag

版本遵循 SemVer：

```text
v0.y.z-alpha.n
vX.Y.Z
```

Tag 只能指向已经通过发布门禁的 `master` Commit。

禁止覆盖已发布 Tag。

发布记录至少包含：

- Git Commit SHA
- Tag
- 构建 ID
- 依赖锁文件状态
- 发布环境

## 13. 数据库变更

数据库 migration 采用前向演进：

- 已执行 migration 不回写。
- 修复通过新 migration 完成。
- PR 必须说明 schema 兼容性。
- 高风险数据变更必须说明回滚或前向修复方案。

## 14. 工作流标准操作

### 开始新任务

```bash
git switch master
git pull --ff-only origin master
git switch -c feature/<name>
```

### 开发完成

```bash
git status
git diff --check
git add <files>
git commit -m "feat(scope): summary"
git push -u origin feature/<name>
```

随后创建 PR，等待 5 个 Required Checks 全绿。

### 合并后

```bash
git switch master
git pull --ff-only origin master
git branch -D feature/<name>
git push origin --delete feature/<name>
git fetch --prune
```

## 15. 变更记录

| 日期 | 版本 | 说明 |
|---|---|---|
| 2026-09-14 | v0.1 | 建立初版 trunk-based、评审、版本和回滚规范 |
| 2026-09-15 | v1.0 | 按真实 GitHub 配置固化为受保护 `master` + PR + 5 项 Required Checks + Squash Merge + 分支清理流程 |
