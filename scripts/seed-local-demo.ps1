param(
    [string]$BaseUrl = 'http://127.0.0.1:8080',
    [string]$AdminEmail = 'demo-admin@technexus.local',
    [string]$AdminPassword = $env:TECHNEXUS_DEMO_ADMIN_PASSWORD,
    [string]$UserPassword = $env:TECHNEXUS_DEMO_USER_PASSWORD
)

$ErrorActionPreference = 'Stop'
if (-not $AdminPassword -or -not $UserPassword) {
    throw 'Set TECHNEXUS_DEMO_ADMIN_PASSWORD and TECHNEXUS_DEMO_USER_PASSWORD before seeding.'
}

function Invoke-Api {
    param([string]$Method, [string]$Path, [object]$Body, [string]$Token, [string]$Key)
    $headers = @{}
    if ($Token) { $headers.Authorization = "Bearer $Token" }
    if ($Key) { $headers['Idempotency-Key'] = $Key }
    $parameters = @{
        Method = $Method
        Uri = "$BaseUrl$Path"
        Headers = $headers
        UseBasicParsing = $true
        TimeoutSec = 20
        SkipHttpErrorCheck = $true
    }
    if ($null -ne $Body) {
        $parameters.ContentType = 'application/json; charset=utf-8'
        $parameters.Body = $Body | ConvertTo-Json -Depth 12 -Compress
    }
    $response = Invoke-WebRequest @parameters
    $text = if ($response.Content -is [byte[]]) { [Text.Encoding]::UTF8.GetString($response.Content) } else { [string]$response.Content }
    $parsed = if ($text) { $text | ConvertFrom-Json } else { $null }
    [pscustomobject]@{ Status = [int]$response.StatusCode; Body = $parsed; Text = $text }
}

function Require-Status {
    param([string]$Name, $Response, [int[]]$Expected)
    if ($Response.Status -notin $Expected) {
        $code = if ($Response.Body) { $Response.Body.code } else { '' }
        throw "$Name failed: HTTP $($Response.Status) $code $($Response.Text)"
    }
}

function Ensure-Account {
    param([string]$Email, [string]$DisplayName, [string]$Bio, [string]$Password, [string]$KeyPrefix)
    $login = Invoke-Api POST '/api/v1/auth/login' @{ account = $Email; password = $Password }
    if ($login.Status -eq 401) {
        $register = Invoke-Api POST '/api/v1/auth/register' @{ email = $Email; password = $Password; displayName = $DisplayName } '' "$KeyPrefix-register-v1"
        Require-Status "register $Email" $register @(201, 409)
        $login = Invoke-Api POST '/api/v1/auth/login' @{ account = $Email; password = $Password }
    }
    Require-Status "login $Email" $login @(200)
    $token = [string]$login.Body.accessToken
    $profile = Invoke-Api GET '/api/v1/me' $null $token
    Require-Status "profile $Email" $profile @(200)
    if ([string]$profile.Body.data.bio -ne $Bio -or [string]$profile.Body.data.displayName -ne $DisplayName) {
        $update = Invoke-Api PATCH '/api/v1/me' @{ displayName = $DisplayName; bio = $Bio; expectedVersion = [long]$profile.Body.data.version } $token "$KeyPrefix-profile-v1"
        Require-Status "update profile $Email" $update @(200)
    }
    [pscustomobject]@{ Email = $Email; Token = $token }
}

function Ensure-Tag {
    param([string]$Name, [string[]]$Aliases, [string]$Token, [string]$Key)
    $all = Invoke-Api GET '/api/v1/admin/tags' $null $Token
    Require-Status 'list managed tags' $all @(200)
    $found = @($all.Body.items | Where-Object { $_.name -eq $Name }) | Select-Object -First 1
    if ($found) { return [string]$found.id }
    $created = Invoke-Api POST '/api/v1/admin/tags' @{ name = $Name; aliases = $Aliases; reason = '初始化本机真实感演示数据' } $Token $Key
    Require-Status "create tag $Name" $created @(201)
    [string]$created.Body.data.id
}

function Approve-Target {
    param([string]$TaskId, [string]$Token, [string]$Key)
    $task = $null
    foreach ($state in @('CREATED', 'PENDING', 'PROCESSING', 'APPROVED')) {
        $tasks = Invoke-Api GET "/api/v1/admin/audit-tasks?state=$state" $null $Token
        Require-Status "list $state audit tasks for approval" $tasks @(200)
        $task = @($tasks.Body.items | Where-Object { [string]$_.id -eq $TaskId }) | Select-Object -First 1
        if ($task) { break }
    }
    if (-not $task) { throw "Audit task not found: $TaskId" }
    if ([string]$task.state -eq 'APPROVED') { return }
    if ([string]$task.state -in @('REJECTED', 'CANCELLED')) { throw "Audit task cannot be approved from state $($task.state): $TaskId" }
    $decision = Invoke-Api POST "/api/v1/admin/audit-tasks/$TaskId/decisions" @{
        decision = 'APPROVED'; reasonCode = 'OTHER'; note = '本机演示数据内容完整，批准公开'; expectedTaskVersion = [long]$task.version
    } $Token $Key
    Require-Status "approve audit $TaskId" $decision @(200)
}

$admin = Ensure-Account $AdminEmail 'TechNexus 演示管理员' '负责本机演示环境的内容审核、标签治理和运营配置。' $AdminPassword 'demo-admin'
$architect = Ensure-Account 'chen.lin@demo.technexus.local' '陈林｜后端架构师' '关注 Spring Boot、领域建模、数据库设计与可观测性。' $UserPassword 'demo-chen'
$frontend = Ensure-Account 'wang.wei@demo.technexus.local' '王薇｜前端工程师' '专注 Vue、Nuxt、Web 性能、可访问性与工程化。' $UserPassword 'demo-wang'
$sre = Ensure-Account 'zhao.min@demo.technexus.local' '赵敏｜SRE' '负责持续交付、监控告警、备份恢复与生产安全。' $UserPassword 'demo-zhao'

$tagDefinitions = @(
    @{ name = 'Spring Boot'; aliases = @('Spring', 'Java Web') },
    @{ name = 'Java'; aliases = @('JDK 21') },
    @{ name = 'MySQL'; aliases = @('MySQL 8.4') },
    @{ name = 'Nuxt'; aliases = @('Nuxt 4', 'SSR') },
    @{ name = 'Vue'; aliases = @('Vue 3') },
    @{ name = '可观测性'; aliases = @('Observability', '监控告警') },
    @{ name = '安全工程'; aliases = @('Application Security') },
    @{ name = '架构设计'; aliases = @('DDD', '模块化单体') },
    @{ name = 'DevOps'; aliases = @('CI/CD') },
    @{ name = '备份恢复'; aliases = @('灾难恢复', 'RPO/RTO') }
)
$tags = @{}
for ($index = 0; $index -lt $tagDefinitions.Count; $index++) {
    $definition = $tagDefinitions[$index]
    $tags[$definition.name] = Ensure-Tag $definition.name $definition.aliases $admin.Token ("demo-tag-{0:D2}-v1" -f $index)
}

$contents = @(
    @{
        owner = $architect; type = 'DOCUMENT'; title = 'Spring Boot 4 模块化单体：用领域边界控制复杂度';
        summary = '从包结构、应用端口和领域事件出发，给出单体系统逐步演进的工程实践。';
        tags = @('Spring Boot', 'Java', '架构设计');
        body = @'
## 背景

当团队规模不大、业务仍在快速探索时，模块化单体通常比过早拆分微服务更可靠。关键不是“是否单体”，而是业务边界能否在代码和数据库访问层面得到约束。

## 实践要点

1. 按业务能力划分模块，而不是按 Controller、Service、Repository 做全局分层。
2. 模块只暴露应用端口；跨模块调用不得直接访问对方 Repository。
3. 聚合内部保持强一致，跨聚合使用事务后事件和幂等消费者。
4. 使用 ArchUnit 固化依赖方向，防止边界随迭代逐渐失效。

## 演进策略

先监控调用量、失败率和数据耦合。当某个模块确实需要独立扩缩容或发布节奏时，再沿已有端口拆分，迁移成本会明显低于从无边界的大单体直接拆服务。
'@
    },
    @{
        owner = $sre; type = 'DOCUMENT'; title = 'MySQL 8.4 慢查询排查与索引优化清单';
        summary = '一份可直接用于值班和性能复盘的 MySQL 慢查询诊断流程。';
        tags = @('MySQL', '可观测性', 'DevOps');
        body = @'
## 先确认影响范围

记录接口、Trace ID、SQL 指纹、执行次数、P95/P99、扫描行数和锁等待。不要只凭单次执行时间决定是否加索引。

## 排查顺序

- 使用 `EXPLAIN ANALYZE` 比较估算行数与实际行数。
- 检查联合索引是否符合最左前缀，以及排序、分页是否触发临时表。
- 区分 CPU、磁盘、锁等待和连接池排队，避免把所有问题归因于 SQL。
- 对高频写表评估新增索引带来的写放大和存储成本。

## 上线后验证

保留变更前后相同窗口的样本，确认延迟、扫描行数、连接池等待和磁盘 IO 同时改善，并准备可逆的索引回滚方案。
'@
    },
    @{
        owner = $architect; type = 'POST'; title = '一次 HikariCP 连接池耗尽故障的完整复盘';
        summary = '从告警、定位到修复，复盘连接泄漏与慢 SQL 叠加导致的接口雪崩。';
        tags = @('Spring Boot', 'MySQL', '可观测性');
        body = @'
周一上午流量上升后，接口首先出现 Hikari pending connection，随后 5xx 比例快速增加。线程转储显示多数请求阻塞在获取数据库连接，慢查询日志同时出现一条缺少复合索引的报表 SQL。

短期处置是限制报表入口并回滚上一版本，而不是直接扩大连接池。根因包含两部分：异常分支未及时关闭流式查询，以及报表条件未命中索引。修复后补充了连接池等待、事务时长和 SQL 指纹告警，并用故障注入确认连接能够回收。

最大的教训是：连接池耗尽通常是结果，不是根因。只调大池子会把压力继续传递到数据库。
'@
    },
    @{
        owner = $frontend; type = 'DOCUMENT'; title = 'Nuxt 4 SSR 的 SEO、缓存与错误页设计';
        summary = '面向内容平台的 SSR 元数据、缓存边界、404/noindex 和可访问性实践。';
        tags = @('Nuxt', 'Vue', '架构设计');
        body = @'
## 页面级元数据

公开内容应为每个实体提供稳定 URL、唯一标题和简洁描述。不存在的资源返回真实 404，并设置 `noindex, nofollow`，避免搜索引擎收录软 404。

## 缓存边界

只缓存匿名可见且不含个性化信息的响应。登录态页面、权限相关 API 和预签名下载地址不得进入共享缓存。更新内容后应按实体键精确失效，而不是清空全部缓存。

## 用户体验

SSR 不能替代可访问性：仍需语义化标题、跳转正文、清晰焦点、足够对比度和不依赖颜色的状态表达。错误页应告诉用户发生了什么，并提供可恢复的下一步。
'@
    },
    @{
        owner = $sre; type = 'DOCUMENT'; title = '对象存储上传安全：Magic、哈希、病毒扫描与授权下载';
        summary = '文件进入可用态前必须经过的校验、隔离、扫描和权限关系设计。';
        tags = @('安全工程', '架构设计', 'DevOps');
        body = @'
文件名和客户端 MIME 都不可信。上传完成后，服务端应重新计算实际大小与 SHA-256，识别文件 Magic，并在隔离区完成病毒扫描。任一步骤失败，文件都不能进入 AVAILABLE。

物理对象可以按哈希复用，但授权关系必须按用户和业务实体独立保存。下载时查询关系表，而不是因为“对象已经存在”就允许访问。预签名 URL 有效期应尽可能短，并记录申请者、目标和结果。

清理任务需要同时处理过期上传会话、孤立临时对象和失败重试；删除前再次确认没有有效关系引用。
'@
    },
    @{
        owner = $frontend; type = 'POST'; title = '如何为个人服务器设计可恢复的备份链路';
        summary = '在资源有限的前提下，实现可验证、可告警、可恢复的数据库备份。';
        tags = @('备份恢复', 'MySQL', 'DevOps');
        body = @'
备份成功不等于能够恢复。一个最低可用的链路至少包含：一致性导出、压缩、加密、远端副本、哈希校验、备份年龄监控和定期隔离恢复。

每日任务应产生机器可读清单，记录数据库版本、迁移版本、表数量、关键行数、文件哈希和执行时间。告警不仅覆盖脚本失败，还要覆盖“任务根本没有启动”。

恢复演练必须使用独立实例，校验迁移版本和关键业务事实，并记录实际 RPO/RTO。没有非同主机副本时，不能把本机复制称为灾备。
'@
    }
)

$publicContents = @( (Invoke-Api GET '/api/v1/contents').Body.items )
$createdContents = [Collections.Generic.List[object]]::new()
for ($index = 0; $index -lt $contents.Count; $index++) {
    $item = $contents[$index]
    $existing = @($publicContents | Where-Object { $_.title -eq $item.title }) | Select-Object -First 1
    if ($existing) { $createdContents.Add($existing); continue }
    $tagIds = @($item.tags | ForEach-Object { $tags[$_] })
    $create = Invoke-Api POST '/api/v1/contents' @{
        type = $item.type; visibility = 'PUBLIC'; title = $item.title; summary = $item.summary
        body = $item.body; tagIds = $tagIds; fileIds = @()
    } $item.owner.Token ("demo-content-{0:D2}-create-v3" -f $index)
    Require-Status "create content $($item.title)" $create @(201)
    $submit = Invoke-Api POST "/api/v1/contents/$($create.Body.data.id)/submit" @{ expectedVersion = [long]$create.Body.data.version } $item.owner.Token ("demo-content-{0:D2}-submit-v3" -f $index)
    Require-Status "submit content $($item.title)" $submit @(202)
    Approve-Target ([string]$submit.Body.data.auditTaskId) $admin.Token ("demo-content-{0:D2}-approve-v2" -f $index)
    $createdContents.Add((Invoke-Api GET "/api/v1/contents/$($create.Body.data.id)").Body.data)
}

$demands = @(
    @{ owner = $architect; title = '建设面向研发团队的统一知识搜索与推荐'; description = "现有技术文档分散在 Wiki、Git 仓库和工单系统中。希望建设统一搜索入口，支持内容、需求、用户和标签检索，提供相关度与最新排序，并允许运营置顶高价值内容。`n`n验收重点：权限隔离、中文搜索质量、分页稳定性、索引可重建和可观测性。"; min='18000.00'; max='35000.00' },
    @{ owner = $sre; title = '为 Spring Boot 服务补齐可观测性与告警闭环'; description = "需要为现有服务补充 JSON 日志、Request/Trace ID、健康探针、JVM/Hikari/MySQL/队列指标，并接入 Prometheus 与 Alertmanager。`n`n要求提供 5xx、数据库、连接池、积压、磁盘和备份告警，完成一次触发、通知送达与恢复演练。"; min='12000.00'; max='26000.00' },
    @{ owner = $frontend; title = '将旧 Vue SPA 渐进迁移到 Nuxt SSR'; description = "当前内容站点为 Vue SPA，公开页面首屏和搜索收录较差。计划保持 API 不变，先迁移首页、内容详情、登录和错误页，再逐步替换其余路由。`n`n需交付 SEO 元数据、响应式布局、键盘可用性、性能预算和可回滚发布方案。"; min='20000.00'; max='42000.00' },
    @{ owner = $architect; title = '设计 MySQL 异地备份、健康检查与恢复演练自动化'; description = "个人服务器上的 MySQL 需要每日加密备份到非同主机位置，保留 30 个恢复点。`n`n脚本应包含一致性 dump、压缩加密、原子复制、SHA-256、备份年龄/容量告警和隔离恢复验证，并输出机器可读证据。"; min='8000.00'; max='18000.00' }
)

$publicDemands = @((Invoke-Api GET '/api/v1/demands').Body.items)
$createdDemands = [Collections.Generic.List[object]]::new()
for ($index = 0; $index -lt $demands.Count; $index++) {
    $item = $demands[$index]
    $existing = @($publicDemands | Where-Object { $_.title -eq $item.title }) | Select-Object -First 1
    if ($existing) { $createdDemands.Add($existing); continue }
    $create = Invoke-Api POST '/api/v1/demands' @{
        title = $item.title; description = $item.description
        budgetMin = @{ amount = $item.min; currency = 'CNY' }; budgetMax = @{ amount = $item.max; currency = 'CNY' }
        deadlineAt = [DateTimeOffset]::UtcNow.AddDays(45 + $index * 15).ToString('o'); visibility = 'PUBLIC'
    } $item.owner.Token ("demo-demand-{0:D2}-create-v2" -f $index)
    Require-Status "create demand $($item.title)" $create @(201)
    $submit = Invoke-Api POST "/api/v1/demands/$($create.Body.data.id)/submit" @{ expectedVersion = [long]$create.Body.data.version } $item.owner.Token ("demo-demand-{0:D2}-submit-v2" -f $index)
    Require-Status "submit demand $($item.title)" $submit @(202)
    Approve-Target ([string]$submit.Body.data.auditTaskId) $admin.Token ("demo-demand-{0:D2}-approve-v2" -f $index)
    $price = Invoke-Api POST "/api/v1/admin/prices/DEMAND/$($create.Body.data.id)/confirmations" @{
        mode = 'FREE'; amount = @{ amount = '0.00'; currency = 'CNY' }; validFrom = [DateTimeOffset]::UtcNow.ToString('o')
        reason = '本机演示需求采用免费发布模式'; expectedVersion = 0
    } $admin.Token ("demo-demand-{0:D2}-price-v2" -f $index)
    Require-Status "price demand $($item.title)" $price @(200)
    $createdDemands.Add((Invoke-Api GET "/api/v1/demands/$($create.Body.data.id)").Body.data)
}

$commentBodies = @(
    '模块边界用 ArchUnit 固化这一点很重要。我们曾经只写在文档里，半年后 Repository 已经被跨模块直接调用。',
    '排查清单很实用，建议再记录优化前后的 rows examined 和连接池等待时间，方便确认收益。',
    '同意“连接池耗尽是结果”这个判断。我们还会同时观察事务时长和下游超时，避免只盯连接数。'
)
for ($index = 0; $index -lt [Math]::Min(3, $createdContents.Count); $index++) {
    $content = $createdContents[$index]
    $comments = Invoke-Api GET "/api/v1/contents/$($content.id)/comments"
    if (@($comments.Body.items).Count -eq 0) {
        $comment = Invoke-Api POST "/api/v1/contents/$($content.id)/comments" @{ body = $commentBodies[$index]; parentId = $null } $sre.Token ("demo-comment-{0:D2}-v1" -f $index)
        Require-Status "comment content $($content.id)" $comment @(201)
        if ($index -lt 2) {
            $reply = Invoke-Api POST "/api/v1/contents/$($content.id)/comments" @{ body = '感谢补充，这个指标我会加入后续的验证模板。'; parentId = [string]$comment.Body.data.id } $architect.Token ("demo-reply-{0:D2}-v1" -f $index)
            Require-Status "reply content $($content.id)" $reply @(201)
        }
    }
}

$proposalPlans = @(
    @{ user=$frontend; stack=@('Nuxt 4','Spring Boot 4','MySQL 8.4','OpenSearch'); days=28; quote='29800.00'; plan='先完成数据源盘点与权限模型，再实现统一搜索 API、Nuxt 搜索页和运营推荐配置。两周交付可用版本，随后进行相关度调优、性能测试和上线观察。' },
    @{ user=$architect; stack=@('Spring Boot Actuator','Micrometer','Prometheus','Alertmanager'); days=18; quote='22000.00'; plan='分三步交付：统一日志与关联 ID、补齐应用和数据库指标、完成告警触发与恢复演练。所有规则附运行手册和可重复验证脚本。' },
    @{ user=$frontend; stack=@('Nuxt 4','Vue 3','Vite','Playwright'); days=35; quote='36000.00'; plan='采用路由级渐进迁移，先建立共享 API 客户端和设计令牌，再迁移公开路由。每批次保留旧路由回退，使用真实浏览器覆盖 SEO、响应式和可访问性。' },
    @{ user=$sre; stack=@('PowerShell 7','MySQL 8.4','AES-256','Windows Task Scheduler'); days=14; quote='15000.00'; plan='实现加密备份、远端原子复制、健康检查和隔离恢复脚本；配置每日任务及 Webhook 告警，最后执行一次完整恢复并提交 RPO/RTO 证据。' }
)
for ($index = 0; $index -lt [Math]::Min($createdDemands.Count, $proposalPlans.Count); $index++) {
    $demand = $createdDemands[$index]; $proposal = $proposalPlans[$index]
    $create = Invoke-Api POST "/api/v1/demands/$($demand.id)/proposals" @{
        plan = $proposal.plan; techStack = $proposal.stack; estimatedDays = $proposal.days
        suggestedQuote = @{ amount = $proposal.quote; currency = 'CNY' }
    } $proposal.user.Token ("demo-proposal-{0:D2}-v1" -f $index)
    Require-Status "proposal demand $($demand.id)" $create @(201, 409)
}

$finalContents = @((Invoke-Api GET '/api/v1/contents').Body.items)
$finalDemands = @((Invoke-Api GET '/api/v1/demands').Body.items)
$finalTags = @((Invoke-Api GET '/api/v1/tags').Body.items)
[pscustomobject]@{
    result = 'PASS'
    demoAccounts = 4
    requestedContents = $contents.Count
    requestedDemands = $demands.Count
    publicContentsTotal = $finalContents.Count
    publicDemandsTotal = $finalDemands.Count
    activeTagsTotal = $finalTags.Count
    demoContentIds = @($createdContents | ForEach-Object id)
    demoDemandIds = @($createdDemands | ForEach-Object id)
} | ConvertTo-Json -Depth 4
