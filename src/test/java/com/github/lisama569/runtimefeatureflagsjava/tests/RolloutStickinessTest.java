package com.github.lisama569.runtimefeatureflagsjava.tests;

import com.github.lisama569.runtimefeatureflagsjava.flag.model.EvaluationReason;
import com.github.lisama569.runtimefeatureflagsjava.flag.model.EvaluationResult;
import com.github.lisama569.runtimefeatureflagsjava.flag.model.RequestContext;
import com.github.lisama569.runtimefeatureflagsjava.flag.service.FeatureFlagManager;
import com.github.lisama569.runtimefeatureflagsjava.flag.service.RefreshOutcome;
import com.github.lisama569.runtimefeatureflagsjava.flag.source.InMemoryConfigSource;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖分级放量的稳定性与粘性：
 * - 分桶只由 salt+visitorId 决定，与当前比例无关；
 * - 放量比例从 0 增到 100 时，同一访问者只可能 OFF->ON 一次，不会反复抖动；
 * - 比例从 100 降到 0 时只可能 ON->OFF，无抖动；
 * - 同一快照下结论可复现；
 * - 白名单与定向条件优先于比例判定。
 */
class RolloutStickinessTest {

    private static final String FLAG = "feature.gradual";

    private Map<String, String> props(int percentage, String extraAllowList) {
        Map<String, String> p = new HashMap<>();
        p.put(FLAG + ".enabled", "true");
        p.put(FLAG + ".percentage", String.valueOf(percentage));
        if (extraAllowList != null) {
            p.put(FLAG + ".allowlist", extraAllowList);
        }
        return p;
    }

    private boolean decide(FeatureFlagManager m, String visitor) {
        return m.evaluate(FLAG, new RequestContext(visitor, Map.of())).enabled();
    }

    @Test
    void decisionIsMonotonicWhilePercentageRampsUpAndDown() {
        InMemoryConfigSource remote = new InMemoryConfigSource("remote-center", 100);
        FeatureFlagManager manager = new FeatureFlagManager(List.of(remote));

        Map<String, Boolean> previous = new HashMap<>();
        for (String visitor : sampleVisitors()) {
            previous.put(visitor, false);
        }

        // 0 -> 100：只允许 OFF->ON 的单调翻转
        int turnedOnAtLeastOnce = 0;
        for (int pct = 0; pct <= 100; pct++) {
            remote.setProperties(props(pct, null));
            RefreshOutcome out = manager.refresh();
            assertTrue(out.accepted(), "比例 " + pct + " 的配置应合法: " + out.detail());
            for (String visitor : sampleVisitors()) {
                boolean now = decide(manager, visitor);
                boolean was = previous.get(visitor);
                assertFalse(was && !now,
                        "放量增加过程中访问者 " + visitor + " 在 " + pct + "% 发生 ON->OFF 抖动");
                if (!was && now) {
                    turnedOnAtLeastOnce++;
                }
                previous.put(visitor, now);
            }
        }
        assertTrue(turnedOnAtLeastOnce >= sampleVisitors().length,
                "100% 时所有访问者都应已翻为 ON");
        for (String visitor : sampleVisitors()) {
            assertTrue(previous.get(visitor), "100% 放量应全部命中");
        }

        // 100 -> 0：只允许 ON->OFF 的单调翻转（收缩不会让人重新抖回 ON）
        for (int pct = 100; pct >= 0; pct--) {
            remote.setProperties(props(pct, null));
            manager.refresh();
            for (String visitor : sampleVisitors()) {
                boolean now = decide(manager, visitor);
                boolean was = previous.get(visitor);
                assertFalse(!was && now,
                        "放量收缩过程中访问者 " + visitor + " 在 " + pct + "% 发生 OFF->ON 抖动");
                previous.put(visitor, now);
            }
        }
        for (String visitor : sampleVisitors()) {
            assertFalse(previous.get(visitor), "0% 放量应全部未命中");
        }
        System.out.println("[TEST] 放量 0->100->0 全程无反复抖动，粘性成立");
    }

    @Test
    void bucketIsStableAcrossUnrelatedConfigChangesAndReproducible() {
        InMemoryConfigSource remote = new InMemoryConfigSource("remote-center", 100);
        remote.setProperties(props(50, null));
        FeatureFlagManager manager = new FeatureFlagManager(List.of(remote));
        manager.refresh();

        // 记录 50% 下每个访问者的结论
        Map<String, Boolean> first = new HashMap<>();
        for (String visitor : sampleVisitors()) {
            first.put(visitor, decide(manager, visitor));
        }

        // 刷新一份"内容完全相同"的新快照（新版本号、同内容）：结论必须逐项可复现
        manager.refresh();
        for (String visitor : sampleVisitors()) {
            assertEquals(first.get(visitor), decide(manager, visitor),
                    "同内容不同快照下 " + visitor + " 结论应可复现");
        }

        // 改为 50% + 无关白名单（不命中本批访问者）：桶位阈值未变，结论仍一致
        remote.setProperties(props(50, "someone-else-999"));
        manager.refresh();
        for (String visitor : sampleVisitors()) {
            assertEquals(first.get(visitor), decide(manager, visitor),
                    "无关配置变化不应改变 " + visitor + " 的桶位结论");
        }
        System.out.println("[TEST] 同内容刷新与无关配置变化下结论可复现");
    }

    @Test
    void allowlistBypassesPercentageAndTargetingGatesRollout() {
        InMemoryConfigSource remote = new InMemoryConfigSource("remote-center", 100);
        Map<String, String> p = props(0, "vip-001");
        p.put(FLAG + ".target.channel", "beta");
        remote.setProperties(p);
        FeatureFlagManager manager = new FeatureFlagManager(List.of(remote));
        manager.refresh();

        // 0% 放量 + 非白名单 + 定向不命中 -> OFF（定向先于比例拦截）
        EvaluationResult outsider = manager.evaluate(FLAG,
                new RequestContext("normal-1", Map.of("channel", "prod")));
        assertFalse(outsider.enabled());
        assertEquals(EvaluationReason.TARGETING_NOT_MATCHED, outsider.reason());

        // 定向命中但 0% 放量 -> OFF
        EvaluationResult betaUser = manager.evaluate(FLAG,
                new RequestContext("normal-2", Map.of("channel", "beta")));
        assertFalse(betaUser.enabled());
        assertEquals(EvaluationReason.PERCENTAGE_BUCKETED, betaUser.reason());

        // 白名单：即便 0% 放量也直接 ON
        EvaluationResult vip = manager.evaluate(FLAG,
                new RequestContext("vip-001", Map.of("channel", "prod")));
        assertTrue(vip.enabled());
        assertEquals(EvaluationReason.ALLOWLIST_MATCH, vip.reason());
        System.out.println("[TEST] 白名单旁路放量，定向不匹配先于比例拦截");
    }

    private String[] sampleVisitors() {
        String[] visitors = new String[200];
        for (int i = 0; i < visitors.length; i++) {
            visitors[i] = String.format("visitor-%04d", i);
        }
        return visitors;
    }
}
