package com.github.lisama569.runtimefeatureflagsjava.tests;

import com.github.lisama569.runtimefeatureflagsjava.flag.model.RequestContext;
import com.github.lisama569.runtimefeatureflagsjava.flag.service.DisableFailureReason;
import com.github.lisama569.runtimefeatureflagsjava.flag.service.DisableOutcome;
import com.github.lisama569.runtimefeatureflagsjava.flag.service.FeatureFlagManager;
import com.github.lisama569.runtimefeatureflagsjava.flag.source.InMemoryConfigSource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 场景：生效中停用条目数量上限。
 * - 超限请求整体拒绝，拒绝原因可区分（CAPACITY_EXCEEDED），现状一条不多一条不少；
 * - 批量请求自身超过剩余容量也整体拒绝（all-or-nothing），不会只生效一半；
 * - 显式恢复 / 到期释放容量后可以再次停用；
 * - 到期条目不占容量。
 */
class DisableCapacityTest {

    private static final int CAPACITY = 3;

    private FeatureFlagManager newManager(AtomicLong clock) {
        InMemoryConfigSource remote = new InMemoryConfigSource("remote-center", 100);
        remote.setProperties(Map.of("feature.base.enabled", "true"));
        FeatureFlagManager manager = new FeatureFlagManager(
                List.of(remote), clock::get, CAPACITY);
        assertTrue(manager.refresh().accepted());
        return manager;
    }

    @Test
    void exceedingCapacityIsRejectedAndNothingChanges() {
        AtomicLong clock = new AtomicLong(0L);
        FeatureFlagManager manager = newManager(clock);

        assertTrue(manager.disable(List.of("feature.a", "feature.b"),
                "r", "op", 0L).accepted());
        assertEquals(2, manager.activeDisableCount());

        // 再要 2 条：2+2=4 > 3，整批拒绝，feature.c/feature.d 都不应出现
        DisableOutcome rejected = manager.disable(
                List.of("feature.c", "feature.d"), "r", "op", 0L);
        assertFalse(rejected.accepted());
        assertEquals(DisableFailureReason.CAPACITY_EXCEEDED, rejected.failureReason());
        assertEquals(2, manager.activeDisableCount(), "拒绝后现状必须保持不变");
        assertTrue(manager.activeDisablesFor("feature.c").isEmpty());
        assertTrue(manager.activeDisablesFor("feature.d").isEmpty());
        System.out.println("[TEST] 超容量整批拒绝: " + rejected.detail());

        // 恰好剩余 1 个名额：接受
        assertTrue(manager.disable(List.of("feature.c"), "r", "op", 0L).accepted());
        assertEquals(CAPACITY, manager.activeDisableCount());

        // 已满后再要 1 条也被拒
        DisableOutcome oneMore = manager.disable(List.of("feature.d"), "r", "op", 0L);
        assertEquals(DisableFailureReason.CAPACITY_EXCEEDED, oneMore.failureReason());
        assertEquals(CAPACITY, manager.activeDisableCount());
    }

    @Test
    void releasedCapacityCanBeReusedAfterRestore() {
        AtomicLong clock = new AtomicLong(0L);
        FeatureFlagManager manager = newManager(clock);
        manager.disable(List.of("feature.a", "feature.b", "feature.c"), "r", "op", 0L);
        assertEquals(CAPACITY, manager.activeDisableCount());
        // 释放一条
        assertTrue(manager.restore("feature.a").accepted());
        assertEquals(2, manager.activeDisableCount());
        // 名额立即可复用
        assertTrue(manager.disable(List.of("feature.d"), "r", "op", 0L).accepted());
        assertEquals(CAPACITY, manager.activeDisableCount());
        System.out.println("[TEST] 恢复释放容量后可再次停用");
    }

    @Test
    void expiredDirectivesDoNotOccupyCapacity() {
        AtomicLong clock = new AtomicLong(0L);
        FeatureFlagManager manager = newManager(clock);
        manager.disable(List.of("feature.a"), "r", "op", 1_000L);
        manager.disable(List.of("feature.b"), "r", "op", 0L);
        assertEquals(2, manager.activeDisableCount());

        // a 到期后容量释放：可再加 2 条（不超过 3）
        clock.set(1_001L);
        DisableOutcome batch = manager.disable(
                List.of("feature.c", "feature.d"), "r", "op", 0L);
        assertTrue(batch.accepted(), () -> "到期条目不应占容量: " + batch.detail());
        assertEquals(3, manager.activeDisableCount());
        // 现在 b/c/d 生效；再加 1 条应超限
        assertEquals(DisableFailureReason.CAPACITY_EXCEEDED,
                manager.disable(List.of("feature.e"), "r", "op", 0L).failureReason());
        System.out.println("[TEST] 到期释放容量，当前生效="
                + manager.activeDisables().stream().map(d -> d.flagKey()).toList());
    }

    @Test
    void evaluationStaysConsistentWhenCapacityRejected() {
        AtomicLong clock = new AtomicLong(0L);
        FeatureFlagManager manager = newManager(clock);
        RequestContext ctx = new RequestContext("v", Map.of());
        manager.disable(List.of("feature.a", "feature.b", "feature.c"), "r", "op", 0L);
        // 被拒的 d 不应影响判定：未停用且未定义 -> FLAG_NOT_FOUND（而非 FLAG_DISABLED）
        manager.disable(List.of("feature.d"), "r", "op", 0L);
        assertEquals(com.github.lisama569.runtimefeatureflagsjava.flag.model.EvaluationReason.FLAG_NOT_FOUND,
                manager.evaluate("feature.d", ctx).reason());
        assertEquals(com.github.lisama569.runtimefeatureflagsjava.flag.model.EvaluationReason.FLAG_DISABLED,
                manager.evaluate("feature.a", ctx).reason());
    }
}
