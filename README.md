# 运行时动态配置与功能开关（Runtime Feature Flags）

同一开关在**多配置来源**与**不同请求上下文**下，给出一致、可解释、可回滚的结论。

- 多来源按优先级合并，支持运行时热更新；来源缺失或取值非法时**不静默回退默认值**，
  拒绝原因可区分，并继续使用上一份可用配置。
- 基于稳定哈希分桶的分级放量：比例/名单/定向变化时结论稳定可复现，放量增减不抖动。
- **按开关紧急停用**：可对单个或一组开关临时停用（带原因、操作人、可选 TTL），
  独立于任何配置刷新——刷新成功/失败、来源推送、回滚都不能无声解除；
  只能显式恢复或到 TTL 自动解除。生效条目有数量上限，超限整批拒绝、现状不变。
- 刷新、停用、恢复、读取并发安全：读请求只看到"配置快照 + 停用集合"成对完整发布的不可变状态；
  刷新失败保旧；支持全局紧急总闸与一键回滚（回滚不解除停用）。
- 判定全程打印**所依据的配置快照标识 + 命中的停用指令 id + 每个字段来自哪个来源 + 判定过程**，
  结论可追溯、可对账。

## 1. 代码结构

```
flag/
├── model/      领域模型（RequestContext / FlagRule / ConfigSnapshot / EvaluationResult / EvaluationReason）
├── source/     配置来源抽象 ConfigSource 与可热更新的 InMemoryConfigSource
├── config/     FlagConfigParser（严格解析校验）、SnapshotAssembler（优先级合并+快照组装）、Spring 装配
├── eval/       FlagEvaluator（确定性判定：总闸/紧急停用/白名单/定向/稳定分桶）
├── service/    FeatureFlagManager（热刷新、快照+停用组合发布、全局总闸、回滚、停用/恢复/查询）
│               DisableRegistry（停用指令函数式核心：TTL/容量/合并）、DisableDirective、DisableOutcome
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

判定按以下顺序短路（对应 `EvaluationReason`；全局总闸与开关配置之间的**紧急停用层**
完整优先级与归因见第 4 节）：

1. 无已加载快照（且未被局部停用）→ `NO_CONFIG_LOADED`，**OFF（fail-CLOSED）**
2. `kill.switch=true` → `KILL_SWITCH_ACTIVE`，一律 **OFF**
   （同时被局部停用的，结论带停用指令以区分两层叠加）
3. **该开关命中生效中的紧急停用指令 → `FLAG_DISABLED`，一律 OFF**
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

## 4. 按开关紧急停用（局部止血层）

在全局总闸之外提供一层更细粒度、可定向止血的紧急控制。一条停用指令 `DisableDirective` 携带：

| 字段 | 说明 |
|---|---|
| `id` | 指令唯一标识（`ds-<uuid>`），用于精确恢复单条 |
| `flagKey` | 被停用的开关（停用未在配置中定义的开关也生效） |
| `reason` / `operator` | 停用原因与操作人，**均必填**（空值被区分拒绝） |
| `createdAtMillis` | 创建时刻 |
| `expireAtMillis` | 到期时刻；`ttlMillis=0` 表示不限期（只能显式恢复） |
| `baseSnapshotId` / `baseSnapshotVersion` | 生效时所基于的配置快照版本（无快照期间创建则为 null/0） |

### 4.1 规则与优先级

判定短路顺序（见 `FlagEvaluator`，优先级从高到低）：

1. 无已加载快照但被局部停用 → `FLAG_DISABLED`（OFF，fail-CLOSED，归因明确）；
   无快照且无停用 → `NO_CONFIG_LOADED`（OFF）
2. 全局总闸 `kill.switch=true` → `KILL_SWITCH_ACTIVE`（一律 OFF）；
   若该开关**同时**被局部停用，结论中仍带 `disableDirectives` 且
   `alsoGlobalKillActive=true`——能分清是"全局层"还是"全局+局部两层"关的
3. 该开关存在**生效中**的停用指令 → `FLAG_DISABLED`（OFF），与 enabled/白名单/分桶无关
4. 开关未定义 → `FLAG_NOT_FOUND`（OFF）
5. `enabled=false` → `MASTER_SWITCH_OFF`（OFF）
6. 白名单命中 → `ALLOWLIST_MATCH`（ON）
7. 定向未全部命中 → `TARGETING_NOT_MATCHED`（OFF）
8. 稳定分桶 → `PERCENTAGE_BUCKETED`（ON/OFF）

即：**全局总闸 > 局部紧急停用 > 开关自身配置**。局部停用在总闸之下、一切业务规则之上。

### 4.2 跨刷新持续有效（只能被两件事解除）

停用指令存储在独立于配置快照的不可变停用状态里，与快照组合成一个整体原子发布：

- 刷新**成功**（新配置推送、来源覆盖变化）：停用原样保留；
- 刷新**失败**（非法取值/来源不可达）：快照保旧，停用原样保留；
- **回滚**：只回滚快照，**不回滚停用**——紧急止血不能被一次回滚无声解除；
- 紧急总闸开启/解除：均不影响停用。

解除路径只有两条：

1. **显式恢复**：`restore(flagKey)`（恢复某开关全部指令）或 `restoreById(directiveId)`（单条）；
   目标不存在/已到期时返回可区分的 `NOT_FOUND`，现状不变；
2. **TTL 到期自动解除**：读路径实时判定 `now < expireAtMillis`，到点即视为从未存在，
   无需后台线程、无需任何人工动作。

**到期后语义**：该开关结论与"从来没被停用过、直接按当前配置判定"**完全一致**
（同一 visitor 的桶位不变，只是重新走当前配置的判定链）。停用期间配置恰好刷新过的，
到期后按**最新**配置判定。

### 4.3 同一开关多条指令的合并

允许对同一开关重复停用，每次生成独立 id 的指令。判定时该开关命中**全部**生效指令，
`EvaluationResult.disableDirectives` 逐条带出（id/原因/操作人/到期时间/基于快照），
合并结果说得清来源；恢复可按 id 精确移除其中一条，其余继续生效。
多条带 TTL 的指令，**最晚到期者**决定何时自动解除；含不限期指令则永不自动解除。

### 4.4 容量上限

生效中停用条目有硬上限（`DisableRegistry.MAX_ACTIVE_DIRECTIVES = 100`，按指令条数计）：

- 停用是**原子批量**操作：整批加入后若会超限，则**整批拒绝**
  （`CAPACITY_EXCEEDED`），一条都不加，现状不变，不会"只生效一半"或静默丢条目；
- 非法参数（空开关列表/空原因/空操作人/负 TTL）以 `INVALID_ARGUMENT` 区分拒绝；
- 已到期条目不占容量；显式恢复/到期释放名额后可再次停用。

### 4.5 查询与对账日志

`activeDisables()` / `activeDisablesFor(flagKey)` 随时返回当前生效中指令
（到期的已被过滤），字段完整、按开关与创建时间排序。每次判定日志包含：

- 所用配置快照标识 `snapshot`；
- 命中的停用指令 id 列表 `disables`（空表示未被局部停用）；
- 该开关每个配置字段最终来自哪个来源 `origins`（另含 `kill.switch` 出处）；
- 判定原因与分桶细节。

```
[FLAG-EVAL] snapshot=v1-dce204c60482 flag=feature.releasable visitor=visitor-7 decision=OFF reason=PERCENTAGE_BUCKETED disables=[] origins={percentage=remote-center, salt=local-defaults, enabled=local-defaults} | 稳定分桶: salt=shared-salt, visitorId=visitor-7, bucket=86, percentage=30 => 未命中(bucket>=percentage)
```

停用时 `disables=[ds-...]`、`reason=FLAG_DISABLED`；全局总闸叠加局部停用时
`reason=KILL_SWITCH_ACTIVE` 且 `disables` 非空。

## 5. 并发一致性、兜底与回滚语义

- 配置快照与停用状态各自不可变，`FeatureFlagManager` 以一个 `volatile` 的组合状态
  （`ConfigSnapshot` + 停用指令 Map）同时持有两者：读请求无锁，任何时刻读到的都是
  **"快照 + 停用集合"成对完整**的状态，刷新/停用期间要么看到旧组合、要么看到新组合，
  看不到半生效状态。
- 刷新 / 紧急关闭 / 回滚 / 停用 / 恢复用同一把锁串行，快照版本号单调递增；
  新组合整体构造成功后才原子发布。
- **刷新失败保旧**：见第 2 节；失败刷新既不换快照也不碰停用。
- **紧急关闭** `emergencyShutdown(reason)`：保留当前全部规则，仅把总闸置 true 发布 `vN-KILL`，
  之后所有开关以 `KILL_SWITCH_ACTIVE` 关闭（关闭前快照进入历史，停用指令原样保留）。
- **回滚** `rollback()`：只弹出历史快照栈顶并发布，可从紧急关闭一键恢复到关闭前的完整判定结论；
  历史栈有界（保留最近 10 份）；无历史时返回失败且当前配置不变；
  **停用不参与回滚**（见第 4.2 节）。
- **判定兜底**：任何判定过程异常都返回 fail-CLOSED 结论，原因码 `EVALUATION_ERROR`，
  即"不确定就关闭"，绝不误放量。

每次判定打印日志（字段出处与命中停用指令见第 4.5 节），例如：

```
[FLAG-EVAL] snapshot=v1-0e93c309ec5a flag=feature.checkout-redesign visitor=normal-55 decision=OFF reason=PERCENTAGE_BUCKETED disables=[] origins={enabled=remote-center, percentage=remote-center, allowlist=remote-center, target.channel=remote-center} | 稳定分桶: salt=checkout-redesign, visitorId=normal-55, bucket=92, percentage=30 => 未命中(bucket>=percentage)
```

## 6. HTTP 接口

| 方法与路径 | 说明 |
|---|---|
| `GET /api/flags/{key}/evaluate?visitorId=..&attr.channel=beta` | 判定查询（`attr.` 前缀参数作为定向属性），结论含快照标识、命中停用指令、字段出处 |
| `POST /api/admin/refresh` | 热刷新（失败保旧，停用不受影响，返回原因） |
| `POST /api/admin/kill?reason=..` | 紧急关闭（全局总闸） |
| `POST /api/admin/rollback` | 回滚到上一份快照（停用不回滚） |
| `POST /api/admin/disable?keys=feature.a,feature.b&reason=..&operator=..&ttlMillis=600000` | 按开关紧急停用（逗号分隔批量、原子；`ttlMillis` 可省略=不限期） |
| `POST /api/admin/restore?flagKey=feature.a` | 恢复某开关的全部停用指令 |
| `POST /api/admin/restore-directive?id=ds-..` | 按指令 id 恢复单条停用 |
| `GET /api/admin/disables` | 查询当前全部生效中的停用指令（含原因/操作人/到期/基于快照） |

## 7. 本地验证方法

```bash
# 全量测试（35 个用例，日志直接打印到控制台）
mvn test

# 只跑紧急停用相关用例并看场景日志
mvn test -Dtest='EmergencyDisableTest,DisableSurvivesRefreshTest,DisableExpiryTest,DisableCapacityTest,ConcurrentDisableConsistencyTest,DisableQueryTest'

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
| `EmergencyDisableTest` | 单/组停用带原因操作人、多指令合并可逐条看清、按开关/按 id 恢复、非法参数与 NOT_FOUND 区分、未定义开关/无快照下仍停用 |
| `DisableSurvivesRefreshTest` | 停用跨刷新成功、非法取值失败、来源缺失、回滚、紧急关闭均不被解除；全局+局部叠加归因；字段出处对账 |
| `DisableExpiryTest` | 可控时钟下 TTL 到期自动恢复、到期后结论等同从未停用（含停用期间刷新到 70%）、最晚到期者决定解除、已到期 id 恢复=NOT_FOUND、不限期不自动解除 |
| `DisableCapacityTest` | 超限整批拒绝（CAPACITY_EXCEEDED）现状不变、批量 all-or-nothing、恢复/到期释放容量、判定不受被拒条目影响 |
| `ConcurrentDisableConsistencyTest` | 8 读 + 1 刷新（好/坏交替）+ 1 停用恢复线程并发：集合自洽不超容量、指令 id 无重复、判定原因与携带指令一致、无空快照、停用最终可恢复 |
| `DisableQueryTest` | 生效列表字段完整（开关/原因/操作人/创建到期时间/基于快照版本）、稳定排序、到期与恢复后从列表消失 |

手动冒烟示例：

```bash
curl -s "http://127.0.0.1:18091/api/flags/feature.checkout-redesign/evaluate?visitorId=vip-001&attr.channel=beta"
# -> enabled=true, reason=ALLOWLIST_MATCH

# 对两个开关停用 10 分钟（600000ms）
curl -s -X POST "http://127.0.0.1:18091/api/admin/disable?keys=feature.checkout-redesign,feature.new-search&reason=checkout-incident&operator=oncall-alice&ttlMillis=600000"
# -> accepted=true, directives=[{id=ds-.., expireAtMillis=..}, ...]

# 这两个开关立刻 OFF, reason=FLAG_DISABLED，结论带命中的 ds-.. 指令
curl -s "http://127.0.0.1:18091/api/flags/feature.new-search/evaluate?visitorId=u1"

# 查看全部生效中停用
curl -s "http://127.0.0.1:18091/api/admin/disables"

# 期间无论怎么刷新都仍然停用（到期前）
curl -s -X POST "http://127.0.0.1:18091/api/admin/refresh"

# 显式恢复（或等 10 分钟自动恢复，恢复后按当前配置判定）
curl -s -X POST "http://127.0.0.1:18091/api/admin/restore?flagKey=feature.new-search"

# 全局总闸（与局部停用相互独立）
curl -s -X POST "http://127.0.0.1:18091/api/admin/kill?reason=incident"
# -> 所有开关 OFF, reason=KILL_SWITCH_ACTIVE
curl -s -X POST "http://127.0.0.1:18091/api/admin/rollback"
# -> 回滚快照（局部停用不受影响）
```
