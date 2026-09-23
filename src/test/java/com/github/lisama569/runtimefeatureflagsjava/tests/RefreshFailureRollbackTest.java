package com.github.lisama569.runtimefeatureflagsjava.tests;

import com.github.lisama569.runtimefeatureflagsjava.config.FeatureFlagProperties;
import com.github.lisama569.runtimefeatureflagsjava.eval.FeatureEvaluator;
import com.github.lisama569.runtimefeatureflagsjava.manager.FeatureFlagManager;
import com.github.lisama569.runtimefeatureflagsjava.merge.SnapshotMerger;
import com.github.lisama569.runtimefeatureflagsjava.model.Decision;
import com.github.lisama569.runtimefeatureflagsjava.model.DecisionReason;
import com.github.lisama569.runtimefeatureflagsjava.model.FlagSnapshot;
import com.github.lisama569.runtimefeatureflagsjava.model.RefreshResult;
import com.github.lisama569.runtimefeatureflagsjava.model.RequestContext;
import com.github.lisama569.runtimefeatureflagsjava.source.ConfigSource;
import com.github.lisama569.runtimefeatureflagsjava.source.PropertiesFileConfigSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 刷新失败保持旧配置、回滚、紧急关闭、以及判定失败时的兜底语义。 */
class RefreshFailureRollbackTest {

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
    void rejectedRefreshKeepsPreviousSnapshotThenRecovers(@TempDir Path dir) throws Exception {
        FeatureFlagManager manager = manager(dir,
                "flag.f.enabled=true\nflag.f.percentage=1000\n", "");
        FlagSnapshot first = manager.currentSnapshot();
        assertNotNull(first);

        // 提交一个非法配置 -> 刷新失败
        Files.writeString(dir.resolve("override.properties"), "flag.f.percentage=999999\n");
        RefreshResult rejected = manager.refresh();
        assertFalse(rejected.success());
        assertEquals(1, rejected.rejections().size());
        assertEquals(first.getSnapshotId(), manager.currentSnapshot().getSnapshotId(),
                "old snapshot must stay active after rejection");

        // 旧快照上的判定结论依旧稳定可复现
        Decision d1 = manager.evaluate("f", new RequestContext("visitor-1"));
        Decision d2 = manager.evaluate("f", new RequestContext("visitor-1"));
        assertEquals(d1.on(), d2.on());
        assertEquals(first.getSnapshotId(), d1.snapshotId());

        // 恢复为合法配置 -> 新快照生效
        Files.writeString(dir.resolve("override.properties"), "flag.f.percentage=4000\n");
        RefreshResult ok = manager.refresh();
        assertTrue(ok.success());
        assertEquals(4000, manager.currentSnapshot().getFlags().get("f").getPercentage());

        System.out.println("[RefreshFailure] rejected=" + rejected.rejections()
                + "\n  retained=" + first.getSnapshotId() + "\n  recovered=" + ok.snapshotId());
    }

    @Test
    void rollbackRestoresAnyHistoricalVersionAndCanBeUndone(@TempDir Path dir) throws Exception {
        FeatureFlagManager manager = manager(dir,
                "flag.f.enabled=true\nflag.f.percentage=1000\n", "");
        long v1 = manager.currentSnapshot().getVersion();

        Files.writeString(dir.resolve("override.properties"), "flag.f.percentage=3000\n");
        manager.refresh();
        long v2 = manager.currentSnapshot().getVersion();
        assertEquals(3000, manager.currentSnapshot().getFlags().get("f").getPercentage());

        Files.writeString(dir.resolve("override.properties"), "flag.f.percentage=6000\n");
        manager.refresh();
        assertEquals(6000, manager.currentSnapshot().getFlags().get("f").getPercentage());

        // 回滚到 v1
        assertTrue(manager.rollback(v1));
        assertEquals(1000, manager.currentSnapshot().getFlags().get("f").getPercentage());
        assertEquals(v1, manager.currentSnapshot().getVersion());

        // 回滚后判定依据的是历史快照标识（可解释）
        Decision d = manager.evaluate("f", new RequestContext("visitor-9"));
        assertEquals(manager.currentSnapshot().getSnapshotId(), d.snapshotId());

        // 再滚到 v2，然后撤销回到当前（回滚操作本身也进历史）
        assertTrue(manager.rollback(v2));
        assertEquals(3000, manager.currentSnapshot().getFlags().get("f").getPercentage());

        // 回滚到不存在的版本 -> false，当前快照不变
        long before = manager.currentSnapshot().getVersion();
        assertFalse(manager.rollback(999_999));
        assertEquals(before, manager.currentSnapshot().getVersion());

        System.out.println("[Rollback] history versions="
                + manager.history().stream().map(FlagSnapshot::getVersion).toList());
    }

    @Test
    void emergencyKillForcesEverythingOffAndIsExplainableThenReleases(@TempDir Path dir) throws Exception {
        FeatureFlagManager manager = manager(dir,
                "flag.f.enabled=true\nflag.f.percentage=10000\n", "");
        assertTrue(manager.evaluate("f", new RequestContext("anyone")).on());

        // 紧急关闭：无需改文件、即使后续来源刷新也不会意外解除
        manager.emergencyKill(true);
        Decision killed = manager.evaluate("f", new RequestContext("anyone"));
        assertFalse(killed.on());
        assertEquals(DecisionReason.GLOBAL_KILL_SWITCH, killed.reason());
        assertTrue(killed.snapshotId().contains("emergency-kill"));

        Files.writeString(dir.resolve("override.properties"), "flag.f.percentage=10000\n");
        RefreshResult refreshed = manager.refresh();
        assertTrue(refreshed.success());
        Decision stillKilled = manager.evaluate("f", new RequestContext("anyone"));
        assertFalse(stillKilled.on(), "refresh must not silently release the kill switch");
        assertEquals(DecisionReason.GLOBAL_KILL_SWITCH, stillKilled.reason());

        // 解除 -> 回到最新非 kill 配置
        manager.emergencyKill(false);
        Decision released = manager.evaluate("f", new RequestContext("anyone"));
        assertTrue(released.on());
        assertEquals(DecisionReason.ROLLOUT_HIT, released.reason());
        assertEquals(10000, manager.currentSnapshot().getFlags().get("f").getPercentage());

        System.out.println("[Kill] killedSnapshot=" + killed.snapshotId()
                + " releasedSnapshot=" + released.snapshotId());
    }

    @Test
    void fallbackSemanticsAreExplicitFailClosedAndFailOpen(@TempDir Path dir) throws Exception {
        // 场景 A：必需来源非法 -> 从无可用快照，fail-closed 兜底 OFF，原因 ERROR_NO_CONFIG
        Files.writeString(dir.resolve("base.properties"), "flag.f.enabled=not-a-boolean\n");
        FeatureFlagProperties closedProps = new FeatureFlagProperties(); // failOpen=false 默认
        FeatureFlagManager closed = new FeatureFlagManager(closedProps, new SnapshotMerger(),
                new FeatureEvaluator(),
                List.of(new PropertiesFileConfigSource("base", dir.resolve("base.properties"), true)));
        assertNull(closed.currentSnapshot());
        Decision noConfig = closed.evaluate("f", new RequestContext("visitor-1"));
        assertFalse(noConfig.on(), "fail-closed default must resolve to OFF");
        assertEquals(DecisionReason.ERROR_NO_CONFIG, noConfig.reason());

        // 场景 B：快照存在但开关未定义 -> ERROR_FLAG_NOT_FOUND，同样 fail-closed
        Files.writeString(dir.resolve("base.properties"), "flag.other.enabled=true\n");
        FeatureFlagManager hasConfig = new FeatureFlagManager(new FeatureFlagProperties(),
                new SnapshotMerger(), new FeatureEvaluator(),
                List.of(new PropertiesFileConfigSource("base", dir.resolve("base.properties"), true)));
        Decision missingFlag = hasConfig.evaluate("f", new RequestContext("visitor-1"));
        assertFalse(missingFlag.on());
        assertEquals(DecisionReason.ERROR_FLAG_NOT_FOUND, missingFlag.reason());

        // 场景 C：fail-open 下兜底为 ON，但 ERROR_ 原因保留，调用方可区分“兜底放开”
        FeatureFlagProperties openProps = new FeatureFlagProperties();
        openProps.setFailOpen(true);
        FeatureFlagManager open = new FeatureFlagManager(openProps, new SnapshotMerger(),
                new FeatureEvaluator(),
                List.of(new PropertiesFileConfigSource("base", dir.resolve("base.properties"), true)));
        Decision failOpen = open.evaluate("totally-unknown", new RequestContext("visitor-1"));
        assertTrue(failOpen.on(), "fail-open must resolve to ON");
        assertEquals(DecisionReason.ERROR_FLAG_NOT_FOUND, failOpen.reason(),
                "even when fail-open returns ON, the ERROR reason stays distinguishable");

        System.out.println("[Fallback] failClosed=" + noConfig.on() + "/" + noConfig.reason()
                + " missingFlag=" + missingFlag.reason()
                + " failOpen=" + failOpen.on() + "/" + failOpen.reason());
    }
}
