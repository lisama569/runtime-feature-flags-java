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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 场景：按开关紧急停用的基本语义。
 * - 单个/一组开关停用，携带原因与操作人，判定一律 OFF 且原因 FLAG_DISABLED；
 * - 同一开关多条停用指令命中时，合并结果可看清每一条（id 互不相同）；
 * - 显式恢复（按开关 / 按指令 id）后结论恢复；恢复不存在的目标给出 NOT_FOUND；
 * - 非法参数（空原因/空操作人/空列表/负 TTL）被区分拒绝；
 * - 停用未定义开关、在"从未加载快照"时停用，仍然强制关闭且归因明确。
 */
class EmergencyDisableTest {

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
    void singleFlagDisableCarriesReasonOperatorAndBaseSnapshotThenRestore() {
        FeatureFlagManager manager = managerWithGoodConfig();
        String snapshotId = manager.currentSnapshotId();
        assertTrue(manager.evaluate(FLAG, CTX).enabled(), "停用前应正常放量");

        DisableOutcome outcome = manager.disable(FLAG, "支付链路错误率飙升", "oncall-alice");
        assertTrue(outcome.accepted(), () -> "停用应被接受: " + outcome.detail());
        assertEquals(1, outcome.directives().size());
        DisableDirective d = outcome.directives().get(0);
        assertEquals(FLAG, d.flagKey());
        assertEquals("支付链路错误率飙升", d.reason());
        assertEquals("oncall-alice", d.operator());
        assertTrue(d.permanent(), "无 TTL 应为不限期");
        assertEquals(snapshotId, d.baseSnapshotId(), "应记录基于哪一版配置生效");
        assertNotEquals(0L, d.baseSnapshotVersion());
        System.out.println("[TEST] 停用成功: id=" + d.id() + " baseSnapshot=" + d.baseSnapshotId());

        EvaluationResult off = manager.evaluate(FLAG, CTX);
        assertFalse(off.enabled());
        assertEquals(EvaluationReason.FLAG_DISABLED, off.reason());
        assertFalse(off.alsoGlobalKillActive(), "仅局部停用，总闸未开");
        assertEquals(1, off.disableDirectives().size());
        assertEquals(d.id(), off.disableDirectives().get(0).id());
        System.out.println("[TEST] 停用后判定: reason=" + off.reason()
                + " detail=" + off.detail());

        assertEquals(1, manager.activeDisableCount());
        // 显式恢复后，结论与"从未停用"一致（按当前 100% 配置 -> ON）
        assertTrue(manager.restore(FLAG).accepted());
        EvaluationResult back = manager.evaluate(FLAG, CTX);
        assertTrue(back.enabled());
        assertEquals(EvaluationReason.PERCENTAGE_BUCKETED, back.reason());
        assertTrue(back.disableDirectives().isEmpty());
    }

    @Test
    void batchDisableIsAllOrNothingAndBothFlagsGoOff() {
        FeatureFlagManager manager = managerWithGoodConfig();
        String other = "feature.another";
        // 一组开关原子停用
        DisableOutcome batch = manager.disable(List.of(FLAG, other),
                "数据库主从延迟", "sre-bob", 0L);
        assertTrue(batch.accepted());
        assertEquals(2, batch.directives().size());
        assertFalse(manager.evaluate(FLAG, CTX).enabled());
        assertEquals(EvaluationReason.FLAG_DISABLED, manager.evaluate(FLAG, CTX).reason());
        // 另一开关即便配置里未定义，停用层照样关闭
        assertFalse(manager.evaluate(other, CTX).enabled());
        assertEquals(EvaluationReason.FLAG_DISABLED, manager.evaluate(other, CTX).reason());
        assertEquals(2, manager.activeDisables().size());
        System.out.println("[TEST] 批量停用: "
                + batch.directives().stream().map(DisableDirective::flagKey).toList());
    }

    @Test
    void multipleDirectivesOnSameFlagMergeIntoOneExplainableResult() {
        FeatureFlagManager manager = managerWithGoodConfig();
        DisableOutcome first = manager.disable(FLAG, "原因A", "alice");
        DisableOutcome second = manager.disable(FLAG, "原因B", "bob");
        assertTrue(first.accepted());
        assertTrue(second.accepted());
        assertNotEquals(first.directives().get(0).id(), second.directives().get(0).id());

        List<DisableDirective> active = manager.activeDisablesFor(FLAG);
        assertEquals(2, active.size());

        EvaluationResult off = manager.evaluate(FLAG, CTX);
        assertFalse(off.enabled());
        assertEquals(EvaluationReason.FLAG_DISABLED, off.reason());
        assertEquals(2, off.disableDirectives().size());
        System.out.println("[TEST] 多指令合并判定 detail=" + off.detail());

        // 按指令 id 只恢复一条，另一条仍生效
        String idToRestore = first.directives().get(0).id();
        assertTrue(manager.restoreById(idToRestore).accepted());
        assertEquals(1, manager.activeDisablesFor(FLAG).size());
        assertFalse(manager.evaluate(FLAG, CTX).enabled());
        // 再恢复最后一条 -> 开关恢复
        assertTrue(manager.restore(FLAG).accepted());
        assertTrue(manager.evaluate(FLAG, CTX).enabled());
    }

    @Test
    void invalidArgumentsAreRejectedWithDistinguishableReasonAndStateUntouched() {
        FeatureFlagManager manager = managerWithGoodConfig();
        assertEquals(DisableFailureReason.INVALID_ARGUMENT,
                manager.disable(List.of(), "r", "op", 0L).failureReason());
        assertEquals(DisableFailureReason.INVALID_ARGUMENT,
                manager.disable(List.of(FLAG), "  ", "op", 0L).failureReason());
        assertEquals(DisableFailureReason.INVALID_ARGUMENT,
                manager.disable(List.of(FLAG), "r", " ", 0L).failureReason());
        assertEquals(DisableFailureReason.INVALID_ARGUMENT,
                manager.disable(List.of(FLAG), "r", "op", -5L).failureReason());
        assertEquals(0, manager.activeDisableCount(), "非法请求不得改变现状");
        // 开关仍按配置正常放量
        assertTrue(manager.evaluate(FLAG, CTX).enabled());
        System.out.println("[TEST] 非法停用参数均被拒绝，现状不变");
    }

    @Test
    void restoreUnknownTargetReturnsNotFoundAndKeepsState() {
        FeatureFlagManager manager = managerWithGoodConfig();
        DisableOutcome none = manager.restore(FLAG);
        assertFalse(none.accepted());
        assertEquals(DisableFailureReason.NOT_FOUND, none.failureReason());
        DisableOutcome byId = manager.restoreById("ds-not-exist");
        assertEquals(DisableFailureReason.NOT_FOUND, byId.failureReason());
        System.out.println("[TEST] 恢复不存在目标: " + none.detail());
    }

    @Test
    void disableWorksEvenWhenFlagUndefinedOrNoSnapshotLoaded() {
        // 正常快照中停用未定义开关：停用层先于 FLAG_NOT_FOUND
        FeatureFlagManager manager = managerWithGoodConfig();
        assertTrue(manager.disable("feature.undefined-x", "止血", "alice").accepted());
        EvaluationResult disabled = manager.evaluate("feature.undefined-x", CTX);
        assertFalse(disabled.enabled());
        assertEquals(EvaluationReason.FLAG_DISABLED, disabled.reason());
        assertTrue(manager.restore("feature.undefined-x").accepted());
        assertEquals(EvaluationReason.FLAG_NOT_FOUND,
                manager.evaluate("feature.undefined-x", CTX).reason());

        // 从未成功加载任何快照时停用：结论仍是 FLAG_DISABLED 而非 NO_CONFIG_LOADED
        InMemoryConfigSource dead = new InMemoryConfigSource("remote-center", 100);
        dead.setUnavailable(true);
        FeatureFlagManager noSnapshot = new FeatureFlagManager(List.of(dead));
        assertFalse(noSnapshot.refresh().accepted());
        assertTrue(noSnapshot.disable(FLAG, "全局故障", "bob").accepted());
        EvaluationResult noSnapDisabled = noSnapshot.evaluate(FLAG, CTX);
        assertFalse(noSnapDisabled.enabled());
        assertEquals(EvaluationReason.FLAG_DISABLED, noSnapDisabled.reason());
        assertNull(noSnapDisabled.snapshotId());
        assertTrue(noSnapshot.restore(FLAG).accepted());
        assertEquals(EvaluationReason.NO_CONFIG_LOADED,
                noSnapshot.evaluate(FLAG, CTX).reason());
        System.out.println("[TEST] 未定义开关/无快照下停用均生效，恢复后回到原归因");
    }
}
