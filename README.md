# 运行时动态配置与功能开关（Runtime Feature Flags）

同一开关在**多配置来源**与**不同请求上下文**下，给出一致、可解释、可回滚的结论。

- 多来源按优先级合并，支持运行时热更新；来源缺失或取值非法时**不静默回退默认值**，
  拒绝原因可区分，并继续使用上一份可用配置。
- 基于稳定哈希分桶的分级放量：比例/名单/定向变化时结论稳定可复现，放量增减不抖动。
- 刷新与读取并发安全：读请求只看到完整发布的不可变快照；刷新失败保旧；支持紧急关闭与一键回滚。
- 判定全程打印**所依据的配置快照标识 + 判定过程**，结论可追溯。

## 1. 代码结构

```
flag/
├── model/      领域模型（RequestContext / FlagRule / ConfigSnapshot / EvaluationResult / EvaluationReason）
├── source/     配置来源抽象 ConfigSource 与可热更新的 InMemoryConfigSource
├── config/     FlagConfigParser（严格解析校验）、SnapshotAssembler（优先级合并+快照组装）、Spring 装配
├── eval/       FlagEvaluator（确定性判定：总闸/白名单/定向/稳定分桶）
├── service/    FeatureFlagManager（热刷新、快照切换、紧急关闭、回滚、无锁读取）
└── web/        查询与运维 HTTP 接口
```

## 2. 配置来源与优先级

- 一个来源实现 `ConfigSource`：`name()`、`priority()`（**数值越大优先级越高**）、`load()`。
- 刷新时按优先级**升序**加载并合并：同一键被高优先级来源覆盖；不同来源的独有键并存。
- 内置两个演示来源（见 `FlagSpringConfig`）：
  - `local-defaults`，priority=10：本地基线；
  - `remote-center`，priority=100：模拟远端配置中心，同键覆盖本地。
- `InMemoryConfigSource.setProperties(...)` 即可模拟一次远端推送（热更新）；
  `setUnavailable(true)` 模拟来源缺失/不可达。

原始配置采用扁平键值对，开关名与配置键保持一致：

| 键 | 必填 | 说明 |
|---|---|---|
| `kill.switch` | 否 | 全局总闸，`true`/`false`；非法取值拒绝整份快照 |
| `feature.<开关名>.enabled` | **是** | 开关总开关，缺失即拒绝（不用默认值顶替） |
| `feature.<开关名>.percentage` | 否 | 放量比例，0..100 整数，默认 0（显式安全缺省） |
| `feature.<开关名>.allowlist` | 否 | 逗号分隔的访问者白名单，默认空 |
| `feature.<开关名>.target.<属性>` | 否 | 定向条件，可多条；**全部**命中才进入分桶 |
| `feature.<开关名>.salt` | 否 | 分桶盐值，默认取开关短名 |

### 非法取值与来源缺失：整体拒绝，绝不部分生效

整份候选快照只要存在以下任一问题，就被**整体拒绝**（不发布、不部分生效），
`RefreshOutcome.accepted=false` 并带可区分的原因：

- `SOURCE_UNAVAILABLE`：某来源 `load()` 失败/返回空；细节携带来源名；
- `INVALID_VALUE`：细分 `ConfigProblemType` —
  `MISSING_FIELD` / `INVALID_BOOLEAN` / `INVALID_INTEGER` /
  `INVALID_PERCENTAGE_RANGE` / `INVALID_KEY` / `UNKNOWN_FIELD`，细节携带问题类型、原始键与原始值。

被拒绝后**继续使用上一份可用快照**；失败的刷新不产生新版本、不进入回滚历史。
若系统从未成功加载过任何快照，所有判定走 `NO_CONFIG_LOADED` 兜底（fail-CLOSED）。

## 3. 分级放量规则与粘性

判定按以下顺序短路（对应 `EvaluationReason`）：

1. 无已加载快照 → `NO_CONFIG_LOADED`，**OFF（fail-CLOSED）**
2. `kill.switch=true` → `KILL_SWITCH_ACTIVE`，一律 **OFF**
3. 开关未定义 → `FLAG_NOT_FOUND`，**OFF（fail-CLOSED）**
4. `enabled=false` → `MASTER_SWITCH_OFF`，**OFF**
5. 命中 `allowlist` → `ALLOWLIST_MATCH`，**ON**（白名单不受比例与定向限制）
6. 定向条件存在且有任一不满足 → `TARGETING_NOT_MATCHED`，**OFF**
7. 稳定分桶 `bucket = SHA256(salt + "|" + visitorId) 前4字节 mod 100`，
   `bucket < percentage` 为命中（`PERCENTAGE_BUCKETED`）。

**粘性**：桶位只由 `salt + visitorId` 决定，与当前 `percentage` 无关。
放量比例只是移动阈值，不重排桶位：

- 比例 0→100 放大时，某访问者最多经历一次 OFF→ON，不会被反复"开-关"；
- 比例 100→0 收缩时同理只发生 ON→OFF；
- 白名单、定向条件变化属于规则变更，按新规则独立判定；
- 同一内容的配置在不同快照版本下结论逐项可复现（快照标识含版本号+内容 SHA-256 指纹）。

## 4. 并发一致性、兜底与回滚语义

- `ConfigSnapshot` 不可变，`FeatureFlagManager` 以一个 `volatile` 引用指向当前快照：
  读请求无锁，刷新期间要么看到旧快照、要么看到新快照，**看不到半生效状态**。
- 刷新 / 紧急关闭 / 回滚用同一把锁串行，版本号单调递增；新快照整体构造成功后才原子发布。
- **刷新失败保旧**：见第 2 节。
- **紧急关闭** `emergencyShutdown(reason)`：保留当前全部规则，仅把总闸置 true 发布 `vN-KILL`，
  之后所有开关以 `KILL_SWITCH_ACTIVE` 关闭（关闭前快照进入历史）。
- **回滚** `rollback()`：弹出历史栈顶并发布，可从紧急关闭一键恢复到关闭前的完整判定结论；
  历史栈有界（保留最近 10 份）；无历史时返回失败且当前配置不变。
- **判定兜底**：任何判定过程异常都返回 fail-CLOSED 结论，原因码 `EVALUATION_ERROR`，
  即"不确定就关闭"，绝不误放量。

每次判定打印日志，例如：

```
[FLAG-EVAL] snapshot=v1-0e93c309ec5a flag=feature.checkout-redesign visitor=normal-55 decision=OFF reason=PERCENTAGE_BUCKETED | 稳定分桶: salt=checkout-redesign, visitorId=normal-55, bucket=92, percentage=30 => 未命中(bucket>=percentage)
```

## 5. HTTP 接口

| 方法与路径 | 说明 |
|---|---|
| `GET /api/flags/{key}/evaluate?visitorId=..&attr.channel=beta` | 判定查询（`attr.` 前缀参数作为定向属性） |
| `POST /api/admin/refresh` | 热刷新（失败保旧，返回原因） |
| `POST /api/admin/kill?reason=..` | 紧急关闭（全局总闸） |
| `POST /api/admin/rollback` | 回滚到上一份快照 |

## 6. 本地验证方法

```bash
# 全量测试（15 个用例，日志直接打印到控制台）
mvn test

# 打包并启动（端口可改）
mvn package -DskipTests
java -jar target/runtime-feature-flags-java-1.0-SNAPSHOT.jar --server.port=18091
```

测试覆盖（位于 `src/test/.../tests/`）：

| 测试类 | 覆盖点 |
|---|---|
| `SourcePriorityTest` | 来源优先级覆盖、独有键并存、热更新后结论变化且可复现 |
| `InvalidConfigRejectionTest` | 六类非法取值拒绝原因可区分、来源缺失可区分到来源名、失败保旧、不静默用默认值顶替 |
| `RolloutStickinessTest` | 0→100→0 放量单调无抖动、同内容刷新可复现、白名单旁路比例、定向先于比例拦截 |
| `ConcurrentRefreshConsistencyTest` | 8 读线程 + 1 刷新线程穿插好/坏/缺失配置，校验无 `EVALUATION_ERROR`、无空快照标识 |
| `RefreshFailureAndRollbackTest` | 失败保旧、紧急关闭、回滚恢复、无历史回滚被拒、NO_CONFIG_LOADED/FLAG_NOT_FOUND 等兜底 |

手动冒烟示例：

```bash
curl -s "http://127.0.0.1:18091/api/flags/feature.checkout-redesign/evaluate?visitorId=vip-001&attr.channel=beta"
# -> enabled=true, reason=ALLOWLIST_MATCH
curl -s -X POST "http://127.0.0.1:18091/api/admin/kill?reason=incident"
# -> 所有开关 OFF, reason=KILL_SWITCH_ACTIVE
curl -s -X POST "http://127.0.0.1:18091/api/admin/rollback"
# -> 恢复关闭前快照与结论
```
