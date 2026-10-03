package com.github.lisama569.runtimefeatureflagsjava.flag.service;

import com.github.lisama569.runtimefeatureflagsjava.flag.config.InvalidFlagConfigException;
import com.github.lisama569.runtimefeatureflagsjava.flag.config.SnapshotAssembler;
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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * 运行时功能开关门面：热刷新、快照切换、紧急关闭、回滚、<b>按开关紧急停用</b>、无锁读取。
 * <p>
 * 一致性保证：
 * <ul>
 *   <li>{@code volatile} 的 {@link CombinedState} 同时持有<b>不可变</b> {@link ConfigSnapshot}
 *       与<b>不可变</b>停用状态。刷新/停用/恢复/关闭/回滚都在同一把锁内产生新的
 *       {@link CombinedState} 并原子发布；无锁读要么看到旧的完整组合、要么看到新的完整组合，
 *       绝不会看到"快照已换但停用还是旧的"这类半生效状态。</li>
 *   <li>停用指令独立于配置生命周期：刷新成功、刷新失败、回滚都不改动停用状态；
 *       解除只有两条路径——显式 {@code restore}，或到达 TTL 自动到期。</li>
 *   <li>停用指令条目数有硬上限（{@link DisableRegistry#MAX_ACTIVE_DIRECTIVES}），
 *       超限时整批请求被拒绝（{@link DisableFailureReason#CAPACITY_EXCEEDED}），现状不变。</li>
 * </ul>
 * 兜底语义：任何判定过程异常均 fail-CLOSED（{@link EvaluationReason#EVALUATION_ERROR}），
 * 即"不确定就关闭"，绝不误放量。
 */
public class FeatureFlagManager {

    private static final Logger log = LoggerFactory.getLogger(FeatureFlagManager.class);
    private static final int HISTORY_LIMIT = 10;

    private final List<ConfigSource> sources;
    private final SnapshotAssembler assembler = new SnapshotAssembler();
    private final FlagEvaluator evaluator = new FlagEvaluator();
    private final DisableRegistry disableRegistry;

    /** 快照 + 停用的完整不可变状态；snapshot 为 null 表示尚未成功加载过任何配置。 */
    private record CombinedState(ConfigSnapshot snapshot,
                                 Map<String, List<DisableDirective>> disables) {
        static CombinedState empty(DisableRegistry registry) {
            return new CombinedState(null, registry.emptyState());
        }
    }

    private volatile CombinedState state;

    /** 历史快照栈：栈顶为最近一次"当前"之前的快照，用于回滚（停用状态不回滚）。 */
    private final Deque<ConfigSnapshot> history = new ArrayDeque<>();

    private final Object switchLock = new Object();
    private long versionSeq = 0;

    public FeatureFlagManager(List<ConfigSource> sources) {
        this(sources, System::currentTimeMillis, DisableRegistry.MAX_ACTIVE_DIRECTIVES);
    }

    /** 测试用：注入时钟（TTL 到期判定随之可控）。 */
    public FeatureFlagManager(List<ConfigSource> sources, LongSupplier clockMillis) {
        this(sources, clockMillis, DisableRegistry.MAX_ACTIVE_DIRECTIVES);
    }

    /** 测试用：注入时钟与停用容量上限。 */
    public FeatureFlagManager(List<ConfigSource> sources, LongSupplier clockMillis,
                              int disableCapacity) {
        this.sources = sources == null ? List.of() : List.copyOf(sources);
        this.disableRegistry = new DisableRegistry(clockMillis, disableCapacity);
        this.state = CombinedState.empty(disableRegistry);
    }

    /**
     * 无锁读取：基于当前完整组合（快照 + 生效中的停用指令）判定。
     * 停用指令在读取瞬间已按时钟过滤到期条目，到期后结论即等同于从未停用。
     */
    public EvaluationResult evaluate(String flagKey, RequestContext context) {
        CombinedState snapshotState = this.state;
        ConfigSnapshot snapshot = snapshotState.snapshot();
        try {
            List<DisableDirective> activeDisables =
                    disableRegistry.activeFor(snapshotState.disables(), flagKey);
            return evaluator.evaluate(flagKey, context, snapshot, activeDisables);
        } catch (RuntimeException ex) {
            // 兜底语义：判定本身出错也 fail-CLOSED，原因码明确为 EVALUATION_ERROR
            String sid = snapshot == null ? null : snapshot.snapshotId();
            log.warn("[FLAG-EVAL] snapshot={} flag={} 判定异常，fail-CLOSED: {}",
                    sid, flagKey, ex.getMessage(), ex);
            return new EvaluationResult(flagKey,
                    context == null ? null : context.visitorId(),
                    false, EvaluationReason.EVALUATION_ERROR,
                    "判定过程异常，按 fail-CLOSED 拒绝开启: " + ex.getMessage(), sid);
        }
    }

    /**
     * 重新加载全部来源并原子切换。失败时保留旧快照与全部停用指令。
     */
    public RefreshOutcome refresh() {
        synchronized (switchLock) {
            ConfigSnapshot previous = this.state.snapshot();
            ConfigSnapshot candidate;
            try {
                candidate = assembler.assemble(sources, ++versionSeq);
            } catch (SourceUnavailableException ex) {
                versionSeq--; // 候选快照未发布，版本号不前进
                log.warn("[FLAG-REFRESH] 拒绝刷新(来源缺失): source={} 继续使用快照={}（停用指令不受影响，仍有 {} 条生效）",
                        ex.sourceName(), idOf(previous),
                        disableRegistry.activeCount(this.state.disables()));
                return RefreshOutcome.fail(RefreshFailureReason.SOURCE_UNAVAILABLE,
                        idOf(previous), "来源缺失/不可达: " + ex.sourceName()
                                + " - " + ex.getMessage());
            } catch (InvalidFlagConfigException ex) {
                versionSeq--;
                log.warn("[FLAG-REFRESH] 拒绝刷新(非法取值): type={} key={} value={} 继续使用快照={}（停用指令不受影响）",
                        ex.problemType(), ex.rawKey(), ex.rawValue(), idOf(previous));
                return RefreshOutcome.fail(RefreshFailureReason.INVALID_VALUE,
                        idOf(previous), "非法取值[" + ex.problemType() + "] key="
                                + ex.rawKey() + " value=" + ex.rawValue()
                                + " - " + ex.getMessage());
            }

            publish(new CombinedState(candidate, this.state.disables()));
            log.info("[FLAG-REFRESH] 刷新成功并发布新快照={} (旧快照={})；停用指令原样保留({} 条生效)",
                    candidate.snapshotId(), idOf(previous),
                    disableRegistry.activeCount(this.state.disables()));
            return RefreshOutcome.ok(candidate.snapshotId());
        }
    }

    /**
     * 紧急关闭：保留当前所有规则，仅将 kill.switch 置为 true 后发布新快照。
     * 停用指令原样保留（两层控制相互独立）。
     */
    public RefreshOutcome emergencyShutdown(String reason) {
        synchronized (switchLock) {
            ConfigSnapshot previous = this.state.snapshot();
            Map<String, FlagRule> flags = previous == null
                    ? Map.of() : previous.flags();
            Map<String, String> origins = previous == null
                    ? Map.of() : previous.fieldOrigins();
            long version = ++versionSeq;
            String snapshotId = "v" + version + "-KILL";
            ConfigSnapshot killed = new ConfigSnapshot(
                    snapshotId, version, System.currentTimeMillis(), true, flags, origins);
            publish(new CombinedState(killed, this.state.disables()));
            log.warn("[FLAG-KILL] 紧急关闭已生效 snapshot={} reason={} (关闭前快照={})；停用指令原样保留({} 条生效)",
                    snapshotId, reason, idOf(previous),
                    disableRegistry.activeCount(this.state.disables()));
            return RefreshOutcome.ok(snapshotId);
        }
    }

    /**
     * 回滚到上一份快照（kill 快照也占一历史位，因此可从紧急关闭一键恢复）。
     * <b>停用状态不参与回滚</b>：紧急停用必须只能被显式恢复/到期解除，
     * 不能通过回滚被无声解除。
     */
    public RefreshOutcome rollback() {
        synchronized (switchLock) {
            if (history.isEmpty()) {
                log.warn("[FLAG-ROLLBACK] 无可回滚的历史快照，当前快照={} 保持不变（停用指令不变）",
                        idOf(this.state.snapshot()));
                return RefreshOutcome.fail(RefreshFailureReason.NO_SOURCES,
                        idOf(this.state.snapshot()), "没有可回滚的历史快照");
            }
            ConfigSnapshot target = history.pop();
            ConfigSnapshot current = this.state.snapshot();
            this.state = new CombinedState(target, this.state.disables());
            log.warn("[FLAG-ROLLBACK] 已回滚快照: {} -> {}（停用指令不变，仍有 {} 条生效）",
                    idOf(current), target.snapshotId(),
                    disableRegistry.activeCount(this.state.disables()));
            return RefreshOutcome.ok(target.snapshotId());
        }
    }

    // ---- 按开关紧急停用 ----

    /**
     * 对单个开关紧急停用（不限期，只能显式恢复）。
     */
    public DisableOutcome disable(String flagKey, String reason, String operator) {
        return disable(List.of(flagKey), reason, operator, 0L);
    }

    /**
     * 对一组开关原子停用（批量：要么全部成功，要么全部被拒、现状不变）。
     *
     * @param ttlMillis 有效期；0 表示不限期
     */
    public DisableOutcome disable(List<String> flagKeys, String reason,
                                  String operator, long ttlMillis) {
        synchronized (switchLock) {
            ConfigSnapshot base = this.state.snapshot();
            String baseId = base == null ? null : base.snapshotId();
            long baseVersion = base == null ? 0L : base.version();
            DisableRegistry.Mutation mutation = disableRegistry.addDirectives(
                    this.state.disables(), flagKeys, reason, operator,
                    ttlMillis, baseId, baseVersion);
            if (mutation.outcome().accepted()) {
                this.state = new CombinedState(base, mutation.next());
                log.warn("[FLAG-DISABLE] {} 基于快照={}（{}）",
                        mutation.outcome().detail(), idOf(base),
                        ttlMillis == 0L ? "不限期" : "TTL=" + ttlMillis + "ms");
            } else {
                // 容量清理可能产生新的不可变状态（仅剔除到期条目），随拒绝一并发布
                this.state = new CombinedState(base, mutation.next());
                log.warn("[FLAG-DISABLE] 停用被拒绝: reason={} detail={}",
                        mutation.outcome().failureReason(), mutation.outcome().detail());
            }
            return mutation.outcome();
        }
    }

    /** 恢复某开关下全部生效中的停用指令。 */
    public DisableOutcome restore(String flagKey) {
        synchronized (switchLock) {
            DisableRegistry.Mutation mutation =
                    disableRegistry.restoreFlag(this.state.disables(), flagKey);
            if (mutation.outcome().accepted()) {
                this.state = new CombinedState(this.state.snapshot(), mutation.next());
                log.warn("[FLAG-RESTORE] {}", mutation.outcome().detail());
            } else {
                log.warn("[FLAG-RESTORE] 恢复被拒绝: reason={} detail={}",
                        mutation.outcome().failureReason(), mutation.outcome().detail());
            }
            return mutation.outcome();
        }
    }

    /** 按指令 id 恢复单条停用。 */
    public DisableOutcome restoreById(String directiveId) {
        synchronized (switchLock) {
            DisableRegistry.Mutation mutation =
                    disableRegistry.restoreId(this.state.disables(), directiveId);
            if (mutation.outcome().accepted()) {
                this.state = new CombinedState(this.state.snapshot(), mutation.next());
                log.warn("[FLAG-RESTORE] {}", mutation.outcome().detail());
            } else {
                log.warn("[FLAG-RESTORE] 恢复被拒绝: reason={} detail={}",
                        mutation.outcome().failureReason(), mutation.outcome().detail());
            }
            return mutation.outcome();
        }
    }

    /** 查询当前全部生效中的停用指令（到期的不计）。 */
    public List<DisableDirective> activeDisables() {
        CombinedState snapshotState = this.state;
        return disableRegistry.listActive(snapshotState.disables());
    }

    /** 查询某开关当前生效中的停用指令（到期的不计）。 */
    public List<DisableDirective> activeDisablesFor(String flagKey) {
        CombinedState snapshotState = this.state;
        return disableRegistry.activeFor(snapshotState.disables(), flagKey);
    }

    /** 当前生效中的停用指令条数（到期的不计）。 */
    public int activeDisableCount() {
        CombinedState snapshotState = this.state;
        return disableRegistry.activeCount(snapshotState.disables());
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

    /** 发布新组合：旧快照入历史栈（有界），停用状态沿用新组合里的版本。 */
    private void publish(CombinedState next) {
        if (this.state.snapshot() != null) {
            history.push(this.state.snapshot());
            while (history.size() > HISTORY_LIMIT) {
                history.removeLast();
            }
        }
        this.state = next;
    }

    private static String idOf(ConfigSnapshot s) {
        return s == null ? "<none>" : s.snapshotId();
    }
}
