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

/**
 * 运行时功能开关门面：热刷新、快照切换、紧急关闭、回滚、无锁读取。
 * <p>
 * 一致性保证：
 * <ul>
 *   <li>{@code volatile} 引用指向<b>不可变</b> {@link ConfigSnapshot}，
 *       刷新期间读请求要么看到旧快照、要么看到新快照，绝不会看到半生效状态。</li>
 *   <li>刷新/关闭/回滚用同一把锁串行化；版本号单调递增。</li>
 *   <li>刷新失败（来源缺失/非法取值）时新快照被整体丢弃，旧快照继续生效，
 *       返回带可区分 {@link RefreshFailureReason} 与细节的结果。</li>
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

    /** 当前生效快照；null 表示尚未有任何成功加载的配置。 */
    private volatile ConfigSnapshot active;
    /** 历史快照栈：栈顶为最近一次"当前"之前的快照，用于回滚。 */
    private final Deque<ConfigSnapshot> history = new ArrayDeque<>();

    private final Object switchLock = new Object();
    private long versionSeq = 0;

    public FeatureFlagManager(List<ConfigSource> sources) {
        this.sources = sources == null ? List.of() : List.copyOf(sources);
    }

    /**
     * 无锁读取：直接基于当前不可变快照判定。
     */
    public EvaluationResult evaluate(String flagKey, RequestContext context) {
        ConfigSnapshot snapshot = this.active;
        try {
            return evaluator.evaluate(flagKey, context, snapshot);
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
     * 重新加载全部来源并原子切换。失败时保留旧快照。
     */
    public RefreshOutcome refresh() {
        synchronized (switchLock) {
            ConfigSnapshot previous = this.active;
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
            log.info("[FLAG-REFRESH] 刷新成功并发布新快照={} (旧快照={})",
                    candidate.snapshotId(), idOf(previous));
            return RefreshOutcome.ok(candidate.snapshotId());
        }
    }

    /**
     * 紧急关闭：保留当前所有规则，仅将 kill.switch 置为 true 后发布新快照。
     * 这样回滚时可完整恢复到关闭前的判定结论。
     */
    public RefreshOutcome emergencyShutdown(String reason) {
        synchronized (switchLock) {
            ConfigSnapshot previous = this.active;
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
     * 没有可回滚的历史时返回失败，当前配置不受影响。
     */
    public RefreshOutcome rollback() {
        synchronized (switchLock) {
            if (history.isEmpty()) {
                log.warn("[FLAG-ROLLBACK] 无可回滚的历史快照，当前快照={} 保持不变", idOf(active));
                return RefreshOutcome.fail(RefreshFailureReason.NO_SOURCES,
                        idOf(active), "没有可回滚的历史快照");
            }
            ConfigSnapshot target = history.pop();
            ConfigSnapshot current = this.active;
            this.active = target;
            log.warn("[FLAG-ROLLBACK] 已回滚: {} -> {}", idOf(current), target.snapshotId());
            return RefreshOutcome.ok(target.snapshotId());
        }
    }

    public String currentSnapshotId() {
        return idOf(active);
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

    /** 发布新快照：旧快照入历史栈（有界），原子替换引用。 */
    private void publish(ConfigSnapshot next) {
        if (this.active != null) {
            history.push(this.active);
            while (history.size() > HISTORY_LIMIT) {
                history.removeLast();
            }
        }
        this.active = next;
    }

    private static String idOf(ConfigSnapshot s) {
        return s == null ? "<none>" : s.snapshotId();
    }
}
