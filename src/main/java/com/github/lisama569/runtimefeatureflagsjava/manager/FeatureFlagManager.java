package com.github.lisama569.runtimefeatureflagsjava.manager;

import com.github.lisama569.runtimefeatureflagsjava.config.FeatureFlagProperties;
import com.github.lisama569.runtimefeatureflagsjava.eval.FeatureEvaluator;
import com.github.lisama569.runtimefeatureflagsjava.merge.MergeOutcome;
import com.github.lisama569.runtimefeatureflagsjava.merge.SnapshotMerger;
import com.github.lisama569.runtimefeatureflagsjava.model.Decision;
import com.github.lisama569.runtimefeatureflagsjava.model.FlagSnapshot;
import com.github.lisama569.runtimefeatureflagsjava.model.RefreshResult;
import com.github.lisama569.runtimefeatureflagsjava.model.Rejection;
import com.github.lisama569.runtimefeatureflagsjava.model.RequestContext;
import com.github.lisama569.runtimefeatureflagsjava.source.ConfigSource;
import com.github.lisama569.runtimefeatureflagsjava.source.PropertiesFileConfigSource;
import com.github.lisama569.runtimefeatureflagsjava.source.RawSourceData;
import com.github.lisama569.runtimefeatureflagsjava.source.SourceLoadException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * 运行时开关门面，保证四件事：
 *
 * <ol>
 *   <li><b>并发一致性</b>：当前快照是 {@code volatile} 引用且快照本身不可变；刷新在锁内完成
 *       “加载→合并→换引用”，读请求永远拿到旧的或新的完整快照，绝不看到中间态。</li>
 *   <li><b>刷新失败保持旧配置</b>：任何来源硬拒绝或合并拒绝都不会替换当前引用，旧快照继续服务。</li>
 *   <li><b>紧急关闭</b>：{@link #emergencyKill(boolean)} 不经过外部来源，立即合成 kill 快照换引用，
 *       可通过 {@code emergencyKill(false)} 或回滚到历史版本解除。</li>
 *   <li><b>回滚</b>：保留最近 N 份历史快照（含合成 kill 快照），{@link #rollback(long)} 可回到任意版本。</li>
 * </ol>
 */
@Component
public class FeatureFlagManager {

    private static final Logger log = LoggerFactory.getLogger(FeatureFlagManager.class);

    private final FeatureFlagProperties properties;
    private final SnapshotMerger merger;
    private final FeatureEvaluator evaluator;
    private final List<ConfigSource> sources;

    private volatile FlagSnapshot current;
    private final Deque<FlagSnapshot> history = new ArrayDeque<>();
    private long nextVersion = 1;

    @org.springframework.beans.factory.annotation.Autowired
    public FeatureFlagManager(FeatureFlagProperties properties, SnapshotMerger merger, FeatureEvaluator evaluator) {
        this(properties, merger, evaluator, defaultSources(properties));
    }

    /** 测试与扩展用：直接指定来源（按优先级从低到高）。 */
    public FeatureFlagManager(FeatureFlagProperties properties, SnapshotMerger merger, FeatureEvaluator evaluator,
                              List<ConfigSource> sources) {
        this.properties = properties;
        this.merger = merger;
        this.evaluator = evaluator;
        this.sources = List.copyOf(sources);
        RefreshResult initial = refresh();
        if (!initial.success()) {
            // 首次启动即失败：current 保持 null，判定走明确的 ERROR_NO_CONFIG 兜底，绝不使用任何隐式默认值
            log.warn("initial feature-flag load failed, service starts with NO active config: {}", initial.rejections());
        }
    }

    private static List<ConfigSource> defaultSources(FeatureFlagProperties properties) {
        List<ConfigSource> list = new ArrayList<>();
        list.add(new PropertiesFileConfigSource("base", Path.of(properties.getBaseFile()), true));
        list.add(new PropertiesFileConfigSource("override", Path.of(properties.getOverrideFile()), false));
        return list;
    }

    /**
     * 从所有来源重新加载并原子切换。
     * 全程持有锁以保证两次 refresh 串行；但读路径不取锁，只读 volatile 引用。
     */
    public RefreshResult refresh() {
        synchronized (this) {
            List<RawSourceData> raws = new ArrayList<>();
            List<Rejection> hardRejections = new ArrayList<>();
            List<Rejection> warnings = new ArrayList<>();

            for (ConfigSource source : sources) {
                try {
                    raws.add(source.load());
                } catch (SourceLoadException e) {
                    Rejection rejection = new Rejection(source.name(), e.getKind(), e.getMessage());
                    if (source instanceof PropertiesFileConfigSource pf && !pf.isRequired()) {
                        warnings.add(rejection);
                    } else {
                        hardRejections.add(rejection);
                    }
                }
            }

            if (!hardRejections.isEmpty()) {
                hardRejections.addAll(warnings);
                log.warn("refresh rejected at load phase, keeping snapshot {}, reasons={}",
                        current == null ? "null" : current.getSnapshotId(), hardRejections);
                return RefreshResult.rejected(hardRejections);
            }

            // 版本号在合并前分配：被拒绝的刷新也消耗一个版本号，
            // 保证后续成功快照不会与失败尝试或回滚中的快照撞版本
            long version = nextVersion++;
            MergeOutcome outcome = merger.merge(raws, version);
            if (!outcome.isAccepted()) {
                List<Rejection> all = new ArrayList<>(outcome.rejections());
                all.addAll(warnings);
                log.warn("refresh rejected at merge phase, keeping snapshot {}, reasons={}",
                        current == null ? "null" : current.getSnapshotId(), outcome.rejections());
                return RefreshResult.rejected(all);
            }

            FlagSnapshot merged = outcome.snapshot();
            // 紧急 kill 生效期间，来源刷新不得意外解除 kill：保留当前 kill 状态
            FlagSnapshot toPublish = merged;
            if (current != null && current.isGlobalKillSwitch() && !merged.isGlobalKillSwitch()) {
                // 先把新配置的非 kill 版本留存到历史，保证解除 kill 后能回到最新配置而非更旧的配置
                history.addFirst(merged);
                trimHistory();
                FlagSnapshot killedView = new FlagSnapshot(merged.getSnapshotId() + "-killed", merged.getVersion(),
                        merged.getCreatedAt(), true, merged.getFlags());
                toPublish = killedView;
                log.warn("global kill-switch remains in force; new config stored as {} and active view {}",
                        merged.getSnapshotId(), killedView.getSnapshotId());
            }
            publish(toPublish);
            log.info("refresh accepted, new active snapshot={} version={} warnings={}",
                    toPublish.getSnapshotId(), toPublish.getVersion(), warnings);
            return RefreshResult.accepted(toPublish, warnings);
        }
    }

    /**
     * 紧急关闭/解除。
     * <p>{@code on=true}：立即发布 globalKillSwitch=true 的合成快照（基于当前配置，仅改 kill 位），
     * 版本号用负数标记为“紧急合成”；{@code on=false}：回到最近一份非 kill 历史快照。</p>
     */
    public void emergencyKill(boolean on) {
        synchronized (this) {
            if (on) {
                FlagSnapshot base = current;
                long killVersion = -(nextVersion);
                nextVersion++;
                FlagSnapshot killed = new FlagSnapshot(
                        "snap-emergency-kill-v" + killVersion, killVersion, System.currentTimeMillis(),
                        true, base == null ? Map.of() : base.getFlags());
                publish(killed);
                log.warn("EMERGENCY KILL SWITCH ENGAGED, active snapshot={}", killed.getSnapshotId());
            } else {
                FlagSnapshot restore = findLatestNonKill();
                if (restore == null) {
                    log.warn("cannot release emergency kill: no non-kill historical snapshot found, trigger a refresh first");
                    return;
                }
                publish(restore);
                log.warn("emergency kill released, restored snapshot={}", restore.getSnapshotId());
            }
        }
    }

    /** 回滚到指定版本；被回滚的当前版本仍留在历史头部，可再次 rollback 撤销。 */
    public boolean rollback(long version) {
        synchronized (this) {
            FlagSnapshot target = null;
            for (FlagSnapshot snapshot : history) {
                if (snapshot.getVersion() == version) {
                    target = snapshot;
                    break;
                }
            }
            if (target == null) {
                log.warn("rollback failed: version {} not found in history {}", version, versionList());
                return false;
            }
            publish(target);
            log.warn("ROLLBACK performed, active snapshot={} version={}", target.getSnapshotId(), target.getVersion());
            return true;
        }
    }

    public Decision evaluate(String flagKey, RequestContext context) {
        FlagSnapshot snapshot = this.current;
        Decision decision = evaluator.evaluate(snapshot, flagKey, context, properties.isFailOpen());
        if (log.isInfoEnabled()) {
            log.info("feature decision flag={} visitor={} on={} reason={} snapshot={}\n  trace:\n    {}",
                    decision.flagKey(), decision.visitorId(), decision.on(), decision.reason(),
                    decision.snapshotId() == null ? "(none)" : decision.snapshotId(),
                    String.join("\n    ", decision.trace()));
        }
        return decision;
    }

    public FlagSnapshot currentSnapshot() {
        return current;
    }

    public List<FlagSnapshot> history() {
        synchronized (this) {
            return List.copyOf(history);
        }
    }

    private void publish(FlagSnapshot snapshot) {
        // 去重：同一版本若已在历史中（rollback 场景），先移除再置顶
        history.removeIf(existing -> existing.getVersion() == snapshot.getVersion()
                && existing.isGlobalKillSwitch() == snapshot.isGlobalKillSwitch());
        history.addFirst(snapshot);
        int maxHistory = Math.max(1, properties.getHistorySize());
        while (history.size() > maxHistory) {
            history.removeLast();
        }
        current = snapshot;
    }

    private void trimHistory() {
        int maxHistory = Math.max(1, properties.getHistorySize());
        while (history.size() > maxHistory) {
            history.removeLast();
        }
    }

    private FlagSnapshot findLatestNonKill() {
        for (FlagSnapshot snapshot : history) {
            if (!snapshot.isGlobalKillSwitch()) {
                return snapshot;
            }
        }
        return null;
    }

    private List<Long> versionList() {
        List<Long> versions = new ArrayList<>();
        for (FlagSnapshot snapshot : history) {
            versions.add(snapshot.getVersion());
        }
        return versions;
    }
}
