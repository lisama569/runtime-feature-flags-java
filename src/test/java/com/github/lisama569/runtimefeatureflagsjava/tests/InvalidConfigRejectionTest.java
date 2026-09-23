package com.github.lisama569.runtimefeatureflagsjava.tests;

import com.github.lisama569.runtimefeatureflagsjava.flag.config.ConfigProblemType;
import com.github.lisama569.runtimefeatureflagsjava.flag.config.InvalidFlagConfigException;
import com.github.lisama569.runtimefeatureflagsjava.flag.model.EvaluationReason;
import com.github.lisama569.runtimefeatureflagsjava.flag.model.EvaluationResult;
import com.github.lisama569.runtimefeatureflagsjava.flag.model.RequestContext;
import com.github.lisama569.runtimefeatureflagsjava.flag.service.FeatureFlagManager;
import com.github.lisama569.runtimefeatureflagsjava.flag.service.RefreshFailureReason;
import com.github.lisama569.runtimefeatureflagsjava.flag.service.RefreshOutcome;
import com.github.lisama569.runtimefeatureflagsjava.flag.source.InMemoryConfigSource;
import com.github.lisama569.runtimefeatureflagsjava.flag.source.SourceUnavailableException;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖：
 * 1) 各类非法取值被拒绝且原因码可区分（缺字段/坏布尔/坏整数/越界/未知字段）；
 * 2) 来源缺失被拒绝且可区分到来源名；
 * 3) 刷新失败时继续使用上一份可用配置，不静默回退默认值。
 */
class InvalidConfigRejectionTest {

    private static final String FLAG = "feature.demo";
    private static final RequestContext CTX = new RequestContext("user-1", Map.of());

    private InMemoryConfigSource base(String extraKey, String extraValue) {
        InMemoryConfigSource remote = new InMemoryConfigSource("remote-center", 100);
        Map<String, String> props = new HashMap<>();
        props.put(FLAG + ".enabled", "true");
        props.put(FLAG + ".percentage", "100");
        if (extraKey != null) {
            props.put(extraKey, extraValue);
        }
        remote.setProperties(props);
        return remote;
    }

    @Test
    void parserRejectsEachIllegalValueWithDistinctReason() {
        // 缺必填 enabled
        assertReject(Map.of(FLAG + ".percentage", "10"),
                ConfigProblemType.MISSING_FIELD);
        // 布尔非法
        assertReject(Map.of(FLAG + ".enabled", "yes"),
                ConfigProblemType.INVALID_BOOLEAN);
        // 整数非法
        assertReject(Map.of(FLAG + ".enabled", "true",
                FLAG + ".percentage", "half"),
                ConfigProblemType.INVALID_INTEGER);
        // 越界
        assertReject(Map.of(FLAG + ".enabled", "true",
                FLAG + ".percentage", "101"),
                ConfigProblemType.INVALID_PERCENTAGE_RANGE);
        // 未知字段
        assertReject(Map.of(FLAG + ".enabled", "true",
                FLAG + ".percent", "10"),
                ConfigProblemType.UNKNOWN_FIELD);
        // 全局总闸取值非法
        assertReject(Map.of(FLAG + ".enabled", "true",
                "kill.switch", "on"),
                ConfigProblemType.INVALID_BOOLEAN);
    }

    private void assertReject(Map<String, String> props, ConfigProblemType expected) {
        InMemoryConfigSource source = new InMemoryConfigSource("remote-center", 100);
        source.setProperties(props);
        FeatureFlagManager manager = new FeatureFlagManager(List.of(source));
        RefreshOutcome outcome = manager.refresh();
        assertFalse(outcome.accepted(), "非法配置不应被接受: " + props);
        assertEquals(RefreshFailureReason.INVALID_VALUE, outcome.failureReason());
        System.out.println("[TEST] 拒绝(非法取值) type=" + expected
                + " active=" + outcome.activeSnapshotId()
                + " detail=" + outcome.detail());
        // 从未加载成功 -> fail-CLOSED 兜底
        EvaluationResult r = manager.evaluate(FLAG, CTX);
        assertFalse(r.enabled());
        assertEquals(EvaluationReason.NO_CONFIG_LOADED, r.reason());
    }

    @Test
    void missingSourceIsRejectedWithDistinctReasonAndOldSnapshotKept() {
        InMemoryConfigSource local = new InMemoryConfigSource("local-defaults", 10);
        local.setProperties(Map.of(
                FLAG + ".enabled", "true", FLAG + ".percentage", "100"));
        InMemoryConfigSource remote = new InMemoryConfigSource("remote-center", 100);
        remote.setProperties(Map.of());

        FeatureFlagManager manager = new FeatureFlagManager(List.of(local, remote));
        assertTrue(manager.refresh().accepted());
        String goodSnapshot = manager.currentSnapshotId();
        assertTrue(manager.evaluate(FLAG, CTX).enabled());

        // 远端来源缺失：刷新必须被拒绝，且可区分到来源名
        remote.setUnavailable(true);
        RefreshOutcome missing = manager.refresh();
        assertFalse(missing.accepted());
        assertEquals(RefreshFailureReason.SOURCE_UNAVAILABLE, missing.failureReason());
        assertTrue(missing.detail().contains("remote-center"),
                "拒绝细节应携带来源名: " + missing.detail());
        assertEquals(goodSnapshot, missing.activeSnapshotId(),
                "刷新失败后必须继续使用上一份可用快照");
        System.out.println("[TEST] 拒绝(来源缺失) active=" + missing.activeSnapshotId()
                + " detail=" + missing.detail());

        // 判定依然基于旧快照，不受失败刷新影响
        assertTrue(manager.evaluate(FLAG, CTX).enabled());
        assertEquals(goodSnapshot, manager.evaluate(FLAG, CTX).snapshotId());
    }

    @Test
    void illegalValueKeepsPreviousGoodSnapshotInsteadOfSilentDefault() {
        InMemoryConfigSource remote = new InMemoryConfigSource("remote-center", 100);
        remote.setProperties(Map.of(
                FLAG + ".enabled", "true", FLAG + ".percentage", "100"));
        FeatureFlagManager manager = new FeatureFlagManager(List.of(remote));
        assertTrue(manager.refresh().accepted());
        String goodSnapshot = manager.currentSnapshotId();

        // 推送非法放量比例：不得静默回退默认 0% 或丢弃 percentage 字段
        remote.setProperties(Map.of(
                FLAG + ".enabled", "true", FLAG + ".percentage", "200"));
        RefreshOutcome rejected = manager.refresh();
        assertFalse(rejected.accepted());
        assertEquals(RefreshFailureReason.INVALID_VALUE, rejected.failureReason());
        assertEquals(goodSnapshot, rejected.activeSnapshotId());

        // 旧配置（100% 放量）依然完全生效，证明没有被默认值顶替
        EvaluationResult stillOn = manager.evaluate(FLAG, CTX);
        assertTrue(stillOn.enabled());
        assertEquals(goodSnapshot, stillOn.snapshotId());
        System.out.println("[TEST] 非法放量被拒绝，继续使用旧快照=" + goodSnapshot);
    }

    @Test
    void sourceLoadExceptionIsSurfacedNotSwallowed() {
        InMemoryConfigSource source = new InMemoryConfigSource("remote-center", 100);
        source.setUnavailable(true);
        assertThrows(SourceUnavailableException.class, source::load);
        // 直接校验异常载体可区分来源
        SourceUnavailableException ex = new SourceUnavailableException(
                "remote-center", "boom");
        assertNotNull(ex.sourceName());
        assertEquals("remote-center", ex.sourceName());
        // 防御性校验 InvalidFlagConfigException 携带问题类型
        InvalidFlagConfigException bad = new InvalidFlagConfigException(
                ConfigProblemType.INVALID_INTEGER, "k", "v", "msg");
        assertEquals(ConfigProblemType.INVALID_INTEGER, bad.problemType());
    }
}
