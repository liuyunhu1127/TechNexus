# Contributing to TechNexus

TechNexus 采用 **受保护 `master` + 短生命周期分支 + Pull Request + Squash Merge** 的 GitHub Flow 工作方式。

## 1. 基本原则

- `master` 是唯一长期分支，始终保持可构建、可验证。
- 禁止直接向 `master` 推送代码。
- 所有变更必须从短生命周期分支发起 Pull Request。
- 合并前必须通过仓库要求的全部 CI 状态检查。
- 仅允许 **Squash Merge**，保持 `master` 线性历史。
- PR 合并后删除功能分支。
- 不提交真实密钥、`.env`、构建产物、依赖目录或无关格式化变更。

## 2. 分支命名

允许使用以下前缀：

| 类型 | 用途 | 示例 |
|---|---|---|
| `feature/` | 新功能 | `feature/user-profile` |
| `fix/` | 普通缺陷修复 | `fix/login-rate-limit` |
| `hotfix/` | 紧急线上修复 | `hotfix/auth-token-expiry` |
| `refactor/` | 不改变业务行为的重构 | `refactor/file-storage` |
| `docs/` | 文档与流程 | `docs/git-workflow-governance` |
| `chore/` | 工程、依赖、工具和维护 | `chore/update-ci-actions` |

分支应从最新 `master` 创建：

```bash
git switch master
git pull --ff-only origin master
git switch -c feature/<short-name>
```

分支尽量保持短生命周期；完成一个逻辑目标后即发起 PR，不在同一分支长期堆积不相关变更。

## 3. Commit 规范

推荐使用 Conventional Commits 风格：

```text
<type>(<scope>): <summary>
```

常用 `type`：

- `feat`: 新功能
- `fix`: 缺陷修复
- `refactor`: 重构
- `test`: 测试
- `docs`: 文档
- `build`: 构建系统或依赖
- `ci`: CI/CD
- `chore`: 维护性工作
- `perf`: 性能优化
- `revert`: 回退

示例：

```text
feat(auth): add login rate limiting
fix(web): sync Nuxt lockfile
docs: formalize Git workflow
```

一个 Commit 应表达一个可理解的逻辑变更。不要把功能修改、批量格式化、依赖升级混在同一个 Commit 中。

## 4. 本地校验

提交前至少执行与变更范围相匹配的校验。

### 后端

以 CI 为最终基准，后端检查包含：

- Maven 构建与测试
- MySQL migration 验证
- Coverage Gate
- CycloneDX SBOM
- OSV 依赖漏洞扫描

### `technexus-web`

CI 使用 Node.js 22；涉及 lockfile 一致性时以 npm `10.9.8` 为基准：

```bash
cd technexus-web
rm -rf node_modules
npx -y npm@10.9.8 ci
npx -y npm@10.9.8 run lint
npx -y npm@10.9.8 run build
npx -y npm@10.9.8 audit --omit=dev --audit-level=high
```

### `technexus-admin`

```bash
cd technexus-admin
npm ci
npm run lint
npm run build
npm audit --omit=dev --audit-level=high
```

### Git 基础检查

在仓库根目录执行：

```bash
git status
git diff --check
```

提交前确认只包含本次目标所需文件。

## 5. Pull Request

PR 必须说明：

- 目的与背景
- 主要变更
- 影响范围
- 风险与兼容性
- 数据库 / API / 安全影响（如适用）
- 已完成的验证
- 回滚方式（如存在运行时风险）
- 截图（UI 变更时）

PR 使用仓库内置模板。

## 6. Required Status Checks

`master` 当前要求以下 5 个状态检查全部通过：

```text
backend
frontend (technexus-admin)
frontend (technexus-web)
dependency-review
secret-scan
```

并启用了 **Require branches to be up to date before merging**。

因此，当 `master` 在 PR 验证后发生变化时，PR 必须基于最新 `master` 重新验证。

不要把 Job 内部步骤（例如 `npm ci`、`npm run lint`）配置成独立 Required Check；Required Check 使用 Job 级名称。

## 7. 合并策略

仓库只允许：

```text
Squash Merge
```

推荐 Squash Commit 标题保持为 Conventional Commit 风格，例如：

```text
feat(auth): add authentication flow
fix(web): sync Nuxt lockfile
docs: formalize Git workflow
```

不得使用普通 merge commit 或 rebase merge 进入 `master`。

## 8. PR 合并后的本地清理

PR 合并后：

```bash
git switch master
git pull --ff-only origin master
git status
git log --oneline --decorate -5
```

确认本地 `master` 与 `origin/master` 一致后删除本地短期分支：

```bash
git branch -D <branch>
```

因为仓库使用 Squash Merge，原功能分支 Commit 不会作为同一提交拓扑直接进入 `master`，所以本地清理时可能需要 `-D`。

删除远程分支：

```bash
git push origin --delete <branch>
git fetch --prune
```

验证：

```bash
git branch -a
git ls-remote --heads origin <branch>
```

正常情况下最终只保留：

```text
* master
  remotes/origin/HEAD -> origin/master
  remotes/origin/master
```

## 9. `master` 保护规则

`master` 当前 Ruleset 应保持：

- 禁止删除
- 禁止 non-fast-forward / force push
- 要求 linear history
- 所有变更必须通过 Pull Request
- 必须解决 Review Conversation
- 只允许 Squash Merge
- 5 个 Required Status Checks
- 要求 PR 分支基于最新 `master`

除已批准的紧急处置外，不应绕过这些规则。

## 10. 单人开发阶段的评审策略

当前项目处于单人开发阶段，因此 Required Approvals 可保持为 `0`，但仍必须通过：

- PR 自审
- CI 门禁
- Review Conversation 清零
- 变更范围和风险检查

当出现第二位稳定维护者后，应将 Required Approvals 调整为至少 `1`，并按模块逐步启用 CODEOWNERS Review。

## 11. 紧急修复

`hotfix/*` 也必须从最新 `master` 创建，并通过 PR 和 CI。

原则上不允许为了赶时间关闭 Required Checks 或直接绕过保护规则。若确有生产事故需要管理员绕过，必须在事后补齐：

- 原因
- 变更内容
- 风险接受
- 验证结果
- 后续修复或复盘记录

## 12. 日常开发命令速记

开始工作：

```bash
git switch master
git pull --ff-only origin master
git switch -c feature/<name>
```

提交并推送：

```bash
git status
git diff --check
git add <files>
git commit -m "feat(scope): summary"
git push -u origin feature/<name>
```

PR 合并后：

```bash
git switch master
git pull --ff-only origin master
git branch -D feature/<name>
git push origin --delete feature/<name>
git fetch --prune
```
