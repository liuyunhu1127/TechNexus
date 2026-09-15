## Summary

<!-- 用 1–3 句话说明本 PR 要解决的问题和目标。 -->

## Change Type

- [ ] feat — 新功能
- [ ] fix — 缺陷修复
- [ ] refactor — 重构
- [ ] test — 测试
- [ ] docs — 文档
- [ ] build / ci / chore — 工程维护
- [ ] hotfix — 紧急修复

## Changes

<!-- 列出核心变更，避免复制 Commit 列表。 -->

-

## Scope / Impact

<!-- 说明涉及的模块、API、数据库、前端、配置、安全边界等。 -->

- Modules:
- API:
- Database migration:
- Configuration:
- Security impact:

## Validation

<!-- 只勾选真正执行过的项；不适用时说明 N/A。 -->

- [ ] `git diff --check`
- [ ] Backend build / tests
- [ ] Database migration verification
- [ ] Coverage gate
- [ ] `technexus-web` `npm ci`
- [ ] `technexus-web` lint
- [ ] `technexus-web` build
- [ ] `technexus-web` audit
- [ ] `technexus-admin` `npm ci`
- [ ] `technexus-admin` lint
- [ ] `technexus-admin` build
- [ ] `technexus-admin` audit
- [ ] Secret scan
- [ ] Dependency / vulnerability scan

Validation notes:

```text
Paste key commands/results here when useful.
```

## Risk

Risk level:

- [ ] Low
- [ ] Medium
- [ ] High

Known risks:

-

## Rollback

<!-- 对运行时行为有影响时说明如何回退；纯文档可写 N/A。 -->

-

## UI Evidence

<!-- UI 变更时添加截图；否则写 N/A。 -->

N/A

## Final Checklist

- [ ] 本 PR 只包含一个清晰目标
- [ ] 未提交真实密钥、`.env`、依赖目录或构建产物
- [ ] 已检查无无关格式化或生成文件
- [ ] 已考虑向后兼容性
- [ ] 已考虑数据库 migration 的前向兼容和回退策略
- [ ] 已考虑权限 / 认证 / 输入校验等安全影响
- [ ] 已解决所有 Review Conversation
- [ ] 所有 Required Status Checks 已通过
- [ ] 分支已基于最新 `master`
- [ ] 合并方式使用 Squash Merge
