package com.github.lisama569.runtimefeatureflagsjava.tests;

import com.github.lisama569.runtimefeatureflagsjava.flag.disable.DisableOutcome;
import com.github.lisama569.runtimefeatureflagsjava.flag.disable.DisableRejectionReason;
import com.github.lisama569.runtimefeatureflagsjava.flag.disable.DisableView;
import com.github.lisama569.runtimefeatureflagsjava.flag.model.EvaluationReason;
import com.github.lisama569.runtimefeatureflagsjava.flag.model.EvaluationResult;
import com.github.lisama569.runtimefeatureflagsjava.flag.model.RequestContext;
import com.github.lisama569.runtimefeatureflagsjava.flag.service.FeatureFlagManager;
import com.github.lisama569.runtimefeatureflagsjava.flag.source.InMemoryConfigSource;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖按开关临时停用（局部层紧急控制）：
 * - 停用跨刷新（含失败刷新）持续有效，只能显式恢复或到期解除；
 * - 到期自动恢复后结论与"从未停用"完全一致；
 * - 全局总闸与局部停用叠加时归因可区分；
 * - 多条停用指令命中同一开关时合并结果说得清；
 * - 容量超限整体拒绝、现状不变；
 * - 查询视图包含开关/原因/操作人/到期时间/基准配置版本。
 */
class TemporaryDisableTest {

    private static final String FLAG = "feature.hotfix";
    private static final String FLAG_B = "feature.hotfix-b";
    private static final RequestContext CTX = new RequestContext("user-1", Map.of());

    /** 可手动推进的时钟，用于到期语义测试。 */
    private static final class MutableClock extends Clock {
        private final AtomicLong millis;

        MutableClock(long start) {
            this.millis = new AtomicLong(start);
        }

        void advance(long delta) {
            millis.addAndGet(delta);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis.get());
        }

        @Override
        public long millis() {
            return millis.get();
        }
    }

    private InMemoryConfigSource sourceWith(Map<String, String> props) {
        InMemoryConfigSource remote = new InMemoryConfigSource("remote-center", 100);
        remote.setProperties(props);
        return remote;
    }

    private Map<String, String> goodProps() {
        return Map.of(FLAG + ".enabled", "true", FLAG + ".percentage", "100");
    }

    private FeatureFlagManager managerWithGoodConfig(InMemoryConfigSource remote, MutableClock clock) {
        FeatureFlagManager manager = clock == null
                ? new FeatureFlagManager(List.of(remote))
                : new FeatureFlagManager(List.of(remote), 8, clock);
        assertTrue(manager.refresh().accepted());
        return manager;
    }

    @Test
    void disableSurvivesSuccessfulAndFailedRefreshesUntilExplicitResume() {
        InMemoryConfigSource remote = sourceWith(goodProps());
        FeatureFlagManager manager = managerWithGoodConfig(remote, null);
        String baseSnapshot = manager.currentSnapshotId();
        assertTrue(manager.evaluate(FLAG, CTX).enabled());

        DisableOutcome disabled = manager.disable(FLAG, "线上资损排查", "sre-lisa");
        assertTrue(disabled.accepted());
        EvaluationResult off = manager.evaluate(FLAG, CTX);
        assertFalse(off.enabled());
        assertEquals(EvaluationReason.FLAG_TEMPORARILY_DISABLED, off.reason());
        assertNotNull(off.disableId());
        System.out.println("[TEST] 停用生效: disableId=" + off.disableId()
                + " reason=" + off.reason() + " detail=" + off.detail());

        // 配置推送（好配置）：停用不得被解除
        remote.setProperties(Map.of(FLAG + ".enabled", "true",
                FLAG + ".percentage", "100",
                FLAG_B + ".enabled", "true", FLAG_B + ".percentage", "100"));
        assertTrue(manager.refresh().accepted());
        EvaluationResult stillOff = manager.evaluate(FLAG, CTX);
        assertFalse(stillOff.enabled());
        assertEquals(EvaluationReason.FLAG_TEMPORARILY_DISABLED, stillOff.reason());
        assertEquals(off.disableId(), stillOff.disableId());

        // 配置推送（坏配置，刷新失败）：停用同样不得被解除
        remote.setProperties(Map.of(FLAG + ".enabled", "not-a-bool"));
        assertFalse(manager.refresh().accepted());
        EvaluationResult afterFail = manager.evaluate(FLAG, CTX);
        assertFalse(afterFail.enabled());
        assertEquals(EvaluationReason.FLAG_TEMPORARILY_DISABLED, afterFail.reason());
        System.out.println("[TEST] 停用跨好/坏刷新均有效: snapshot="
                + afterFail.snapshotId() + " disableId=" + afterFail.disableId());

        // 显式恢复后按当前配置判定（100% -> ON）
        DisableOutcome resumed = manager.resume(FLAG);
        assertTrue(resumed.accepted());
        EvaluationResult restored = manager.evaluate(FLAG, CTX);
        assertTrue(restored.enabled());
        assertNull(restored.disableId());
        assertEquals(0, manager.activeDisables().size());
        System.out.println("[TEST] 显式恢复后结论=" + restored.enabled()
                + " (baseSnapshot=" + baseSnapshot + ")");
    }

    @Test
    void timedDisableAutoResumesAndMatchesNeverDisabledDecision() {
        MutableClock clock = new MutableClock(1_000_000L);
        InMemoryConfigSource remote = sourceWith(goodProps());
        FeatureFlagManager manager = managerWithGoodConfig(remote, clock);

        // 参照组：从未停用过，直接按配置判定
        EvaluationResult reference = manager.evaluate(FLAG, CTX);
        assertTrue(reference.enabled());

        // 停用 10 分钟
        DisableOutcome disabled = manager.disable(FLAG, "灰度异常", "sre-lisa", 10 * 60_000L);
        assertTrue(disabled.accepted());
        assertFalse(manager.evaluate(FLAG, CTX).enabled());

        // 9 分钟后仍停用
        clock.advance(9 * 60_000L);
        assertFalse(manager.evaluate(FLAG, CTX).enabled());
        assertEquals(1, manager.activeDisables().size());

        // 到点后自动恢复：结论必须与"从未停用"逐项一致
        clock.advance(60_000L + 1);
        EvaluationResult afterExpiry = manager.evaluate(FLAG, CTX);
        assertEquals(reference.enabled(), afterExpiry.enabled());
        assertEquals(reference.reason(), afterExpiry.reason());
        assertNull(afterExpiry.disableId());
        assertEquals(0, manager.activeDisables().size(),
                "到期条目应自动恢复，不再出现在生效列表");
        System.out.println("[TEST] 到期自动恢复: reason=" + afterExpiry.reason()
                + " 与从未停用结论一致=" + afterExpiry.enabled());

        // 到期后条目不再占用容量：恢复操作应判 NOT_DISABLED
        DisableOutcome resumeAgain = manager.resume(FLAG);
        assertFalse(resumeAgain.accepted());
        assertEquals(DisableRejectionReason.NOT_DISABLED, resumeAgain.rejectionReason());
    }

    @Test
    void killSwitchAndLocalDisableAttributionIsDistinguishable() {
        InMemoryConfigSource remote = sourceWith(goodProps());
        FeatureFlagManager manager = managerWithGoodConfig(remote, null);

        // 仅局部停用：归因到局部层
        manager.disable(FLAG, "局部熔断", "sre-lisa");
        EvaluationResult localOnly = manager.evaluate(FLAG, CTX);
        assertEquals(EvaluationReason.FLAG_TEMPORARILY_DISABLED, localOnly.reason());
        assertNotNull(localOnly.disableId());

        // 叠加全局总闸：归因到全局层，同时带出局部命中记录
        manager.emergencyShutdown("全站故障");
        EvaluationResult both = manager.evaluate(FLAG, CTX);
        assertFalse(both.enabled());
        assertEquals(EvaluationReason.KILL_SWITCH_ACTIVE, both.reason());
        assertEquals(localOnly.disableId(), both.disableId(),
                "全局层关闭时也应带出同时命中的局部停用记录");
        assertTrue(both.detail().contains("全局层") && both.detail().contains("局部层"),
                "叠加时判定细节应能区分两层: " + both.detail());
        System.out.println("[TEST] 叠加归因: reason=" + both.reason()
                + " disableId=" + both.disableId() + " detail=" + both.detail());

        // 回滚解除总闸后，局部停用仍在生效（停用不被刷新类动作解除）
        manager.rollback();
        EvaluationResult localAgain = manager.evaluate(FLAG, CTX);
        assertEquals(EvaluationReason.FLAG_TEMPORARILY_DISABLED, localAgain.reason());

        // 恢复局部停用后回到正常判定
        manager.resume(FLAG);
        assertTrue(manager.evaluate(FLAG, CTX).enabled());
    }

    @Test
    void multipleDirectivesOnSameFlagMergeIntoExplainableView() {
        InMemoryConfigSource remote = sourceWith(goodProps());
        FeatureFlagManager manager = managerWithGoodConfig(remote, null);
        String baseSnapshot = manager.currentSnapshotId();

        assertTrue(manager.disable(FLAG, "资损排查", "sre-lisa").accepted());
        assertTrue(manager.disable(FLAG, "依赖故障", "sre-tom", 30 * 60_000L).accepted());

        List<DisableView> views = manager.activeDisables();
        assertEquals(1, views.size(), "同一开关多条指令应合并为一个视图");
        DisableView view = views.get(0);
        assertEquals(FLAG, view.flagKey());
        assertEquals(2, view.directiveIds().size());
        assertTrue(view.reason().contains("资损排查") && view.reason().contains("依赖故障"),
                "合并视图应保留全部原因: " + view.reason());
        assertTrue(view.operator().contains("sre-lisa") && view.operator().contains("sre-tom"),
                "合并视图应保留全部操作人: " + view.operator());
        assertNull(view.expiresAtMillis(), "任一指令永久则合并结果永久");
        assertEquals(baseSnapshot, view.baseSnapshotId(),
                "合并视图应记录最早指令生效时基于的配置版本");

        EvaluationResult r = manager.evaluate(FLAG, CTX);
        assertEquals(EvaluationReason.FLAG_TEMPORARILY_DISABLED, r.reason());
        assertEquals(view.mergedId(), r.disableId());
        System.out.println("[TEST] 多指令合并: ids=" + view.directiveIds()
                + " reason=" + view.reason() + " operator=" + view.operator()
                + " baseSnapshot=" + view.baseSnapshotId());

        // 显式恢复一次即解除该开关的全部指令
        assertTrue(manager.resume(FLAG).accepted());
        assertTrue(manager.evaluate(FLAG, CTX).enabled());
    }

    @Test
    void capacityExceededIsRejectedWithDistinctReasonAndStateUnchanged() {
        MutableClock clock = new MutableClock(1_000_000L);
        InMemoryConfigSource remote = sourceWith(goodProps());
        FeatureFlagManager manager = managerWithGoodConfig(remote, clock); // 容量=8

        // 批量停用 8 个（占满容量），整批原子生效
        List<String> batch = List.of("feature.f1", "feature.f2", "feature.f3", "feature.f4",
                "feature.f5", "feature.f6", "feature.f7", "feature.f8");
        DisableOutcome filled = manager.disableAll(batch, "批量熔断", "sre-lisa", null);
        assertTrue(filled.accepted());
        assertEquals(8, manager.activeDisables().size());

        // 再停一个：超限，必须整体拒绝且现状不变
        DisableOutcome overflow = manager.disable("feature.f9", "超额", "sre-lisa");
        assertFalse(overflow.accepted());
        assertEquals(DisableRejectionReason.CAPACITY_EXCEEDED, overflow.rejectionReason());
        assertEquals(8, manager.activeDisables().size(),
                "超限拒绝后不得静默丢掉任何已生效条目");
        assertFalse(manager.evaluate("feature.f9", CTX).reason()
                == EvaluationReason.FLAG_TEMPORARILY_DISABLED);
        System.out.println("[TEST] 超限拒绝: reason=" + overflow.rejectionReason()
                + " detail=" + overflow.detail());

        // 超限的批量请求同样整体拒绝（不部分生效）
        DisableOutcome batchOverflow = manager.disableAll(
                List.of("feature.x1", "feature.x2"), "批量超额", "sre-lisa", null);
        assertFalse(batchOverflow.accepted());
        assertEquals(DisableRejectionReason.CAPACITY_EXCEEDED, batchOverflow.rejectionReason());
        assertEquals(8, manager.activeDisables().size());

        // 恢复一个后腾出容量，可以再停用
        assertTrue(manager.resume("feature.f1").accepted());
        assertTrue(manager.disable("feature.f9", "补位", "sre-lisa").accepted());
        assertEquals(8, manager.activeDisables().size());
    }

    @Test
    void disableViewExposesReasonOperatorExpiryAndBaseSnapshot() {
        MutableClock clock = new MutableClock(1_000_000L);
        InMemoryConfigSource remote = sourceWith(goodProps());
        FeatureFlagManager manager = managerWithGoodConfig(remote, clock);
        String baseSnapshot = manager.currentSnapshotId();

        assertTrue(manager.disable(FLAG, "资损排查", "sre-lisa", 10 * 60_000L).accepted());
        DisableView view = manager.activeDisables().get(0);
        assertEquals(FLAG, view.flagKey());
        assertEquals("资损排查", view.reason());
        assertEquals("sre-lisa", view.operator());
        assertEquals(1_000_000L + 10 * 60_000L, view.expiresAtMillis());
        assertEquals(baseSnapshot, view.baseSnapshotId());
        System.out.println("[TEST] 停用查询视图: " + view);

        // 非法请求可区分：空开关名 / 缺原因 / 非正 TTL
        assertEquals(DisableRejectionReason.INVALID_REQUEST,
                manager.disable("  ", "r", "o").rejectionReason());
        assertEquals(DisableRejectionReason.INVALID_REQUEST,
                manager.disable(FLAG, " ", "o").rejectionReason());
        assertEquals(DisableRejectionReason.INVALID_REQUEST,
                manager.disable(FLAG, "r", "o", 0L).rejectionReason());
        assertEquals(1, manager.activeDisables().size(), "非法请求不得改变现状");
    }
}
