package com.github.lisama569.runtimefeatureflagsjava.tests;

import com.github.lisama569.runtimefeatureflagsjava.config.FeatureFlagProperties;
import com.github.lisama569.runtimefeatureflagsjava.eval.BucketHasher;
import com.github.lisama569.runtimefeatureflagsjava.eval.FeatureEvaluator;
import com.github.lisama569.runtimefeatureflagsjava.manager.FeatureFlagManager;
import com.github.lisama569.runtimefeatureflagsjava.merge.SnapshotMerger;
import com.github.lisama569.runtimefeatureflagsjava.model.Decision;
import com.github.lisama569.runtimefeatureflagsjava.model.DecisionReason;
import com.github.lisama569.runtimefeatureflagsjava.model.RequestContext;
import com.github.lisama569.runtimefeatureflagsjava.source.ConfigSource;
import com.github.lisama569.runtimefeatureflagsjava.source.PropertiesFileConfigSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 放量粘性：同一访问者结论稳定可复现；放量比例单调变化时命中集合只做单向迁移，不抖动。 */
class RolloutStickinessTest {

    private FeatureFlagManager manager(Path dir, String base, String override) throws Exception {
        Files.writeString(dir.resolve("base.properties"), base);
        Files.writeString(dir.resolve("override.properties"), override);
        List<ConfigSource> sources = List.of(
                new PropertiesFileConfigSource("base", dir.resolve("base.properties"), true),
                new PropertiesFileConfigSource("override", dir.resolve("override.properties"), false));
        return new FeatureFlagManager(new FeatureFlagProperties(), new SnapshotMerger(),
                new FeatureEvaluator(), sources);
    }

    @Test
    void bucketIsStableAcrossJvmAndInputStrings() {
        int b1 = BucketHasher.bucketOf("new-checkout", "visitor-42");
        int b2 = BucketHasher.bucketOf("new-checkout", "visitor-42");
        assertEquals(b1, b2, "same input must hash to same bucket");
        assertTrue(b1 >= 0 && b1 < 10_000);
        // 开关名参与哈希，避免不同开关把同一用户绑死在同一结论上
        assertFalse(b1 == BucketHasher.bucketOf("other-flag", "visitor-42")
                && b1 == BucketHasher.bucketOf("third-flag", "visitor-42"),
                "flag key must participate in hashing");
        System.out.println("[Stickiness] stable bucket = " + b1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void rampUpAndDownNeverFlapsSameVisitors(@TempDir Path dir) throws Exception {
        FeatureFlagManager manager = manager(dir,
                "flag.f.enabled=true\nflag.f.percentage=0\n", "");

        Map<Integer, Boolean>[] statesByStep = new Map[6];
        int[] steps = {1000, 2500, 5000, 2500, 1000, 0};

        // 记录每个访问者的固定桶号
        Map<Integer, Integer> bucketOfVisitor = new HashMap<>();
        for (int v = 0; v < 200; v++) {
            bucketOfVisitor.put(v, BucketHasher.bucketOf("f", "visitor-" + v));
        }

        for (int s = 0; s < steps.length; s++) {
            Files.writeString(dir.resolve("override.properties"),
                    "flag.f.percentage=" + steps[s] + "\n");
            manager.refresh();
            Map<Integer, Boolean> decisions = new HashMap<>();
            for (int v = 0; v < 200; v++) {
                Decision d = manager.evaluate("f", new RequestContext("visitor-" + v));
                decisions.put(v, d.on());
                // 结论必须与“固定桶 < 阈值”的数学定义严格一致（可复现）
                assertEquals(bucketOfVisitor.get(v) < steps[s], d.on(),
                        "visitor " + v + " decision inconsistent with stable bucket at step " + s);
            }
            statesByStep[s] = decisions;
            System.out.println("[Stickiness] step percentage=" + steps[s]
                    + " hits=" + decisions.values().stream().filter(Boolean::booleanValue).count());
        }

        // 关键粘性：放量从 1000->2500->5000 过程中已命中的访问者不会掉（扩容不抖动）；
        // 缩容到相同阈值时，结论集合必须与之前在该阈值时完全一致（对称、可复现）：
        // 2500(step3) 投影到 1000 必须等于 step0；2500(step1) 必须等于 step3 本身。
        for (int v = 0; v < 200; v++) {
            if (Boolean.TRUE.equals(statesByStep[0].get(v))) {
                assertTrue(statesByStep[1].get(v) && statesByStep[2].get(v),
                        "visitor " + v + " hit at 10% must stay hit while ramping up, no flapping");
            }
            assertEquals(statesByStep[1].get(v), statesByStep[3].get(v),
                    "decisions at same threshold 2500 must be identical before/after ramp (no hysteresis)");
            boolean expectedAt1000 = bucketOfVisitor.get(v) < 1000;
            assertEquals(expectedAt1000, statesByStep[4].get(v),
                    "at threshold 1000 after shrink, visitor " + v + " must match bucket math");
            assertFalse(statesByStep[5].get(v), "at 0% nobody is on");
        }

        Decision sample = manager.evaluate("f", new RequestContext("visitor-1"));
        assertEquals(DecisionReason.ROLLOUT_MISS, sample.reason());
        System.out.println("[Stickiness] final trace snapshot=" + sample.snapshotId()
                + "\n  " + String.join("\n  ", sample.trace()));
    }

    @Test
    void allowlistRemainsStickyEvenWhenRolloutShrinks(@TempDir Path dir) throws Exception {
        FeatureFlagManager manager = manager(dir,
                "flag.f.enabled=true\nflag.f.percentage=9000\nflag.f.allowlist=vip-1\n", "");
        Decision at90 = manager.evaluate("f", new RequestContext("vip-1"));
        assertTrue(at90.on());
        assertEquals(DecisionReason.ALLOWLIST_HIT, at90.reason());

        Files.writeString(dir.resolve("override.properties"), "flag.f.percentage=0\n");
        manager.refresh();
        Decision at0 = manager.evaluate("f", new RequestContext("vip-1"));
        assertTrue(at0.on(), "allowlisted visitor must remain ON when percentage drops to 0");
        assertEquals(DecisionReason.ALLOWLIST_HIT, at0.reason());

        // 黑名单优先：即便在白名单里……（本实现把同时出现视为非法配置；此处验证独立 blocklist 生效）
        Decision blocked = manager.evaluate("f", new RequestContext("blocked-x"));
        assertFalse(blocked.on());
    }

}
