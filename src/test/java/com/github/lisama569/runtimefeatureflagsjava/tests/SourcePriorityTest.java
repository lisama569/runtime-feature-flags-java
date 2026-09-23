package com.github.lisama569.runtimefeatureflagsjava.tests;

import com.github.lisama569.runtimefeatureflagsjava.config.FeatureFlagProperties;
import com.github.lisama569.runtimefeatureflagsjava.eval.FeatureEvaluator;
import com.github.lisama569.runtimefeatureflagsjava.manager.FeatureFlagManager;
import com.github.lisama569.runtimefeatureflagsjava.merge.SnapshotMerger;
import com.github.lisama569.runtimefeatureflagsjava.model.FlagDefinition;
import com.github.lisama569.runtimefeatureflagsjava.model.RefreshResult;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 来源优先级：高优先级来源逐字段覆盖低优先级，未覆盖字段仍取低优先级，provenance 可解释。 */
class SourcePriorityTest {

    private static FeatureFlagManager manager(Path dir, String base, String override) throws Exception {
        Files.writeString(dir.resolve("base.properties"), base);
        Files.writeString(dir.resolve("override.properties"), override);
        List<ConfigSource> sources = List.of(
                new PropertiesFileConfigSource("base", dir.resolve("base.properties"), true),
                new PropertiesFileConfigSource("override", dir.resolve("override.properties"), false));
        FeatureFlagProperties props = new FeatureFlagProperties();
        props.setHistorySize(10);
        return new FeatureFlagManager(props, new SnapshotMerger(), new FeatureEvaluator(), sources);
    }

    @Test
    void higherPrioritySourceWinsFieldByField(@TempDir Path dir) throws Exception {
        FeatureFlagManager manager = manager(dir, """
                flag.f.enabled=true
                flag.f.percentage=1000
                flag.f.allowlist=u-1
                """, """
                flag.f.percentage=9000
                flag.f.allowlist=u-2
                """);

        RefreshResult result = manager.refresh();
        assertTrue(result.success(), "refresh must succeed: " + result.rejections());
        FlagDefinition def = manager.currentSnapshot().getFlags().get("f");

        // enabled 未在高优先级定义 -> 保留 base
        assertTrue(def.isEnabled(), "enabled should come from base");
        // percentage/allowlist 被高优先级覆盖
        assertEquals(9000, def.getPercentage());
        assertEquals(List.of("u-2"), def.getAllowlist());

        // provenance 必须指出每个字段的依据来源
        assertEquals("base", def.getProvenance().get("enabled"));
        assertEquals("override", def.getProvenance().get("percentage"));
        assertEquals("override", def.getProvenance().get("allowlist"));

        System.out.println("[SourcePriority] snapshot=" + manager.currentSnapshot().getSnapshotId()
                + " provenance=" + def.getProvenance());
    }

    @Test
    void missingOptionalSourceIsWarningNotFallbackToDefaults(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("base.properties"), """
                flag.f.enabled=true
                flag.f.percentage=5000
                """);
        List<ConfigSource> sources = List.of(
                new PropertiesFileConfigSource("base", dir.resolve("base.properties"), true),
                new PropertiesFileConfigSource("override", dir.resolve("override.properties"), false));
        FeatureFlagProperties props = new FeatureFlagProperties();
        FeatureFlagManager manager = new FeatureFlagManager(props, new SnapshotMerger(), new FeatureEvaluator(), sources);

        RefreshResult result = manager.refresh();
        assertTrue(result.success(), "optional source missing must not block refresh");
        assertEquals(1, result.warnings().size(), "missing optional source must be reported, not silent");
        assertEquals("override", result.warnings().get(0).sourceName());
        assertNotNull(result.snapshotId());
        // base 的取值完整生效，没有任何“默认值”混入
        assertEquals(5000, manager.currentSnapshot().getFlags().get("f").getPercentage());

        System.out.println("[SourcePriority] optional missing warning=" + result.warnings());
    }

    @Test
    void missingRequiredSourceHardRejectsAndKeepsPreviousSnapshot(@TempDir Path dir) throws Exception {
        FeatureFlagManager manager = manager(dir, """
                flag.f.enabled=true
                flag.f.percentage=5000
                """, """
                flag.f.percentage=6000
                """);
        assertEquals(6000, manager.currentSnapshot().getFlags().get("f").getPercentage());
        String firstSnapshot = manager.currentSnapshot().getSnapshotId();

        // 删除必需来源后刷新 -> 硬拒绝，旧快照原样可用
        Files.delete(dir.resolve("base.properties"));
        RefreshResult result = manager.refresh();
        assertFalse(result.success());
        assertEquals(1, result.rejections().size());
        assertNotNull(result.rejections().get(0).kind());
        assertEquals(firstSnapshot, manager.currentSnapshot().getSnapshotId(),
                "previous usable snapshot must remain active");

        System.out.println("[SourcePriority] hard rejection=" + result.rejections()
                + " retained=" + manager.currentSnapshot().getSnapshotId());
    }
}
