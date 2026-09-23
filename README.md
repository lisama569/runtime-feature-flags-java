# Runtime Feature Flags（运行时动态配置与功能开关）

同一开关在**多配置来源**与**不同请求上下文**下得到一致、可解释、可回滚的结论。

- 多来源**字段级合并**与热更新，来源缺失 / 取值非法**不静默回退默认值**，拒绝原因三类可区分
- 基于稳定哈希的**分级放量**，比例增减不抖动，结论跨 JVM 可复现
- 不可变快照 + `volatile` 引用，刷新期间读请求**看不到中间态**；刷新失败保持旧配置
- **紧急关闭**（kill switch）与**版本回滚**，判定失败的兜底语义显式可解释

---

## 1. 架构与组件

```
ConfigSource (base[必需] / override[可选])         每次重新读盘 -> 热更新，无需监听
      │  加载失败抛 SourceLoadException(RejectionKind)
      ▼
SnapshotMerger   逐字段合并 + 严格校验 + provenance -> MergeOutcome(快照 | 拒绝列表)
      ▼
FeatureFlagManager  synchronized 刷新 / volatile 不可变快照 / 历史版本 / emergencyKill / rollback
      ▼
FeatureEvaluator  纯函数：快照 + RequestContext -> Decision(on, reason, snapshotId, trace)
      ▼
FeatureFlagController  HTTP 演示入口
```

判定过程通过 SLF4J 打印到单测与应用日志，包含**所依据的快照标识**、每个字段的来源
（provenance）、命中的桶号/阈值与每一步判定轨迹（trace）。

## 2. 配置来源与优先级

两个 properties 文件来源，优先级从低到高：

| 来源 | 默认路径 | 是否必需 | 缺失时行为 |
|------|----------|----------|------------|
| `base` | `config/flags-base.properties` | 必需 | **硬拒绝**（`SOURCE_MISSING`），刷新失败、保持旧快照 |
| `override` | `config/flags-override.properties` | 可选 | 记为 `warnings`（非阻断），base 照常生效，原因可追查 |

- **合并粒度是字段而不是文件**：override 只覆盖它显式定义的字段，其余字段仍取自 base；
  每个字段在快照中都有 provenance，例如 `{enabled=base, percentage=override}`。
- 热更新方式：`refresh()` 每次重新读盘并重建快照，写完文件后调用刷新即可。
- 路径可在 `application.properties` 通过 `feature-flags.base-file` / `feature-flags.override-file` 覆盖。

### 配置文件格式

```properties
# 全局紧急总闸（可被管理端热切换，无需改文件）
global.kill-switch=false

flag.<开关名>.enabled=true|false
flag.<开关名>.percentage=<0~10000 的整数，基点制：10000=100%，5000=50%，1000=10%>
flag.<开关名>.allowlist=visitor-a,visitor-b        # 逗号分隔，不允许空项
flag.<开关名>.blocklist=visitor-c
flag.<开关名>.targeting.<属性名>=<期望值>           # 多条之间为 AND（region + tenant 全部满足）
```

### 非法取值：不静默回退，拒绝原因三类可区分

| RejectionKind | 触发场景 |
|---------------|----------|
| `SOURCE_MISSING` | 必需来源文件不存在 / 不是可读的普通文件 |
| `SOURCE_UNREADABLE` | 文件存在但无法读取，或内容不是合法 properties（如畸形 `\uXXXX`） |
| `INVALID_VALUE` | 布尔值非 true/false；percentage 越界或非整数；名单含空项；同一 visitor 同时在 allow/block 名单；出现未知键名；targeting 属性名非法或值为空 |

**关键语义**：高优先级来源定义了字段但取值非法时，整次刷新被拒绝，
**不会**退回用低优先级取值，也不会退回任何编译期默认值——而是继续使用上一份可用快照。
首次启动即无可用快照时，当前快照为 `null`，判定走显式兜底（见第 4 节）。

## 3. 放量规则与粘性

判定链（顺序即优先级，命中即返回）：

1. 全局 kill switch 开启 → `GLOBAL_KILL_SWITCH`，一律 OFF
2. `enabled=false` → `FLAG_DISABLED`
3. 命中 blocklist → `BLOCKLIST_HIT`（黑名单优先于白名单）
4. 命中 allowlist → `ALLOWLIST_HIT`（与比例无关，永久粘住）
5. 定义了 targeting：上下文属性满足全部规则 → `TARGETING_MATCH`；任一不满足 →
   `TARGETING_NOT_MATCH`（**直接拒绝，不再落到比例放量**）
6. 比例放量：`bucket = FNV1a-64(flagKey + '\\0' + visitorId) mod 10000`，
   `bucket < percentage` 命中 → `ROLLOUT_HIT`，否则 `ROLLOUT_MISS`

**为什么不会抖动**：桶号只取决于 `(开关名, 访问者标识)`，与比例无关。
比例从 p1 调到 p2 时命中集合只做单向迁移——扩容时已命中的不会掉，缩容后回到同一阈值时
结论与之前逐字节一致。开关名参与哈希，避免不同开关把同一用户绑死在同一结论。
哈希为确定性 FNV-1a（不依赖 `String.hashCode()`），跨 JVM / 重启可复现。

## 4. 兜底语义、紧急关闭与回滚

### 判定失败的兜底（显式、可解释，永不向调用方抛异常）

由 `feature-flags.fail-open` 控制，默认 `false`（fail-closed）：

| 场景 | fail-closed（默认） | fail-open |
|------|--------------------|-----------|
| 无任何可用快照 | OFF，原因 `ERROR_NO_CONFIG`，snapshotId=null | ON，原因仍为 `ERROR_NO_CONFIG` |
| 快照存在但开关未定义 | OFF，原因 `ERROR_FLAG_NOT_FOUND` | ON，原因仍为 `ERROR_FLAG_NOT_FOUND` |

即使 fail-open 返回 ON，原因仍带 `ERROR_` 前缀，调用方可区分“正常放量命中”与“故障兜底放开”。

### 紧急关闭

- `POST /admin/flags/kill?on=true`：不经过外部文件，立即合成 kill 快照（版本号为负数标记）
  生效，所有开关一律 OFF；
- kill 生效期间即便来源刷新出新配置也**不会意外解除**（新配置存入历史、活动视图保持 killed）；
- `POST /admin/flags/kill?on=false`：解除，自动回到最新的非 kill 配置。

### 回滚

- 保留最近 `feature-flags.history-size`（默认 10）份**不可变**历史快照（含紧急 kill 快照）；
- `POST /admin/flags/rollback/{version}` 原子切回任意历史版本；被回滚的当前版本仍保留在历史中，
  因此回滚本身也可以再次回滚撤销；版本不存在时返回 `rolledBack=false` 且当前快照不变。

### 一致性保证

- `FlagSnapshot` 及其内部的 `FlagDefinition` 全部不可变，发布后任何代码不再修改它；
- 管理器只持有一个 `volatile` 快照引用，读路径无锁；“加载→合并→换引用”在
  `synchronized` 块内完成，且先构造完整新快照、最后才换引用——读者只能看到旧的或新的完整快照。

## 5. HTTP 接口（演示）

```
GET  /flags/{key}?visitorId=...&region=...&tenant=...   判定，返回 on/reason/snapshotId/trace
POST /admin/flags/refresh                               热更新（成功返回新快照，失败返回 rejections）
POST /admin/flags/kill?on=true|false                    紧急关闭 / 解除
POST /admin/flags/rollback/{version}                    回滚到历史版本
GET  /admin/flags/snapshots                             当前与历史快照列表
```

判定响应示例（注意 trace 中的快照标识、provenance 与桶号）：

```json
{
  "flagKey": "new-checkout", "visitorId": "visitor-1",
  "on": false, "reason": "ROLLOUT_MISS",
  "snapshotId": "snap-v1-1790149011656",
  "trace": [
    "using snapshot snap-v1-1790149011656 version=1",
    "merged definition: enabled=true percentage=5000bp(50.0%) ... provenance={enabled=base, percentage=override}",
    "bucket FNV1a(flag|visitor)=9876/10000 vs threshold=5000 -> ROLLOUT_MISS -> OFF"
  ]
}
```

## 6. 本地验证

要求 JDK 21+、Maven 3.9+。

```bash
# 1) 全量单测（16 个用例；日志中可见 snapshot 标识与判定 trace）
mvn test

# 2) 单独运行各专项测试
mvn test -Dtest=SourcePriorityTest            # 来源字段级优先级 / 必需与可选来源缺失
mvn test -Dtest=InvalidValueRejectionTest     # 三类可区分拒绝原因 / 不静默回退
mvn test -Dtest=RolloutStickinessTest         # 桶号稳定、扩缩容不抖动、名单粘性
mvn test -Dtest=ConcurrentRefreshReadTest     # 80 次刷新 × 6 读线程并发自洽
mvn test -Dtest=RefreshFailureRollbackTest    # 刷新失败保旧 / 回滚 / kill / fail-open|closed

# 3) 启动应用后手工验证（若 8080 被占用可加 --server.port=19090）
mvn spring-boot:run
curl -s "http://localhost:8080/flags/new-checkout?visitorId=visitor-1"
curl -s -X POST "http://localhost:8080/admin/flags/refresh"
curl -s -X POST "http://localhost:8080/admin/flags/kill?on=true"
curl -s      "http://localhost:8080/admin/flags/snapshots"
curl -s -X POST "http://localhost:8080/admin/flags/rollback/1"
```

演示配置位于 `config/flags-base.properties` 与 `config/flags-override.properties`；
热更新只需修改文件后调用 refresh，或制造一次非法值观察刷新被拒绝且旧快照保持服务。

## 7. 测试覆盖对照表

| 需求 | 测试类 |
|------|--------|
| 来源优先级（字段级覆盖、provenance） | `SourcePriorityTest#higherPrioritySourceWinsFieldByField` |
| 必需来源缺失硬拒绝、可选缺失仅告警、旧快照保留 | `SourcePriorityTest` 其余两个用例 |
| 非法取值不静默回退 + 三类拒绝原因可区分 | `InvalidValueRejectionTest` |
| 放量桶号稳定、扩缩容不抖动、结论可复现 | `RolloutStickinessTest#rampUpAndDownNeverFlapsSameVisitors` |
| 白名单在比例降到 0 时仍命中 | `RolloutStickinessTest#allowlistRemainsStickyEvenWhenRolloutShrinks` |
| 刷新期间并发读取看不到中间态、结论与快照自洽 | `ConcurrentRefreshReadTest` |
| 刷新失败保持旧配置、恢复后生效 | `RefreshFailureRollbackTest#rejectedRefreshKeepsPreviousSnapshotThenRecovers` |
| 历史回滚与撤销、不存在版本安全失败 | `RefreshFailureRollbackTest#rollbackRestoresAnyHistoricalVersionAndCanBeUndone` |
| 紧急关闭、刷新不解除、显式解除 | `RefreshFailureRollbackTest#emergencyKillForcesEverythingOffAndIsExplainableThenReleases` |
| fail-open / fail-closed 兜底与 ERROR 原因 | `RefreshFailureRollbackTest#fallbackSemanticsAreExplicitFailClosedAndFailOpen` |
