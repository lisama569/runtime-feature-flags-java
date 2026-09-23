package com.github.lisama569.runtimefeatureflagsjava.tests;

import com.github.lisama569.runtimefeatureflagsjava.config.FeatureFlagProperties;
import com.github.lisama569.runtimefeatureflagsjava.eval.FeatureEvaluator;
import com.github.lisama569.runtimefeatureflagsjava.manager.FeatureFlagManager;
import com.github.lisama569.runtimefeatureflagsjava.merge.SnapshotMerger;
import com.github.lisama569.runtimefeatureflagsjava.model.RefreshResult;
import com.github.lisama569.runtimefeatureflagsjava.model.Rejection;
import com.github.lisama569.runtimefeatureflagsjava.model.RejectionKind;
import com.github.lisama569.runtimefeatureflagsjava.source.ConfigSource;
import com.github.lisama569.runtimefeatureflagsjava.source.PropertiesFileConfigSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 非法取值 / 来源异常：刷新被整体拒绝，拒绝原因三类可区分，且不静默回退到低优先级或旧值以外的默认值。 */
class InvalidValueRejectionTest {

    private FeatureFlagManager managerOf(Path dir, String base, String override) throws Exception {
        Files.writeString(dir.resolve("base.properties"), base);
        Files.writeString(dir.resolve("override.properties"), override);
        List<ConfigSource> sources = List.of(
                new PropertiesFileConfigSource("base", dir.resolve("base.properties"), true),
                new PropertiesFileConfigSource("override", dir.resolve("override.properties"), false));
        return new FeatureFlagManager(new FeatureFlagProperties(), new SnapshotMerger(),
                new FeatureEvaluator(), sources);
    }

    @Test
    void invalidBooleanInWinningSourceRejectsWithoutFallingBackToLowerSource(@TempDir Path dir) throws Exception {
        FeatureFlagManager manager = managerOf(dir, """
                flag.f.enabled=true
                flag.f.percentage=5000
                """, """
                flag.f.percentage=not-a-number
                """);

        // 构造器内首次刷新就被拒绝 -> 无活动快照
        assertEquals(null, manager.currentSnapshot());

        RefreshResult result = manager.refresh();
        assertFalse(result.success());
        Rejection rejection = result.rejections().get(0);
        assertEquals(RejectionKind.INVALID_VALUE, rejection.kind());
        assertEquals("override", rejection.sourceName());
        assertTrue(rejection.detail().contains("percentage"), "detail must identify the bad key: " + rejection.detail());

        // 修复后即可生效
        Files.writeString(dir.resolve("override.properties"), "flag.f.percentage=8000\n");
        RefreshResult fixed = manager.refresh();
        assertTrue(fixed.success(), fixed.rejections().toString());
        assertEquals(8000, manager.currentSnapshot().getFlags().get("f").getPercentage());

        System.out.println("[InvalidValue] distinguishable rejection=" + rejection);
    }

    @Test
    void outOfRangeAndMalformedKeysAreInvalidValue(@TempDir Path dir) throws Exception {
        RefreshResult range = managerOf(dir, "flag.f.percentage=10001\n", "").refresh();
        assertFalse(range.success());
        assertEquals(RejectionKind.INVALID_VALUE, range.rejections().get(0).kind());

        RefreshResult unknown = managerOf(dir, "flag.f.unexpectedField=true\n", "").refresh();
        assertFalse(unknown.success());
        assertEquals(RejectionKind.INVALID_VALUE, unknown.rejections().get(0).kind());
        assertTrue(unknown.rejections().get(0).detail().contains("unknown"));

        RefreshResult overlap = managerOf(dir,
                "flag.f.enabled=true\nflag.f.allowlist=u-1\nflag.f.blocklist=u-1\n", "").refresh();
        assertFalse(overlap.success());
        assertTrue(overlap.rejections().get(0).detail().contains("both allowlist and blocklist"));

        System.out.println("[InvalidValue] range=" + range.rejections()
                + "\n  unknown=" + unknown.rejections()
                + "\n  overlap=" + overlap.rejections());
    }

    @Test
    void missingVsUnreadableVsInvalidAreThreeDistinguishableKinds(@TempDir Path dir) throws Exception {
        // 1) 必需来源缺失 -> SOURCE_MISSING
        List<ConfigSource> missing = List.of(
                new PropertiesFileConfigSource("base", dir.resolve("absent.properties"), true));
        FeatureFlagManager m1 = new FeatureFlagManager(new FeatureFlagProperties(), new SnapshotMerger(),
                new FeatureEvaluator(), missing);
        assertEquals(RejectionKind.SOURCE_MISSING, m1.refresh().rejections().get(0).kind());

        // 2) 来源存在但内容畸形（properties 非法 Unicode 转义）-> SOURCE_UNREADABLE
        Path bad = dir.resolve("bad.properties");
        Files.write(bad, "flag.f.enabled=\\uZZZZ\n".getBytes(StandardCharsets.UTF_8));
        List<ConfigSource> unreadable = List.of(
                new PropertiesFileConfigSource("base", bad, true));
        FeatureFlagManager m2 = new FeatureFlagManager(new FeatureFlagProperties(), new SnapshotMerger(),
                new FeatureEvaluator(), unreadable);
        assertEquals(RejectionKind.SOURCE_UNREADABLE, m2.refresh().rejections().get(0).kind());

        // 3) 取值非法 -> INVALID_VALUE
        RefreshResult invalid = managerOf(dir, "flag.f.enabled=maybe\n", "").refresh();
        assertEquals(RejectionKind.INVALID_VALUE, invalid.rejections().get(0).kind());

        System.out.println("[InvalidValue] three kinds verified: SOURCE_MISSING / SOURCE_UNREADABLE / INVALID_VALUE");
    }
}
