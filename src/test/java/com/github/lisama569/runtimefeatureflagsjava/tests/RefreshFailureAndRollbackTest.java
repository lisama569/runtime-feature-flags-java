package com.github.lisama569.runtimefeatureflagsjava.tests;

import com.github.lisama569.runtimefeatureflagsjava.flag.model.EvaluationReason;
import com.github.lisama569.runtimefeatureflagsjava.flag.model.EvaluationResult;
import com.github.lisama569.runtimefeatureflagsjava.flag.model.RequestContext;
import com.github.lisama569.runtimefeatureflagsjava.flag.service.FeatureFlagManager;
import com.github.lisama569.runtimefeatureflagsjava.flag.service.RefreshFailureReason;
import com.github.lisama569.runtimefeatureflagsjava.flag.service.RefreshOutcome;
import com.github.lisama569.runtimefeatureflagsjava.flag.source.InMemoryConfigSource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖：
 * - 刷新失败保持旧配置可用；
 * - 紧急关闭（全局总闸）一键让所有开关 fail-CLOSED，原因码可区分；
 * - 回滚从总闸状态恢复到关闭前快照，结论完全恢复；
 * - 无快照/开关未定义/判定异常等兜底语义明确。
 */
class RefreshFailureAndRollbackTest {

    private static final String FLAG = "feature.releasable";
    private static final RequestContext CTX = new RequestContext("visitor-7", Map.of());

    private FeatureFlagManager managerWithGoodConfig() {
        InMemoryConfigSource remote = new InMemoryConfigSource("remote-center", 100);
        remote.setProperties(Map.of(
                FLAG + ".enabled", "true", FLAG + ".percentage", "100"));
        FeatureFlagManager manager = new FeatureFlagManager(List.of(remote));
        assertTrue(manager.refresh().accepted());
        return manager;
    }

    @Test
    void emergencyKillForcesAllFlagsOffAndRollbackRestores() {
        FeatureFlagManager manager = managerWithGoodConfig();
        String beforeKill = manager.currentSnapshotId();
        assertTrue(manager.evaluate(FLAG, CTX).enabled());

        // 紧急关闭
        RefreshOutcome killed = manager.emergencyShutdown("线上故障演练");
        assertTrue(killed.accepted());
        assertNotEquals(beforeKill, killed.activeSnapshotId());

        EvaluationResult off = manager.evaluate(FLAG, CTX);
        assertFalse(off.enabled());
        assertEquals(EvaluationReason.KILL_SWITCH_ACTIVE, off.reason());
        assertEquals(killed.activeSnapshotId(), off.snapshotId());
        System.out.println("[TEST] 紧急关闭后判定: reason=" + off.reason()
                + " snapshot=" + off.snapshotId() + " detail=" + off.detail());

        // 回滚：恢复到关闭前快照，同一访问者结论恢复 ON
        RefreshOutcome rolledBack = manager.rollback();
        assertTrue(rolledBack.accepted());
        assertEquals(beforeKill, rolledBack.activeSnapshotId());
        EvaluationResult restored = manager.evaluate(FLAG, CTX);
        assertTrue(restored.enabled());
        assertEquals(beforeKill, restored.snapshotId());
        System.out.println("[TEST] 回滚后恢复快照=" + restored.snapshotId());
    }

    @Test
    void failedRefreshDoesNotChangeActiveSnapshotAndRollbackAfterReleases() {
        InMemoryConfigSource remote = new InMemoryConfigSource("remote-center", 100);
        remote.setProperties(Map.of(
                FLAG + ".enabled", "true", FLAG + ".percentage", "100"));
        FeatureFlagManager manager = new FeatureFlagManager(List.of(remote));
        String v1 = manager.refresh().activeSnapshotId();

        // 发布 v2：放量调到 40
        remote.setProperties(Map.of(
                FLAG + ".enabled", "true", FLAG + ".percentage", "40"));
        String v2 = manager.refresh().activeSnapshotId();
        assertNotEquals(v1, v2);

        // 推送坏配置被拒绝，当前仍是 v2
        remote.setProperties(Map.of(
                FLAG + ".enabled", "maybe", FLAG + ".percentage", "40"));
        RefreshOutcome rejected = manager.refresh();
        assertFalse(rejected.accepted());
        assertEquals(RefreshFailureReason.INVALID_VALUE, rejected.failureReason());
        assertEquals(v2, manager.currentSnapshotId());
        // 失败的刷新不得进入回滚历史
        assertEquals(1, manager.historySnapshotIds().size());

        // 回滚回到 v1
        RefreshOutcome backToV1 = manager.rollback();
        assertTrue(backToV1.accepted());
        assertEquals(v1, manager.currentSnapshotId());
        System.out.println("[TEST] 坏配置拒绝后回滚: v2 -> " + manager.currentSnapshotId()
                + " 拒绝原因=" + rejected.failureReason());
    }

    @Test
    void rollbackWithoutHistoryIsRejectedAndKeepsCurrentSnapshot() {
        FeatureFlagManager manager = managerWithGoodConfig();
        String current = manager.currentSnapshotId();
        RefreshOutcome nothing = manager.rollback();
        assertFalse(nothing.accepted());
        assertEquals(current, manager.currentSnapshotId());
        System.out.println("[TEST] 无历史可回滚: " + nothing.detail());
    }

    @Test
    void fallbackSemanticsAreExplicitWhenNoSnapshotOrFlagMissing() {
        // 来源完全不可用，从未成功加载 -> NO_CONFIG_LOADED，fail-CLOSED
        InMemoryConfigSource dead = new InMemoryConfigSource("remote-center", 100);
        dead.setUnavailable(true);
        FeatureFlagManager manager = new FeatureFlagManager(List.of(dead));
        RefreshOutcome rejected = manager.refresh();
        assertFalse(rejected.accepted());
        assertEquals(RefreshFailureReason.SOURCE_UNAVAILABLE, rejected.failureReason());

        EvaluationResult noConfig = manager.evaluate(FLAG, CTX);
        assertFalse(noConfig.enabled());
        assertEquals(EvaluationReason.NO_CONFIG_LOADED, noConfig.reason());
        System.out.println("[TEST] 兜底(无快照): reason=" + noConfig.reason()
                + " detail=" + noConfig.detail());

        // 正常快照里查询未定义开关 -> FLAG_NOT_FOUND，fail-CLOSED
        FeatureFlagManager good = managerWithGoodConfig();
        EvaluationResult missingFlag = good.evaluate("feature.unknown", CTX);
        assertFalse(missingFlag.enabled());
        assertEquals(EvaluationReason.FLAG_NOT_FOUND, missingFlag.reason());
        System.out.println("[TEST] 兜底(开关不存在): reason=" + missingFlag.reason()
                + " snapshot=" + missingFlag.snapshotId());

        // 总开关关闭 -> MASTER_SWITCH_OFF，fail-CLOSED
        InMemoryConfigSource disabled = new InMemoryConfigSource("remote-center", 100);
        disabled.setProperties(Map.of(FLAG + ".enabled", "false"));
        FeatureFlagManager offManager = new FeatureFlagManager(List.of(disabled));
        offManager.refresh();
        assertEquals(EvaluationReason.MASTER_SWITCH_OFF,
                offManager.evaluate(FLAG, CTX).reason());
    }
}
