package com.github.lisama569.runtimefeatureflagsjava.flag.service;

import com.github.lisama569.runtimefeatureflagsjava.flag.config.InvalidFlagConfigException;
import com.github.lisama569.runtimefeatureflagsjava.flag.config.SnapshotAssembler;
import com.github.lisama569.runtimefeatureflagsjava.flag.disable.ControlState;
import com.github.lisama569.runtimefeatureflagsjava.flag.disable.DisableDirective;
import com.github.lisama569.runtimefeatureflagsjava.flag.disable.DisableOutcome;
import com.github.lisama569.runtimefeatureflagsjava.flag.disable.DisableRejectionReason;
import com.github.lisama569.runtimefeatureflagsjava.flag.disable.DisableSet;
import com.github.lisama569.runtimefeatureflagsjava.flag.disable.DisableView;
import com.github.lisama569.runtimefeatureflagsjava.flag.eval.FlagEvaluator;
import com.github.lisama569.runtimefeatureflagsjava.flag.model.ConfigSnapshot;
import com.github.lisama569.runtimefeatureflagsjava.flag.model.EvaluationReason;
import com.github.lisama569.runtimefeatureflagsjava.flag.model.EvaluationResult;
import com.github.lisama569.runtimefeatureflagsjava.flag.model.FlagRule;
import com.github.lisama569.runtimefeatureflagsjava.flag.model.RequestContext;
import com.github.lisama569.runtimefeatureflagsjava.flag.source.ConfigSource;
import com.github.lisama569.runtimefeatureflagsjava.flag.source.SourceUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 运行时功能开关门面：热刷新、快照切换、紧急关闭、按开关临时停用、回滚、无锁读取。
 * <p>
 * 一致性保证：
 * <ul>
 *   <li>{@code volatile} 引用指向<b>不可变</b> {@link ControlState}
 *       （配置快照 + 停用集合 配对整体发布），刷新/停用/恢复期间读请求要么看到
 *       旧的整体、要么看到新的整体，绝不会看到半生效状态。</li>
 *   <li>刷新/关闭/停用/恢复/回滚用同一把锁串行化；版本号单调递增。</li>
 *   <li>刷新失败（来源缺失/非法取值）时新快照被整体丢弃，旧快照继续生效，
 *       返回带可区分 {@link RefreshFailureReason} 与细节的结果。</li>
 *   <li>局部停用一旦生效，<b>不被任何刷新/推送/刷新失败解除</b>；
 *       只有显式恢复或到期才解除。停用操作超容量上限时整体拒绝、现状不变，
 *       拒绝原因 {@link DisableRejectionReason#CAPACITY_EXCEEDED} 可区分。</li>
 * </ul>
 * 兜底语义：任何判定过程异常均 fail-CLOSED（{@link EvaluationReason#EVALUATION_ERROR}），
 * 即"不确定就关闭"，绝不误放量。
 */
public class FeatureFlagManager {

    private static final Logger log = LoggerFactory.getLogger(FeatureFlagManager.class);
    private static final int HISTORY_LIMIT = 10;
    /** 生效中停用条目的默认容量上限。 */
    public static final int DEFAULT_MAX_DISABLES = 128;

    private final List<ConfigSource> sources;
    private final SnapshotAssembler assembler = new SnapshotAssembler();
    private final FlagEvaluator evaluator = new FlagEvaluator();
    private final Clock clock;
    private final int maxActiveDisables;

    /** 当前生效控制面状态（快照 + 停用集合），整体原子替换。 */
    private volatile ControlState state = ControlState.initial();
    /** 历史快照栈：栈顶为最近一次"当前"之前的快照，用于回滚。 */
    private final Deque<ConfigSnapshot> history = new ArrayDeque<>();

    private final Object switchLock = new Object();
    private long versionSeq = 0;
    private long disableSeq = 0;

    public FeatureFlagManager(List<ConfigSource> sources) {
        this(sources, DEFAULT_MAX_DISABLES, Clock.systemUTC());
    }

    public FeatureFlagManager(List<ConfigSource> sources, int maxActiveDisables, Clock clock) {
        this.sources = sources == null ? List.of() : List.copyOf(sources);
        this.maxActiveDisables = maxActiveDisables;
        this.clock = clock;
    }

    /**
     * 无锁读取：基于当前不可变 (快照, 停用集合) 整体判定。
     */
    public EvaluationResult evaluate(String flagKey, RequestContext context) {
        ControlState current = this.state;
        ConfigSnapshot snapshot = current.snapshot();
        try {
            return evaluator.evaluate(flagKey, context, snapshot,
                    current.disables(), clock.millis());
        } catch (RuntimeException ex) {
            // 兜底语义：判定本身出错也 fail-CLOSED，原因码明确为 EVALUATION_ERROR；
            // 日志同样携带可对账信息（快照/停用/字段出处，尽力而为，绝不再抛）
            String sid = snapshot == null ? null : snapshot.snapshotId();
            log.warn("[FLAG-EVAL] snapshot={} flag={} visitor={} decision=OFF reason={} disable={} origins={} | 判定异常，fail-CLOSED: {}",
                    sid == null ? "<none>" : sid, flagKey,
                    context == null ? null : context.visitorId(),
                    EvaluationReason.EVALUATION_ERROR,
                    safeDisableHit(current, flagKey), safeOrigins(snapshot, flagKey),
                    ex.getMessage(), ex);
            return new EvaluationResult(flagKey,
                    context == null ? null : context.visitorId(),
                    false, EvaluationReason.EVALUATION_ERROR,
                    "判定过程异常，按 fail-CLOSED 拒绝开启: " + ex.getMessage(), sid);
        }
    }

    /** 兜底日志用：尽力给出命中的停用记录标识，任何异常都退化为 "-"，不再抛出。 */
    private String safeDisableHit(ControlState state, String flagKey) {
        try {
            DisableView hit = state.disables().effectiveFor(flagKey, clock.millis());
            return hit == null ? "-" : hit.mergedId();
        } catch (RuntimeException ignored) {
            return "-";
        }
    }

    /** 兜底日志用：尽力给出字段出处来源，任何异常都退化为显式标记，不再抛出。 */
    private String safeOrigins(ConfigSnapshot snapshot, String flagKey) {
        try {
            if (snapshot == null) {
                return "<no-config>";
            }
            FlagRule rule = snapshot.flags().get(flagKey);
            return rule == null ? "<flag-not-defined>" : rule.fieldOrigins().toString();
        } catch (RuntimeException ignored) {
            return "<unknown>";
        }
    }

    /**
     * 重新加载全部来源并原子切换。失败时保留旧快照。
     * 局部停用集合不受刷新影响（无论成功或失败），随快照原样携带发布。
     */
    public RefreshOutcome refresh() {
        synchronized (switchLock) {
            ConfigSnapshot previous = this.state.snapshot();
            ConfigSnapshot candidate;
            try {
                candidate = assembler.assemble(sources, ++versionSeq);
            } catch (SourceUnavailableException ex) {
                versionSeq--; // 候选快照未发布，版本号不前进
                log.warn("[FLAG-REFRESH] 拒绝刷新(来源缺失): source={} 继续使用快照={}",
                        ex.sourceName(), idOf(previous));
                return RefreshOutcome.fail(RefreshFailureReason.SOURCE_UNAVAILABLE,
                        idOf(previous), "来源缺失/不可达: " + ex.sourceName()
                                + " - " + ex.getMessage());
            } catch (InvalidFlagConfigException ex) {
                versionSeq--;
                log.warn("[FLAG-REFRESH] 拒绝刷新(非法取值): type={} key={} value={} 继续使用快照={}",
                        ex.problemType(), ex.rawKey(), ex.rawValue(), idOf(previous));
                return RefreshOutcome.fail(RefreshFailureReason.INVALID_VALUE,
                        idOf(previous), "非法取值[" + ex.problemType() + "] key="
                                + ex.rawKey() + " value=" + ex.rawValue()
                                + " - " + ex.getMessage());
            }

            publish(candidate);
            log.info("[FLAG-REFRESH] 刷新成功并发布新快照={} (旧快照={}) 生效中停用={}条",
                    candidate.snapshotId(), idOf(previous),
                    this.state.disables().size());
            return RefreshOutcome.ok(candidate.snapshotId());
        }
    }

    /**
     * 紧急关闭：保留当前所有规则，仅将 kill.switch 置为 true 后发布新快照。
     * 这样回滚时可完整恢复到关闭前的判定结论。局部停用集合原样保留。
     */
    public RefreshOutcome emergencyShutdown(String reason) {
        synchronized (switchLock) {
            ConfigSnapshot previous = this.state.snapshot();
            Map<String, FlagRule> flags = previous == null
                    ? Map.of() : previous.flags();
            long version = ++versionSeq;
            String snapshotId = "v" + version + "-KILL";
            ConfigSnapshot killed = new ConfigSnapshot(
                    snapshotId, version, System.currentTimeMillis(), true, flags);
            publish(killed);
            log.warn("[FLAG-KILL] 紧急关闭已生效 snapshot={} reason={} (关闭前快照={})",
                    snapshotId, reason, idOf(previous));
            return RefreshOutcome.ok(snapshotId);
        }
    }

    /**
     * 回滚到上一份快照（kill 快照也占一历史位，因此可从紧急关闭一键恢复）。
     * 没有可回滚的历史时返回失败，当前配置不受影响。局部停用集合不受回滚影响。
     */
    public RefreshOutcome rollback() {
        synchronized (switchLock) {
            if (history.isEmpty()) {
                log.warn("[FLAG-ROLLBACK] 无可回滚的历史快照，当前快照={} 保持不变",
                        idOf(this.state.snapshot()));
                return RefreshOutcome.fail(RefreshFailureReason.NO_SOURCES,
                        idOf(this.state.snapshot()), "没有可回滚的历史快照");
            }
            ConfigSnapshot target = history.pop();
            ConfigSnapshot current = this.state.snapshot();
            this.state = this.state.withSnapshot(target);
            log.warn("[FLAG-ROLLBACK] 已回滚: {} -> {}", idOf(current), target.snapshotId());
            return RefreshOutcome.ok(target.snapshotId());
        }
    }

    /**
     * 临时停用单个开关（永久，需显式恢复）。
     */
    public DisableOutcome disable(String flagKey, String reason, String operator) {
        return disableAll(List.of(flagKey), reason, operator, null);
    }

    /**
     * 临时停用单个开关；{@code ttlMillis} 非 null 时到点自动恢复。
     */
    public DisableOutcome disable(String flagKey, String reason, String operator, Long ttlMillis) {
        return disableAll(List.of(flagKey), reason, operator, ttlMillis);
    }

    /**
     * 批量临时停用一组开关（原子：要么整批生效，要么整批拒绝，现状不变）。
     * <p>
     * 停用一旦发布，后续任何配置刷新/推送/刷新失败都不得解除；
     * 只有显式 {@link #resume(String)} 或到达 {@code ttlMillis} 到期点才解除。
     * 同一开关被多条指令命中时按 {@link DisableView} 的合并规则给出合并结果。
     *
     * @param ttlMillis 非 null 且必须为正数；null 表示永久停用
     */
    public DisableOutcome disableAll(List<String> flagKeys, String reason,
                                     String operator, Long ttlMillis) {
        // 参数校验在锁外做不可行（需一致视图），统一在锁内完成
        synchronized (switchLock) {
            LinkedHashSet<String> keys = new LinkedHashSet<>();
            if (flagKeys != null) {
                for (String k : flagKeys) {
                    if (k != null && !k.isBlank()) {
                        keys.add(k.trim());
                    }
                }
            }
            if (keys.isEmpty()) {
                return reject(DisableRejectionReason.INVALID_REQUEST,
                        "停用请求被拒绝: 开关列表为空或全部非法");
            }
            if (reason == null || reason.isBlank() || operator == null || operator.isBlank()) {
                return reject(DisableRejectionReason.INVALID_REQUEST,
                        "停用请求被拒绝: 必须携带原因(reason)与操作人(operator)");
            }
            if (ttlMillis != null && ttlMillis <= 0) {
                return reject(DisableRejectionReason.INVALID_REQUEST,
                        "停用请求被拒绝: ttlMillis 必须为正数, 实际=" + ttlMillis);
            }

            long now = clock.millis();
            // 先回收已到期条目，再按"生效中条目数"做容量判断
            DisableSet pruned = this.state.disables().pruneExpired(now);
            if (pruned.size() + keys.size() > maxActiveDisables) {
                DisableOutcome rejected = DisableOutcome.fail(
                        DisableRejectionReason.CAPACITY_EXCEEDED,
                        "停用容量已满: 生效中=" + pruned.size()
                                + " 上限=" + maxActiveDisables
                                + " 本次请求=" + keys.size()
                                + " 条，整体拒绝，现状不变");
                log.warn("[FLAG-DISABLE] 拒绝(容量超限) 生效中={} 上限={} 请求={}",
                        pruned.size(), maxActiveDisables, keys);
                return rejected;
            }

            String baseSnapshot = idOf(this.state.snapshot());
            Long expiresAt = ttlMillis == null ? null : now + ttlMillis;
            List<DisableDirective> directives = new ArrayList<>();
            for (String key : keys) {
                directives.add(new DisableDirective(
                        "d-" + (++disableSeq), key, reason.trim(), operator.trim(),
                        now, expiresAt, baseSnapshot));
            }
            DisableSet next = pruned.plusAll(directives);
            this.state = this.state.withDisables(next);
            log.warn("[FLAG-DISABLE] 停用已生效 flags={} reason={} operator={} ttl={} baseSnapshot={} 生效中={}条",
                    keys, reason, operator,
                    ttlMillis == null ? "永久" : ttlMillis + "ms",
                    baseSnapshot, next.size());
            return DisableOutcome.ok(
                    "停用已生效(" + keys.size() + " 个开关)，基于快照 " + baseSnapshot,
                    List.copyOf(keys));
        }
    }

    /**
     * 显式恢复某开关：移除其全部停用指令。该开关没有生效中停用时拒绝
     * （{@link DisableRejectionReason#NOT_DISABLED}），现状不变。
     */
    public DisableOutcome resume(String flagKey) {
        synchronized (switchLock) {
            if (flagKey == null || flagKey.isBlank()) {
                return reject(DisableRejectionReason.INVALID_REQUEST,
                        "恢复请求被拒绝: 开关名为空");
            }
            String key = flagKey.trim();
            long now = clock.millis();
            DisableSet pruned = this.state.disables().pruneExpired(now);
            if (!pruned.hasActiveFor(key, now)) {
                return reject(DisableRejectionReason.NOT_DISABLED,
                        "开关 " + key + " 当前没有生效中的停用记录，无需恢复");
            }
            DisableSet next = pruned.removeFlag(key);
            this.state = this.state.withDisables(next);
            log.warn("[FLAG-RESUME] 停用已恢复 flag={} 剩余生效中={}条", key, next.size());
            return DisableOutcome.ok("开关 " + key + " 的停用已恢复", List.of(key));
        }
    }

    /**
     * 查询当前生效中的停用（已到期的自动视为恢复，不出现）。
     * 无锁读取，返回的是不可变快照视图，任意时刻都完整一致。
     */
    public List<DisableView> activeDisables() {
        return this.state.disables().activeViews(clock.millis());
    }

    public String currentSnapshotId() {
        return idOf(this.state.snapshot());
    }

    /** 历史快照标识，栈顶（最近的前一份）在前。 */
    public List<String> historySnapshotIds() {
        synchronized (switchLock) {
            List<String> ids = new ArrayList<>();
            for (ConfigSnapshot s : history) {
                ids.add(s.snapshotId());
            }
            return Collections.unmodifiableList(ids);
        }
    }

    /** 发布新快照：旧快照入历史栈（有界），与停用集合配对原子替换引用。 */
    private void publish(ConfigSnapshot next) {
        ConfigSnapshot previous = this.state.snapshot();
        if (previous != null) {
            history.push(previous);
            while (history.size() > HISTORY_LIMIT) {
                history.removeLast();
            }
        }
        this.state = this.state.withSnapshot(next);
    }

    private DisableOutcome reject(DisableRejectionReason reason, String detail) {
        log.warn("[FLAG-DISABLE] 拒绝({}): {}", reason, detail);
        return DisableOutcome.fail(reason, detail);
    }

    private static String idOf(ConfigSnapshot s) {
        return s == null ? "<none>" : s.snapshotId();
    }
}
