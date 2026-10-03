package com.github.lisama569.runtimefeatureflagsjava.tests;

import com.github.lisama569.runtimefeatureflagsjava.flag.model.EvaluationReason;
import com.github.lisama569.runtimefeatureflagsjava.flag.model.EvaluationResult;
import com.github.lisama569.runtimefeatureflagsjava.flag.model.RequestContext;
import com.github.lisama569.runtimefeatureflagsjava.flag.service.DisableDirective;
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
 * 场景：停用一旦生效，任何刷新动作都不能无声解除它。
 * - 刷新成功（新配置推送/来源覆盖变化）后停用仍在；
 * - 刷新失败（非法取值/来源不可达）后停用仍在；
 * - 回滚不能解除停用；紧急关闭不解除停用，且叠加时判因为 KILL_SWITCH_ACTIVE
 *   同时带出局部停用指令；回滚掉 KILL 快照后仍是 FLAG_DISABLED；
 * - 判定结果携带各字段的来源出处，可对账。
 */
class DisableSurvivesRefreshTest {

    private static final String FLAG = "feature.releasable";
    private static final RequestContext CTX = new RequestContext("visitor-7", Map.of());

    @Test
    void disableSurvivesSuccessfulRefreshAndExpiryOrRestoreOnlyReleasesIt() {
        InMemoryConfigSource remote = new InMemoryConfigSource("remote-center", 100);
        remote.setProperties(Map.of(
                FLAG + ".enabled", "true", FLAG + ".percentage", "100"));
        FeatureFlagManager manager = new FeatureFlagManager(List.of(remote));
        String v1 = manager.refresh().activeSnapshotId();
        assertTrue(manager.disable(FLAG, "事故止血", "alice").accepted());
        String directiveId = manager.activeDisablesFor(FLAG).get(0).id();

        // 远端推来全新配置（放量收到 0），刷新成功
        remote.setProperties(Map.of(
                FLAG + ".enabled", "true", FLAG + ".percentage", "0"));
        RefreshOutcome v2 = manager.refresh();
        assertTrue(v2.accepted());
        assertNotEquals(v1, v2.activeSnapshotId());

        EvaluationResult stillOff = manager.evaluate(FLAG, CTX);
        assertFalse(stillOff.enabled());
        assertEquals(EvaluationReason.FLAG_DISABLED, stillOff.reason());
        assertEquals(1, stillOff.disableDirectives().size());
        assertEquals(directiveId, stillOff.disableDirectives().get(0).id(),
                "刷新后命中的必须是同一条停用指令");
        System.out.println("[TEST] 刷新成功后停用仍在: snapshot=" + stillOff.snapshotId()
                + " directive=" + directiveId);

        // 恢复后结论必须与"从未停用、直接按当前配置判定"完全一致：percentage=0 -> OFF
        assertTrue(manager.restore(FLAG).accepted());
        EvaluationResult restored = manager.evaluate(FLAG, CTX);
        assertFalse(restored.enabled());
        assertEquals(EvaluationReason.PERCENTAGE_BUCKETED, restored.reason());
        assertTrue(restored.detail().contains("percentage=0"));
        assertTrue(restored.disableDirectives().isEmpty());
    }

    @Test
    void disableSurvivesFailedRefreshAndSourceOutage() {
        InMemoryConfigSource remote = new InMemoryConfigSource("remote-center", 100);
        remote.setProperties(Map.of(
                FLAG + ".enabled", "true", FLAG + ".percentage", "100"));
        FeatureFlagManager manager = new FeatureFlagManager(List.of(remote));
        manager.refresh();
        assertTrue(manager.disable(FLAG, "止血", "alice").accepted());
        String snapshotBefore = manager.currentSnapshotId();
        String directiveId = manager.activeDisablesFor(FLAG).get(0).id();

        // 非法配置刷新失败
        remote.setProperties(Map.of(
                FLAG + ".enabled", "maybe", FLAG + ".percentage", "100"));
        RefreshOutcome invalid = manager.refresh();
        assertFalse(invalid.accepted());
        assertEquals(RefreshFailureReason.INVALID_VALUE, invalid.failureReason());
        assertEquals(snapshotBefore, manager.currentSnapshotId());

        // 来源不可达刷新失败
        remote.setUnavailable(true);
        RefreshOutcome unavailable = manager.refresh();
        assertFalse(unavailable.accepted());
        assertEquals(RefreshFailureReason.SOURCE_UNAVAILABLE, unavailable.failureReason());
        remote.setUnavailable(false);

        EvaluationResult off = manager.evaluate(FLAG, CTX);
        assertEquals(EvaluationReason.FLAG_DISABLED, off.reason());
        assertEquals(directiveId, off.disableDirectives().get(0).id());
        assertEquals(snapshotBefore, off.snapshotId());
        System.out.println("[TEST] 非法取值/来源缺失两次失败刷新后停用仍在，快照仍是 "
                + snapshotBefore);
    }

    @Test
    void rollbackAndEmergencyKillDoNotReleaseDisable() {
        InMemoryConfigSource remote = new InMemoryConfigSource("remote-center", 100);
        remote.setProperties(Map.of(
                FLAG + ".enabled", "true", FLAG + ".percentage", "100"));
        FeatureFlagManager manager = new FeatureFlagManager(List.of(remote));
        String v1 = manager.refresh().activeSnapshotId();
        remote.setProperties(Map.of(
                FLAG + ".enabled", "true", FLAG + ".percentage", "40"));
        String v2 = manager.refresh().activeSnapshotId();

        // 在 v2 上停用
        assertTrue(manager.disable(FLAG, "止血", "alice").accepted());

        // 回滚到 v1：快照回退，停用不得被解除
        RefreshOutcome rolledBack = manager.rollback();
        assertTrue(rolledBack.accepted());
        assertEquals(v1, rolledBack.activeSnapshotId());
        assertEquals(EvaluationReason.FLAG_DISABLED, manager.evaluate(FLAG, CTX).reason());
        System.out.println("[TEST] 回滚 " + v2 + " -> " + v1 + " 后停用仍在");

        // 紧急关闭：两层叠加，判因仍是总闸层，但局部停用指令随结论带出
        manager.emergencyShutdown("全站故障");
        EvaluationResult killed = manager.evaluate(FLAG, CTX);
        assertFalse(killed.enabled());
        assertEquals(EvaluationReason.KILL_SWITCH_ACTIVE, killed.reason());
        assertTrue(killed.alsoGlobalKillActive());
        assertEquals(1, killed.disableDirectives().size());
        System.out.println("[TEST] 总闸+局部叠加: reason=" + killed.reason()
                + " 附带停用=" + killed.disableDirectives().stream()
                .map(DisableDirective::id).toList());

        // 回滚掉 KILL 快照：总闸解除，但局部停用仍在 -> FLAG_DISABLED
        RefreshOutcome undoKill = manager.rollback();
        assertTrue(undoKill.accepted());
        EvaluationResult localOnly = manager.evaluate(FLAG, CTX);
        assertFalse(localOnly.enabled());
        assertEquals(EvaluationReason.FLAG_DISABLED, localOnly.reason());
        assertFalse(localOnly.alsoGlobalKillActive());

        // 只有显式恢复才能解除
        assertTrue(manager.restore(FLAG).accepted());
        assertTrue(manager.evaluate(FLAG, CTX).enabled());
    }

    @Test
    void evaluationResultCarriesFieldOriginsForReconciliation() {
        InMemoryConfigSource local = new InMemoryConfigSource("local-defaults", 10);
        local.setProperties(Map.of(
                FLAG + ".enabled", "true",
                FLAG + ".percentage", "100",
                FLAG + ".salt", "shared-salt"));
        InMemoryConfigSource remote = new InMemoryConfigSource("remote-center", 100);
        remote.setProperties(Map.of(
                // 高优先级覆盖放量比例
                FLAG + ".percentage", "30"));
        FeatureFlagManager manager = new FeatureFlagManager(List.of(local, remote));
        assertTrue(manager.refresh().accepted());

        EvaluationResult r = manager.evaluate(FLAG, CTX);
        assertEquals("remote-center", r.fieldOrigins().get("percentage"),
                "percentage 应来自高优先级来源");
        assertEquals("local-defaults", r.fieldOrigins().get("enabled"),
                "未被覆盖的字段应记录本地来源");
        assertEquals("local-defaults", r.fieldOrigins().get("salt"));
        System.out.println("[TEST] 字段出处: " + r.fieldOrigins());
    }
}
