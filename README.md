# 运行时动态配置与功能开关（Runtime Feature Flags）

同一开关在**多配置来源**与**不同请求上下文**下，给出一致、可解释、可回滚的结论。

- 多来源按优先级合并，支持运行时热更新；来源缺失或取值非法时**不静默回退默认值**，
  拒绝原因可区分，并继续使用上一份可用配置。
- 基于稳定哈希分桶的分级放量：比例/名单/定向变化时结论稳定可复现，放量增减不抖动。
- 刷新与读取并发安全：读请求只看到完整发布的不可变快照；刷新失败保旧；支持紧急关闭与一键回滚。
- **按开关临时停用（局部层紧急控制）**：单个或批量停用，带原因与操作人；
  停用跨刷新/推送/刷新失败持续有效，仅显式恢复或到期解除；支持到期自动恢复、
  与全局总闸叠加时分层归因、容量上限可区分拒绝。
- 判定全程打印**所依据的配置快照标识 + 命中的停用记录 + 各字段出处来源 + 判定过程**，结论可追溯。

## 1. 代码结构

```
flag/
├── model/      领域模型（RequestContext / FlagRule(含字段出处) / ConfigSnapshot / EvaluationResult(含 disableId) / EvaluationReason）
├── source/     配置来源抽象 ConfigSource 与可热更新的 InMemoryConfigSource
├── config/     FlagConfigParser（严格解析校验+字段出处）、SnapshotAssembler（优先级合并+快照组装）、Spring 装配
├── eval/       FlagEvaluator（确定性判定：总闸/局部停用/白名单/定向/稳定分桶）
├── disable/    停用控制面（DisableDirective / DisableSet / DisableView / ControlState / DisableOutcome / DisableRejectionReason）
├── service/    FeatureFlagManager（热刷新、快照切换、紧急关闭、临时停用/恢复、回滚、无锁读取）
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
2. `kill.switch=true` → `KILL_SWITCH_ACTIVE`，一律 **OFF**（全局层）
3. 命中生效中的局部停用 → `FLAG_TEMPORARILY_DISABLED`，**OFF**（局部层）
4. 开关未定义 → `FLAG_NOT_FOUND`，**OFF（fail-CLOSED）**
5. `enabled=false` → `MASTER_SWITCH_OFF`，**OFF**
6. 命中 `allowlist` → `ALLOWLIST_MATCH`，**ON**（白名单不受比例与定向限制）
7. 定向条件存在且有任一不满足 → `TARGETING_NOT_MATCHED`，**OFF**
8. 稳定分桶 `bucket = SHA256(salt + "|" + visitorId) 前4字节 mod 100`，
   `bucket < percentage` 为命中（`PERCENTAGE_BUCKETED`）。

**粘性**：桶位只由 `salt + visitorId` 决定，与当前 `percentage` 无关。
放量比例只是移动阈值，不重排桶位：

- 比例 0→100 放大时，某访问者最多经历一次 OFF→ON，不会被反复"开-关"；
- 比例 100→0 收缩时同理只发生 ON→OFF；
- 白名单、定向条件变化属于规则变更，按新规则独立判定；
- 同一内容的配置在不同快照版本下结论逐项可复现（快照标识含版本号+内容 SHA-256 指纹）。

## 4. 按开关临时停用（局部层紧急控制）

全局总闸之外提供更细粒度的紧急控制：单个或一组开关的临时停用。

### 4.1 停用规则

- `disable(flag, reason, operator[, ttlMillis])` / `disableAll(flags, reason, operator, ttlMillis)`：
  停用必须携带**原因**与**操作人**；`ttlMillis` 省略表示**永久停用**（只能显式恢复），
  传入正数表示到点**自动恢复**。批量停用是**原子**的：整批生效或整批拒绝。
- **持久性**：停用一旦生效，后续任何配置刷新、来源推送、甚至刷新失败都**不得解除**它；
  刷新/紧急关闭/回滚只替换配置快照，停用集合原样携带。只有两种解除途径：
  显式 `resume(flag)`，或到达 `expiresAt` 到期点。
- **到期语义**：到期采用惰性判定（判定/查询时按当前时间过滤），到点即自动恢复，
  无需后台线程；恢复后该开关的结论与"从未被停用、直接按当前配置判定"**完全一致**
  （停用层不参与任何分桶/规则计算）。
- **多条指令命中同一开关**：合并为一个说得清的视图（`DisableView`）——
  保留全部指令 id、去重拼接全部原因与操作人、创建时间取最早、
  到期时间取最晚（任一永久则整体永久）、基准快照取最早指令生效时的版本。
- **与全局总闸叠加**：总闸层优先短路，结论 `reason=KILL_SWITCH_ACTIVE`（全局层），
  同时 `disableId` 带出命中的局部停用记录，判定细节明确标注两层；
  仅局部停用时 `reason=FLAG_TEMPORARILY_DISABLED`（局部层）。两层归因不会糊在一起。

### 4.2 容量限制

- 生效中停用条目数上限默认 **128**（`FeatureFlagManager(sources, maxDisables, clock)` 可调）。
- 变更前先做惰性到期清理再计数；新增会超限时**整体拒绝**，原因码
  `DisableRejectionReason.CAPACITY_EXCEEDED`，**现状不变、绝不静默丢条目**。
- 其他可区分拒绝：`INVALID_REQUEST`（空开关名/缺原因或操作人/非正 TTL）、
  `NOT_DISABLED`（恢复一个当前没有生效停用的开关）。

### 4.3 查询与对账

- `activeDisables()` / `GET /api/admin/disables`：返回当前生效停用的合并视图——
  开关名、指令 id 列表、原因、操作人、创建时间、到期时间（null=永久）、
  **生效时基于的配置快照标识**。
- 每次判定日志包含：配置快照标识、命中的停用记录（`disable=`）、
  开关各字段分别来自哪个配置来源（`origins=`，取缺省值的字段标记 `<default>`）：

```
[FLAG-EVAL] snapshot=v2-667a5cc3ef07 flag=feature.hotfix visitor=user-1 decision=OFF reason=FLAG_TEMPORARILY_DISABLED disable=d-1 origins=- | 开关被临时停用（局部层）: disable=d-1, reason=线上资损排查, operator=sre-lisa, baseSnapshot=v1-b6cae87b1d89, expiresAt=永久(需显式恢复)
[FLAG-EVAL] snapshot=v1-876c435835ab flag=feature.demo visitor=user-1 decision=ON reason=PERCENTAGE_BUCKETED disable=- origins={salt=<default>, allowlist=<default>, enabled=local-defaults, percentage=remote-center} | 稳定分桶: ...
```

## 5. 并发一致性、兜底与回滚语义

- `ConfigSnapshot` 与 `DisableSet` 均不可变，并配对成 `ControlState` 由一个
  `volatile` 引用整体发布：读请求无锁，任意时刻拿到的都是一对完整一致的
  （快照, 停用集合），**看不到半生效状态**（包括批量停用只生效一半）。
- 刷新 / 紧急关闭 / 停用 / 恢复 / 回滚用同一把锁串行，版本号单调递增；
  新状态整体构造成功后才原子发布。
- **刷新失败保旧**：见第 2 节。
- **紧急关闭** `emergencyShutdown(reason)`：保留当前全部规则，仅把总闸置 true 发布 `vN-KILL`，
  之后所有开关以 `KILL_SWITCH_ACTIVE` 关闭（关闭前快照进入历史）。
- **回滚** `rollback()`：弹出历史栈顶并发布，可从紧急关闭一键恢复到关闭前的完整判定结论；
  历史栈有界（保留最近 10 份）；无历史时返回失败且当前配置不变。
  回滚只作用于配置快照，**不影响**生效中的停用。
- **判定兜底**：任何判定过程异常都返回 fail-CLOSED 结论，原因码 `EVALUATION_ERROR`，
  即"不确定就关闭"，绝不误放量。

## 6. HTTP 接口

| 方法与路径 | 说明 |
|---|---|
| `GET /api/flags/{key}/evaluate?visitorId=..&attr.channel=beta` | 判定查询（`attr.` 前缀参数作为定向属性） |
| `POST /api/admin/refresh` | 热刷新（失败保旧，返回原因） |
| `POST /api/admin/kill?reason=..` | 紧急关闭（全局总闸） |
| `POST /api/admin/rollback` | 回滚到上一份快照 |
| `POST /api/admin/disable?flag=a,b&reason=..&operator=..&ttlMillis=..` | 临时停用（逗号分隔可批量；`ttlMillis` 省略=永久） |
| `POST /api/admin/resume?flag=..` | 显式恢复某开关 |
| `GET /api/admin/disables` | 查询当前生效中的停用（合并视图） |

## 7. 本地验证方法

```bash
# 全量测试（22 个用例，日志直接打印到控制台，可看到每个场景的快照/停用/判定细节）
mvn test

# 只跑停用相关用例
mvn test -Dtest=TemporaryDisableTest,ConcurrentDisableConsistencyTest

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
| `TemporaryDisableTest` | 停用跨好/坏刷新持续有效、到期自动恢复且结论与从未停用一致、总闸+局部停用分层归因、多指令合并视图、容量超限拒绝且现状不变、查询视图字段与非法请求 |
| `ConcurrentDisableConsistencyTest` | 停用/恢复/刷新/判定并发：命中停用必带 disableId、无 `EVALUATION_ERROR`、生效停用数永不超容量 |

手动冒烟示例：

```bash
curl -s "http://127.0.0.1:18091/api/flags/feature.checkout-redesign/evaluate?visitorId=vip-001&attr.channel=beta"
# -> enabled=true, reason=ALLOWLIST_MATCH

# 局部停用单个开关（10 分钟自动恢复），不影响其他开关
curl -s -X POST "http://127.0.0.1:18091/api/admin/disable?flag=feature.checkout-redesign&reason=资损排查&operator=sre-lisa&ttlMillis=600000"
curl -s "http://127.0.0.1:18091/api/flags/feature.checkout-redesign/evaluate?visitorId=vip-001&attr.channel=beta"
# -> enabled=false, reason=FLAG_TEMPORARILY_DISABLED, disableId=d-1
curl -s "http://127.0.0.1:18091/api/admin/disables"
# -> 当前生效停用：原因/操作人/到期时间/基准快照
curl -s -X POST "http://127.0.0.1:18091/api/admin/resume?flag=feature.checkout-redesign"
# -> 显式恢复，判定回到按配置计算

curl -s -X POST "http://127.0.0.1:18091/api/admin/kill?reason=incident"
# -> 所有开关 OFF, reason=KILL_SWITCH_ACTIVE（若同时有局部停用，disableId 一并带出）
curl -s -X POST "http://127.0.0.1:18091/api/admin/rollback"
# -> 恢复关闭前快照与结论
```
