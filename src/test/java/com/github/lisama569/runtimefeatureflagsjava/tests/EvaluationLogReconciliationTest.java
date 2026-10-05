package com.github.lisama569.runtimefeatureflagsjava.tests;

import com.github.lisama569.runtimefeatureflagsjava.flag.model.EvaluationReason;
import com.github.lisama569.runtimefeatureflagsjava.flag.model.EvaluationResult;
import com.github.lisama569.runtimefeatureflagsjava.flag.model.RequestContext;
import com.github.lisama569.runtimefeatureflagsjava.flag.service.FeatureFlagManager;
import com.github.lisama569.runtimefeatureflagsjava.flag.source.InMemoryConfigSource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖"每条判定日志都稳定携带对账信息"：
 * - 正常放量 / 白名单命中 / 定向未命中：日志带快照标识 + 各字段出处来源（含 &lt;default&gt; 标记）；
 * - 全局总闸、局部临时停用、两层叠加：短路日志同样带字段出处，且分层归因可区分；
 * - 开关未定义 / 完全无配置：显式标记 &lt;flag-not-defined&gt; / &lt;none&gt;+&lt;no-config&gt;，
 *   不与"来源缺失/未知"混淆；
 * - 刷新失败（非法取值/来源缺失）、紧急关闭、回滚之后的判定：日志对账信息不缺失；
 * - 所有用例同时断言判定结论不变（本次调整只影响日志内容）。
 */
class EvaluationLogReconciliationTest {

    private static final String FLAG = "feature.recon";
    private static final RequestContext CTX = new RequestContext("user-1", Map.of());

    private InMemoryConfigSource local;
    private InMemoryConfigSource remote;

    /** 双来源：本地基线 + 远端覆盖，让字段出处可区分（含 &lt;default&gt; 字段）。 */
    private FeatureFlagManager newManager() {
        local = new InMemoryConfigSource("local-defaults", 10);
        remote = new InMemoryConfigSource("remote-center", 100);
        local.setProperties(Map.of(
                FLAG + ".enabled", "true",
                FLAG + ".percentage", "0",
                "feature.vip.enabled", "true",
                "feature.vip.allowlist", "vip-1",
                "feature.targeted.enabled", "true",
                "feature.targeted.percentage", "100",
                "feature.targeted.target.channel", "beta"));
        remote.setProperties(Map.of(
                FLAG + ".percentage", "100",
                FLAG + ".salt", "recon-salt"));
        FeatureFlagManager manager = new FeatureFlagManager(List.of(local, remote));
        assertTrue(manager.refresh().accepted());
        return manager;
    }

    @Test
    void rolloutAllowlistAndTargetingLogsCarrySnapshotAndFieldOrigins() {
        FeatureFlagManager manager = newManager();
        try (EvalLogCapture capture = EvalLogCapture.start()) {
            // 正常放量：结论不变，日志带快照 + 全字段出处（含 <default> 标记）
            EvaluationResult rollout = manager.evaluate(FLAG, CTX);
            assertTrue(rollout.enabled());
            assertEquals(EvaluationReason.PERCENTAGE_BUCKETED, rollout.reason());
            String rolloutLine = capture.lastEvalLineFor(FLAG);
            assertNotNull(rolloutLine, "正常放量判定必须产生日志");
            assertTrue(rolloutLine.contains("snapshot=" + rollout.snapshotId()),
                    "日志必须带本次配置快照标识: " + rolloutLine);
            assertTrue(rolloutLine.contains("enabled=local-defaults")
                            && rolloutLine.contains("percentage=remote-center")
                            && rolloutLine.contains("salt=remote-center")
                            && rolloutLine.contains("allowlist=<default>"),
                    "日志必须逐字段给出来源，缺省字段标记 <default>: " + rolloutLine);
            System.out.println("[TEST] 正常放量日志: " + rolloutLine);

            // 白名单命中：短路 ON 也要带字段出处
            EvaluationResult allow = manager.evaluate("feature.vip",
                    new RequestContext("vip-1", Map.of()));
            assertTrue(allow.enabled());
            assertEquals(EvaluationReason.ALLOWLIST_MATCH, allow.reason());
            String allowLine = capture.lastEvalLineFor("feature.vip");
            assertTrue(allowLine.contains("snapshot=" + allow.snapshotId())
                            && allowLine.contains("allowlist=local-defaults")
                            && allowLine.contains("percentage=<default>"),
                    "白名单命中日志必须带字段出处: " + allowLine);
            System.out.println("[TEST] 白名单命中日志: " + allowLine);

            // 定向未命中：短路 OFF 也要带字段出处（含定向条件来源）
            EvaluationResult targeted = manager.evaluate("feature.targeted", CTX);
            assertFalse(targeted.enabled());
            assertEquals(EvaluationReason.TARGETING_NOT_MATCHED, targeted.reason());
            String targetedLine = capture.lastEvalLineFor("feature.targeted");
            assertTrue(targetedLine.contains("snapshot=" + targeted.snapshotId())
                            && targetedLine.contains("target.channel=local-defaults"),
                    "定向未命中日志必须带字段出处: " + targetedLine);
            System.out.println("[TEST] 定向未命中日志: " + targetedLine);
        }
    }

    @Test
    void killDisableAndOverlayLogsCarryOriginsAndLayerAttribution() {
        FeatureFlagManager manager = newManager();
        try (EvalLogCapture capture = EvalLogCapture.start()) {
            // 仅局部停用：结论 OFF/局部层，日志带命中停用记录 + 字段出处
            assertTrue(manager.disable(FLAG, "资损排查", "sre-lisa").accepted());
            EvaluationResult disabled = manager.evaluate(FLAG, CTX);
            assertFalse(disabled.enabled());
            assertEquals(EvaluationReason.FLAG_TEMPORARILY_DISABLED, disabled.reason());
            String disableLine = capture.lastEvalLineFor(FLAG);
            assertTrue(disableLine.contains("disable=" + disabled.disableId()),
                    "日志必须带命中的停用记录: " + disableLine);
            assertTrue(disableLine.contains("enabled=local-defaults")
                            && disableLine.contains("percentage=remote-center"),
                    "局部停用短路日志也必须带字段出处: " + disableLine);
            assertFalse(disableLine.contains("origins=-"),
                    "字段出处不得为空: " + disableLine);
            System.out.println("[TEST] 局部停用日志: " + disableLine);

            // 叠加全局总闸：结论 OFF/全局层，日志同时给字段出处与两层归因
            assertTrue(manager.emergencyShutdown("全站故障").accepted());
            EvaluationResult both = manager.evaluate(FLAG, CTX);
            assertFalse(both.enabled());
            assertEquals(EvaluationReason.KILL_SWITCH_ACTIVE, both.reason());
            assertEquals(disabled.disableId(), both.disableId());
            String overlayLine = capture.lastEvalLineFor(FLAG);
            assertTrue(overlayLine.contains("snapshot=" + both.snapshotId())
                            && overlayLine.contains("disable=" + both.disableId())
                            && overlayLine.contains("enabled=local-defaults"),
                    "总闸+局部停用叠加日志必须带快照/停用记录/字段出处: " + overlayLine);
            assertTrue(overlayLine.contains("全局层") && overlayLine.contains("局部层"),
                    "叠加时日志必须能区分两层归因: " + overlayLine);
            System.out.println("[TEST] 总闸+局部停用叠加日志: " + overlayLine);

            // 回滚解除总闸：局部停用仍在，日志对账信息不变
            assertTrue(manager.rollback().accepted());
            EvaluationResult localAgain = manager.evaluate(FLAG, CTX);
            assertEquals(EvaluationReason.FLAG_TEMPORARILY_DISABLED, localAgain.reason());
            assertTrue(capture.lastEvalLineFor(FLAG).contains("enabled=local-defaults"));

            // 恢复后回到正常判定
            assertTrue(manager.resume(FLAG).accepted());
            assertTrue(manager.evaluate(FLAG, CTX).enabled());
            System.out.println("[TEST] 回滚/恢复后日志对账信息保持完整");
        }
    }

    @Test
    void flagNotFoundAndNoConfigLogsAreExplicitlyMarked() {
        FeatureFlagManager manager = newManager();
        try (EvalLogCapture capture = EvalLogCapture.start()) {
            // 开关未定义：快照标识在，字段出处显式标记 <flag-not-defined>
            EvaluationResult notFound = manager.evaluate("feature.missing", CTX);
            assertFalse(notFound.enabled());
            assertEquals(EvaluationReason.FLAG_NOT_FOUND, notFound.reason());
            String notFoundLine = capture.lastEvalLineFor("feature.missing");
            assertTrue(notFoundLine.contains("snapshot=" + notFound.snapshotId()),
                    "开关未定义时日志仍须带快照标识: " + notFoundLine);
            assertTrue(notFoundLine.contains("origins=<flag-not-defined>"),
                    "开关未定义必须显式标记，而非来源缺失: " + notFoundLine);
            System.out.println("[TEST] 开关未定义日志: " + notFoundLine);
        }

        // 完全无配置（从未加载成功）：snapshot=<none> + origins=<no-config>，
        // 明确是"没有配置"，不是来源缺失或来源未知
        FeatureFlagManager empty = new FeatureFlagManager(List.of(
                new InMemoryConfigSource("remote-center", 100)));
        try (EvalLogCapture capture = EvalLogCapture.start()) {
            EvaluationResult noConfig = empty.evaluate(FLAG, CTX);
            assertFalse(noConfig.enabled());
            assertEquals(EvaluationReason.NO_CONFIG_LOADED, noConfig.reason());
            assertNull(noConfig.snapshotId());
            String noConfigLine = capture.lastEvalLineFor(FLAG);
            assertTrue(noConfigLine.contains("snapshot=<none>")
                            && noConfigLine.contains("origins=<no-config>"),
                    "无配置必须显式标记 <none>/<no-config>: " + noConfigLine);
            System.out.println("[TEST] 无配置日志: " + noConfigLine);

            // 无配置 + 命中停用记录：停用信息同样要能对账
            assertTrue(empty.disable(FLAG, "无配置期停用", "sre-lisa").accepted());
            EvaluationResult noConfigDisabled = empty.evaluate(FLAG, CTX);
            assertEquals(EvaluationReason.NO_CONFIG_LOADED, noConfigDisabled.reason());
            assertNotNull(noConfigDisabled.disableId());
            String line = capture.lastEvalLineFor(FLAG);
            assertTrue(line.contains("snapshot=<none>")
                            && line.contains("disable=" + noConfigDisabled.disableId())
                            && line.contains("origins=<no-config>"),
                    "无配置且命中停用时，快照/停用/出处都要显式: " + line);
            System.out.println("[TEST] 无配置+停用日志: " + line);
        }
    }

    @Test
    void logsAfterRefreshFailureSourceOutageAndRollbackStayComplete() {
        FeatureFlagManager manager = newManager();
        String goodSnapshot = manager.currentSnapshotId();
        try (EvalLogCapture capture = EvalLogCapture.start()) {
            // 非法取值刷新失败：保旧，之后判定日志仍带旧快照与字段出处
            remote.setProperties(Map.of(FLAG + ".enabled", "not-a-bool"));
            assertFalse(manager.refresh().accepted());
            EvaluationResult afterBadPush = manager.evaluate(FLAG, CTX);
            assertTrue(afterBadPush.enabled(), "失败保旧，判定结论不变");
            String badPushLine = capture.lastEvalLineFor(FLAG);
            assertTrue(badPushLine.contains("snapshot=" + goodSnapshot)
                            && badPushLine.contains("enabled=local-defaults"),
                    "刷新失败后日志必须带旧快照与字段出处: " + badPushLine);
            System.out.println("[TEST] 非法取值刷新失败后日志: " + badPushLine);

            // 来源缺失刷新失败：同样保旧，日志完整
            remote.setUnavailable(true);
            assertFalse(manager.refresh().accepted());
            remote.setUnavailable(false);
            EvaluationResult afterOutage = manager.evaluate(FLAG, CTX);
            assertTrue(afterOutage.enabled());
            String outageLine = capture.lastEvalLineFor(FLAG);
            assertTrue(outageLine.contains("snapshot=" + goodSnapshot)
                            && outageLine.contains("percentage=remote-center"),
                    "来源缺失失败后日志必须完整: " + outageLine);
            System.out.println("[TEST] 来源缺失失败后日志: " + outageLine);

            // 紧急关闭后：KILL 快照下日志仍带字段出处
            assertTrue(manager.emergencyShutdown("incident").accepted());
            EvaluationResult killed = manager.evaluate(FLAG, CTX);
            assertFalse(killed.enabled());
            assertEquals(EvaluationReason.KILL_SWITCH_ACTIVE, killed.reason());
            String killLine = capture.lastEvalLineFor(FLAG);
            assertTrue(killLine.contains("snapshot=" + killed.snapshotId())
                            && killLine.contains("enabled=local-defaults"),
                    "紧急关闭后日志必须带 KILL 快照与字段出处: " + killLine);
            System.out.println("[TEST] 紧急关闭后日志: " + killLine);

            // 回滚后：回到关闭前快照，日志对账信息完整
            assertTrue(manager.rollback().accepted());
            EvaluationResult rolledBack = manager.evaluate(FLAG, CTX);
            assertTrue(rolledBack.enabled());
            String rollbackLine = capture.lastEvalLineFor(FLAG);
            assertTrue(rollbackLine.contains("snapshot=" + goodSnapshot)
                            && rollbackLine.contains("salt=remote-center"),
                    "回滚后日志必须带恢复的快照与字段出处: " + rollbackLine);
            System.out.println("[TEST] 回滚后日志: " + rollbackLine);
        }
    }

    /**
     * 判定/停用/恢复/刷新并发进行时，每条 [FLAG-EVAL] 日志都必须完整一致：
     * 快照标识、停用记录、字段出处三者永不缺失，且与结论自洽。
     */
    @Test
    void concurrentEvaluationsProduceCompleteAndConsistentLogs() throws Exception {
        FeatureFlagManager manager = newManager();
        try (EvalLogCapture capture = EvalLogCapture.start()) {
            int readerThreads = 4;
            ExecutorService pool = Executors.newFixedThreadPool(readerThreads + 2);
            CountDownLatch start = new CountDownLatch(1);
            AtomicBoolean stop = new AtomicBoolean(false);
            AtomicInteger reads = new AtomicInteger();
            AtomicInteger errorReasons = new AtomicInteger();
            ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();

            // 刷新线程：交替好配置 / 非法取值 / 来源缺失
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 100 && !stop.get(); i++) {
                        if (i % 3 == 1) {
                            remote.setProperties(Map.of(FLAG + ".enabled", "not-a-bool"));
                            manager.refresh();
                        } else if (i % 3 == 2) {
                            remote.setUnavailable(true);
                            manager.refresh();
                            remote.setUnavailable(false);
                        } else {
                            remote.setProperties(Map.of(
                                    FLAG + ".percentage", "100",
                                    FLAG + ".salt", "recon-salt"));
                            manager.refresh();
                        }
                    }
                } catch (Throwable t) {
                    failures.add(t);
                }
            });

            // 停用/恢复线程：反复停用再恢复
            pool.submit(() -> {
                try {
                    start.await();
                    while (!stop.get()) {
                        manager.disable(FLAG, "并发熔断", "sre-lisa");
                        manager.resume(FLAG);
                    }
                } catch (Throwable t) {
                    failures.add(t);
                }
            });

            // 读线程：持续判定两个开关
            for (int r = 0; r < readerThreads; r++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        while (!stop.get()) {
                            EvaluationResult a = manager.evaluate(FLAG, CTX);
                            EvaluationResult b = manager.evaluate("feature.vip",
                                    new RequestContext("vip-1", Map.of()));
                            reads.addAndGet(2);
                            if (a.reason() == EvaluationReason.EVALUATION_ERROR
                                    || b.reason() == EvaluationReason.EVALUATION_ERROR) {
                                errorReasons.incrementAndGet();
                            }
                        }
                    } catch (Throwable t) {
                        failures.add(t);
                    }
                });
            }

            start.countDown();
            Thread.sleep(1500);
            stop.set(true);
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "线程应在限定时间内结束");
            assertTrue(failures.isEmpty(), "并发过程不应有异常: " + failures);
            assertEquals(0, errorReasons.get(), "并发下不得出现 EVALUATION_ERROR");
            assertTrue(reads.get() > 0, "应实际发生并发判定");

            // 完整性：每条日志必须能解析出 快照/停用记录/字段出处 三段，且无一缺失
            Pattern line = Pattern.compile(
                    "^\\[FLAG-EVAL] snapshot=(?<snapshot><none>|v\\S+)"
                            + " flag=\\S+ visitor=\\S+ decision=(ON|OFF)"
                            + " reason=(?<reason>[A-Z_]+) disable=(?<disable>\\S+)"
                            + " origins=(?<origins><no-config>|<flag-not-defined>|\\{.+})"
                            + " \\| .+$");
            List<String> evalLines = capture.evalLines();
            assertFalse(evalLines.isEmpty(), "并发下必须产生判定日志");
            Map<String, Integer> reasonCounts = new TreeMap<>();
            int disabledHits = 0;
            for (String l : evalLines) {
                Matcher m = line.matcher(l);
                assertTrue(m.matches(), "日志行必须完整携带对账信息: " + l);
                String reason = m.group("reason");
                reasonCounts.merge(reason, 1, Integer::sum);
                String origins = m.group("origins");
                if (origins.startsWith("{")) {
                    assertTrue(origins.contains("enabled="),
                            "已定义开关的字段出处必须含 enabled 来源: " + l);
                }
                if ("FLAG_TEMPORARILY_DISABLED".equals(reason)) {
                    assertFalse("-".equals(m.group("disable")),
                            "命中局部停用的日志必须带停用记录: " + l);
                    assertTrue(l.contains("baseSnapshot="),
                            "命中局部停用的日志必须带停用详情: " + l);
                    disabledHits++;
                }
                if ("KILL_SWITCH_ACTIVE".equals(reason)
                        && !"-".equals(m.group("disable"))) {
                    assertTrue(l.contains("全局层") && l.contains("局部层"),
                            "总闸+局部停用叠加的日志必须分层归因: " + l);
                }
                assertFalse(l.contains("origins=-"), "字段出处不得为空: " + l);
            }
            System.out.println("[TEST] 并发判定日志完整性: 总条数=" + evalLines.size()
                    + " 判定次数=" + reads.get()
                    + " 命中停用条数=" + disabledHits
                    + " 原因分布=" + reasonCounts);
            assertTrue(disabledHits > 0, "并发下应实际覆盖到停用命中的日志");
            assertTrue(reasonCounts.containsKey("PERCENTAGE_BUCKETED")
                    || reasonCounts.containsKey("ALLOWLIST_MATCH"),
                    "并发下应覆盖正常放量路径: " + reasonCounts);
        }
    }
}
