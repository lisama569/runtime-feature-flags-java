package com.github.lisama569.runtimefeatureflagsjava.tests;

import com.github.lisama569.runtimefeatureflagsjava.flag.model.EvaluationReason;
import com.github.lisama569.runtimefeatureflagsjava.flag.model.EvaluationResult;
import com.github.lisama569.runtimefeatureflagsjava.flag.model.RequestContext;
import com.github.lisama569.runtimefeatureflagsjava.flag.service.DisableDirective;
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
 * 场景：停用 TTL 到期自动恢复。
 * - 未到期：FLAG_DISABLED；
 * - 到点后无需任何操作，判定结论立即恢复为"按当前配置直接判定"（含同 visitor 同桶位），
 *   指令从生效列表消失，如同从未停用；
 * - 多条不同到期时间的指令：最晚到期者决定何时解除；
 * - 对已到期指令做 restoreById 返回 NOT_FOUND；
 * - 到期条目不占容量（配合下一个容量测试）。
 */
class DisableExpiryTest {

    private static final String FLAG = "feature.ttl";
    private static final RequestContext CTX = new RequestContext("visitor-ttl", Map.of());

    /** 可控时钟：测试手动推进时间。 */
    private final AtomicLong clock = new AtomicLong(1_000_000L);

    private FeatureFlagManager managerWith(String percentage) {
        InMemoryConfigSource remote = new InMemoryConfigSource("remote-center", 100);
        remote.setProperties(Map.of(
                FLAG + ".enabled", "true", FLAG + ".percentage", percentage));
        FeatureFlagManager manager = new FeatureFlagManager(List.of(remote), clock::get);
        assertTrue(manager.refresh().accepted());
        return manager;
    }

    @Test
    void ttlExpiryReleasesAutomaticallyAndDecisionMatchesNeverDisabled() {
        FeatureFlagManager manager = managerWith("100");
        // 停用前结论（作为"从未停用"基准）
        boolean baseline = manager.evaluate(FLAG, CTX).enabled();
        assertTrue(baseline);

        DisableOutcome outcome = manager.disable(List.of(FLAG),
                "临时止血 10 分钟", "alice", 10 * 60_000L);
        assertTrue(outcome.accepted());
        DisableDirective d = outcome.directives().get(0);
        assertEquals(clock.get() + 10 * 60_000L, d.expireAtMillis());
        assertFalse(d.permanent());

        // 推进 9 分 59 秒：仍停用
        clock.addAndGet(10 * 60_000L - 1);
        assertEquals(EvaluationReason.FLAG_DISABLED, manager.evaluate(FLAG, CTX).reason());
        assertEquals(1, manager.activeDisables().size());

        // 再推进 1ms 到点：自动恢复，无需显式操作
        clock.addAndGet(1);
        EvaluationResult restored = manager.evaluate(FLAG, CTX);
        assertTrue(restored.enabled());
        assertEquals(EvaluationReason.PERCENTAGE_BUCKETED, restored.reason());
        assertTrue(restored.disableDirectives().isEmpty(),
                "到期后命中指令列表必须为空，等同于从未停用");
        assertEquals(0, manager.activeDisables().size());
        assertEquals(0, manager.activeDisableCount());
        System.out.println("[TEST] TTL 到点自动恢复: reason=" + restored.reason()
                + " detail=" + restored.detail());
    }

    @Test
    void expiryRestoresToExactlyWhatCurrentConfigWouldSay() {
        // 当前配置放量 0%：到期后必须是 OFF 且 PERCENTAGE_BUCKETED（而不是沿用停用结论）
        FeatureFlagManager managerOff = managerWith("0");
        managerOff.disable(List.of(FLAG), "r", "op", 1_000L);
        clock.addAndGet(1_001L);
        EvaluationResult r0 = managerOff.evaluate(FLAG, CTX);
        assertFalse(r0.enabled());
        assertEquals(EvaluationReason.PERCENTAGE_BUCKETED, r0.reason());
        assertTrue(r0.detail().contains("percentage=0"));

        // 100% 配置：到期后 ON
        FeatureFlagManager managerOn = managerWith("100");
        managerOn.disable(List.of(FLAG), "r", "op", 1_000L);
        clock.addAndGet(1_001L);
        assertTrue(managerOn.evaluate(FLAG, CTX).enabled());

        // 到期期间配置发生了刷新（100% -> 70%）：到期后按新配置判定，与从未停用一致
        InMemoryConfigSource liveSource = new InMemoryConfigSource("remote-center", 100);
        liveSource.setProperties(Map.of(
                FLAG + ".enabled", "true", FLAG + ".percentage", "100"));
        FeatureFlagManager manager = new FeatureFlagManager(
                List.of(liveSource), clock::get);
        assertTrue(manager.refresh().accepted());
        // 停用前先记录"当前 100% 配置下"的基准
        boolean before = manager.evaluate(FLAG, CTX).enabled();
        assertTrue(before);
        manager.disable(List.of(FLAG), "r", "op", 5_000L);
        // 停用中途推送 70% 配置并刷新成功
        clock.addAndGet(1_000L);
        liveSource.setProperties(Map.of(
                FLAG + ".enabled", "true", FLAG + ".percentage", "70"));
        assertTrue(manager.refresh().accepted());
        assertEquals(EvaluationReason.FLAG_DISABLED, manager.evaluate(FLAG, CTX).reason());
        // 到期：结论必须等于"直接按 70% 新配置判定"
        clock.addAndGet(5_000L);
        EvaluationResult afterExpiry = manager.evaluate(FLAG, CTX);
        assertEquals(EvaluationReason.PERCENTAGE_BUCKETED, afterExpiry.reason());
        assertTrue(afterExpiry.detail().contains("percentage=70"));
        System.out.println("[TEST] 到期后严格回到当前配置结论: 0%->OFF/100%->ON/"
                + "停用中刷新到70%->按70%判定 均符合, visitor=" + CTX.visitorId()
                + " -> " + (afterExpiry.enabled() ? "ON" : "OFF"));
    }

    @Test
    void directiveWithLatestExpiryDeterminesRelease() {
        FeatureFlagManager manager = managerWith("100");
        DisableOutcome shortLived = manager.disable(List.of(FLAG), "短", "a", 1_000L);
        DisableOutcome longLived = manager.disable(List.of(FLAG), "长", "b", 5_000L);
        assertTrue(shortLived.accepted());
        assertTrue(longLived.accepted());

        clock.addAndGet(1_001L);
        // 短的到期，长的仍在 -> 仍然停用
        assertEquals(EvaluationReason.FLAG_DISABLED, manager.evaluate(FLAG, CTX).reason());
        assertEquals(1, manager.activeDisablesFor(FLAG).size());

        clock.addAndGet(4_000L);
        // 长的也到期 -> 恢复
        assertTrue(manager.evaluate(FLAG, CTX).enabled());
        assertEquals(0, manager.activeDisables().size());
        System.out.println("[TEST] 最晚到期指令决定解除时刻");
    }

    @Test
    void restoringExpiredDirectiveReturnsNotFoundAndPermanentNeverAutoReleases() {
        FeatureFlagManager manager = managerWith("100");
        DisableOutcome expired = manager.disable(List.of(FLAG), "短", "a", 100L);
        String expiredId = expired.directives().get(0).id();
        clock.addAndGet(101L);
        DisableOutcome restore = manager.restoreById(expiredId);
        assertFalse(restore.accepted());
        assertEquals(DisableFailureReason.NOT_FOUND, restore.failureReason());

        // 不限期停用：时间推进很久仍在
        DisableOutcome permanent = manager.disable(List.of(FLAG), "长期", "a", 0L);
        String permId = permanent.directives().get(0).id();
        clock.addAndGet(365L * 24 * 60 * 60_000L);
        assertEquals(1, manager.activeDisables().size());
        assertEquals(permId, manager.activeDisablesFor(FLAG).get(0).id());
        assertEquals(EvaluationReason.FLAG_DISABLED, manager.evaluate(FLAG, CTX).reason());
        System.out.println("[TEST] 已到期指令恢复=NOT_FOUND；不限期指令不自动解除");
    }
}
