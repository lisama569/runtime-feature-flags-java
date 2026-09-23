package com.github.lisama569.runtimefeatureflagsjava.tests;

import com.github.lisama569.runtimefeatureflagsjava.flag.model.EvaluationReason;
import com.github.lisama569.runtimefeatureflagsjava.flag.model.EvaluationResult;
import com.github.lisama569.runtimefeatureflagsjava.flag.model.RequestContext;
import com.github.lisama569.runtimefeatureflagsjava.flag.service.FeatureFlagManager;
import com.github.lisama569.runtimefeatureflagsjava.flag.service.RefreshOutcome;
import com.github.lisama569.runtimefeatureflagsjava.flag.source.InMemoryConfigSource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖：多来源优先级合并 + 热更新后结论变化且仍可复现。
 */
class SourcePriorityTest {

    private RequestContext ctx(String visitor) {
        return new RequestContext(visitor, Map.of());
    }

    @Test
    void higherPrioritySourceOverridesLowerOne() {
        InMemoryConfigSource local = new InMemoryConfigSource("local-defaults", 10);
        local.setProperties(Map.of(
                "feature.shared.enabled", "false",
                "feature.shared.percentage", "0",
                "feature.only-local.enabled", "true",
                "feature.only-local.percentage", "100"));
        InMemoryConfigSource remote = new InMemoryConfigSource("remote-center", 100);
        remote.setProperties(Map.of(
                // 同键覆盖：总开关翻为 true、放量 100%
                "feature.shared.enabled", "true",
                "feature.shared.percentage", "100",
                "feature.only-remote.enabled", "false"));

        FeatureFlagManager manager = new FeatureFlagManager(List.of(local, remote));
        RefreshOutcome outcome = manager.refresh();
        assertTrue(outcome.accepted());
        System.out.println("[TEST] 合并后生效快照=" + manager.currentSnapshotId());

        // 被高优先级覆盖为 true + 100%
        EvaluationResult shared = manager.evaluate("feature.shared", ctx("user-1"));
        assertTrue(shared.enabled());
        assertEquals(EvaluationReason.PERCENTAGE_BUCKETED, shared.reason());
        assertEquals(manager.currentSnapshotId(), shared.snapshotId());

        // 低优先级独有键仍然保留生效
        EvaluationResult onlyLocal = manager.evaluate("feature.only-local", ctx("user-2"));
        assertTrue(onlyLocal.enabled());

        // 高优先级独有键生效
        EvaluationResult onlyRemote = manager.evaluate("feature.only-remote", ctx("user-3"));
        assertFalse(onlyRemote.enabled());
        assertEquals(EvaluationReason.MASTER_SWITCH_OFF, onlyRemote.reason());
    }

    @Test
    void priorityOverrideChangesAfterHotUpdateAndStaysReproducible() {
        InMemoryConfigSource local = new InMemoryConfigSource("local-defaults", 10);
        local.setProperties(Map.of(
                "feature.shared.enabled", "true",
                "feature.shared.percentage", "100"));
        InMemoryConfigSource remote = new InMemoryConfigSource("remote-center", 100);
        remote.setProperties(Map.of()); // 初始没有覆盖

        FeatureFlagManager manager = new FeatureFlagManager(List.of(local, remote));
        manager.refresh();
        assertTrue(manager.evaluate("feature.shared", ctx("user-1")).enabled());
        String firstSnapshot = manager.currentSnapshotId();

        // 热更新：远端推下覆盖配置，关闭该开关
        remote.setProperties(Map.of(
                "feature.shared.enabled", "false"));
        RefreshOutcome second = manager.refresh();
        assertTrue(second.accepted());
        assertFalse(manager.evaluate("feature.shared", ctx("user-1")).enabled());
        assertEquals(EvaluationReason.MASTER_SWITCH_OFF,
                manager.evaluate("feature.shared", ctx("user-1")).reason());

        // 同一快照下反复判定结论可复现
        for (int i = 0; i < 5; i++) {
            assertFalse(manager.evaluate("feature.shared", ctx("user-1")).enabled());
        }
        // 新快照标识与旧快照可区分
        assertFalse(firstSnapshot.equals(manager.currentSnapshotId()));
        System.out.println("[TEST] 热更新切换: " + firstSnapshot
                + " -> " + manager.currentSnapshotId());
    }
}
