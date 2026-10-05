package com.github.lisama569.runtimefeatureflagsjava.tests;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.github.lisama569.runtimefeatureflagsjava.flag.eval.FlagEvaluator;
import com.github.lisama569.runtimefeatureflagsjava.flag.model.EvaluationReason;
import com.github.lisama569.runtimefeatureflagsjava.flag.model.EvaluationResult;
import com.github.lisama569.runtimefeatureflagsjava.flag.model.RequestContext;
import com.github.lisama569.runtimefeatureflagsjava.flag.service.FeatureFlagManager;
import com.github.lisama569.runtimefeatureflagsjava.flag.source.InMemoryConfigSource;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖判定对账日志：每一次判定（含所有短路分支）的 [FLAG-EVAL] 日志都稳定携带
 * 三类对账信息——配置快照标识、命中的停用记录、各字段出处来源（缺省值显式标记），
 * 且日志内容与返回的判定结论逐项一致。
 */
class EvaluationAuditLogTest {

    private static final String FLAG = "feature.demo";
    private static final RequestContext CTX =
            new RequestContext("user-1", Map.of("channel", "beta"));

    /** 捕获 FlagEvaluator 输出的 [FLAG-EVAL] 日志行（logback 内存 appender）。 */
    private static final class EvalLogCapture implements AutoCloseable {
        private final ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(FlagEvaluator.class);
        private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

        EvalLogCapture() {
            appender.start();
            logger.addAppender(appender);
        }

        List<String> evalLines() {
            List<String> lines = new ArrayList<>();
            for (ILoggingEvent e : appender.list) {
                String msg = e.getFormattedMessage();
                if (msg.contains("[FLAG-EVAL]")) {
                    lines.add(msg);
                }
            }
            return lines;
        }

        String lastLine() {
            List<String> lines = evalLines();
            assertFalse(lines.isEmpty(), "每次判定都应产生 [FLAG-EVAL] 日志");
            return lines.get(lines.size() - 1);
        }

        @Override
        public void close() {
            logger.detachAppender(appender);
        }
    }

    /** 日志行必须与返回的判定结论逐项对得上（快照/结论/原因/停用记录）。 */
    private static void assertLineMatchesResult(String line, EvaluationResult r) {
        assertTrue(line.contains("snapshot="
                        + (r.snapshotId() == null ? "<none>" : r.snapshotId()) + " "),
                "日志快照标识与结论不一致: " + line);
        assertTrue(line.contains("decision=" + (r.enabled() ? "ON" : "OFF") + " "),
                "日志结论与结果不一致: " + line);
        assertTrue(line.contains("reason=" + r.reason() + " "),
                "日志原因码与结果不一致: " + line);
        assertTrue(line.contains("disable="
                        + (r.disableId() == null ? "-" : r.disableId()) + " "),
                "日志停用记录与结果不一致: " + line);
    }

    /**
     * 双来源配置：enabled/allowlist 来自 local-defaults，percentage/target 来自
     * remote-center（覆盖本地），salt 取缺省——让字段出处可区分到具体来源。
     */
    private FeatureFlagManager twoSourceManager() {
        InMemoryConfigSource local = new InMemoryConfigSource("local-defaults", 10);
        local.setProperties(Map.of(
                FLAG + ".enabled", "true",
                FLAG + ".percentage", "0",
                FLAG + ".allowlist", "vip-1"));
        InMemoryConfigSource remote = new InMemoryConfigSource("remote-center", 100);
        remote.setProperties(Map.of(
                FLAG + ".percentage", "100",
                FLAG + ".target.channel", "beta"));
        FeatureFlagManager manager = new FeatureFlagManager(List.of(local, remote));
        assertTrue(manager.refresh().accepted());
        return manager;
    }

    private static void assertFullOrigins(String line) {
        assertTrue(line.contains("enabled=local-defaults"), "enabled 出处: " + line);
        assertTrue(line.contains("percentage=remote-center"), "percentage 出处: " + line);
        assertTrue(line.contains("allowlist=local-defaults"), "allowlist 出处: " + line);
        assertTrue(line.contains("salt=<default>"), "salt 缺省标记: " + line);
        assertTrue(line.contains("target.channel=remote-center"), "target 出处: " + line);
    }

    @Test
    void normalRolloutLogCarriesSnapshotDisableAndFieldOrigins() {
        FeatureFlagManager manager = twoSourceManager();
        try (EvalLogCapture logs = new EvalLogCapture()) {
            EvaluationResult r = manager.evaluate(FLAG, CTX);
            assertTrue(r.enabled());
            assertEquals(EvaluationReason.PERCENTAGE_BUCKETED, r.reason());

            String line = logs.lastLine();
            assertLineMatchesResult(line, r);
            assertTrue(line.contains("disable=- "), "未命中停用应显式为 -: " + line);
            assertFullOrigins(line);
            System.out.println("[TEST] 正常放量判定日志对账: snapshot+disable+origins 齐全 -> " + line);
        }
    }

    @Test
    void allowlistAndTargetingBranchesAlsoCarryFieldOrigins() {
        FeatureFlagManager manager = twoSourceManager();
        try (EvalLogCapture logs = new EvalLogCapture()) {
            // 白名单命中（ON）：字段来源照常输出
            EvaluationResult allow = manager.evaluate(FLAG,
                    new RequestContext("vip-1", Map.of()));
            assertTrue(allow.enabled());
            assertEquals(EvaluationReason.ALLOWLIST_MATCH, allow.reason());
            String allowLine = logs.lastLine();
            assertLineMatchesResult(allowLine, allow);
            assertFullOrigins(allowLine);
            System.out.println("[TEST] 白名单命中分支日志对账: " + allowLine);

            // 定向未命中（OFF）：字段来源照常输出
            EvaluationResult miss = manager.evaluate(FLAG,
                    new RequestContext("user-9", Map.of("channel", "gamma")));
            assertFalse(miss.enabled());
            assertEquals(EvaluationReason.TARGETING_NOT_MATCHED, miss.reason());
            String missLine = logs.lastLine();
            assertLineMatchesResult(missLine, miss);
            assertFullOrigins(missLine);
            System.out.println("[TEST] 定向未命中分支日志对账: " + missLine);
        }
    }

    @Test
    void killSwitchShortCircuitStillCarriesSnapshotAndFieldOrigins() {
        FeatureFlagManager manager = twoSourceManager();
        manager.emergencyShutdown("全站故障演练");
        try (EvalLogCapture logs = new EvalLogCapture()) {
            EvaluationResult r = manager.evaluate(FLAG, CTX);
            assertFalse(r.enabled());
            assertEquals(EvaluationReason.KILL_SWITCH_ACTIVE, r.reason());

            String line = logs.lastLine();
            assertLineMatchesResult(line, r);
            assertTrue(line.contains("disable=- "), "未命中局部停用应显式为 -: " + line);
            assertFullOrigins(line);
            System.out.println("[TEST] 全局总闸短路日志对账(字段来源不丢): " + line);
        }
    }

    @Test
    void localDisableShortCircuitStillCarriesFieldOriginsAndDisableId() {
        FeatureFlagManager manager = twoSourceManager();
        assertTrue(manager.disable(FLAG, "资损排查", "sre-lisa").accepted());
        try (EvalLogCapture logs = new EvalLogCapture()) {
            EvaluationResult r = manager.evaluate(FLAG, CTX);
            assertFalse(r.enabled());
            assertEquals(EvaluationReason.FLAG_TEMPORARILY_DISABLED, r.reason());
            assertNotNull(r.disableId());

            String line = logs.lastLine();
            assertLineMatchesResult(line, r);
            assertTrue(line.contains("disable=" + r.disableId() + " "),
                    "命中停用记录必须出现在日志里: " + line);
            assertFullOrigins(line);
            System.out.println("[TEST] 局部停用短路日志对账(停用记录+字段来源): " + line);
        }
    }

    @Test
    void killSwitchPlusLocalDisableCarriesOriginsAndLayerAttribution() {
        FeatureFlagManager manager = twoSourceManager();
        assertTrue(manager.disable(FLAG, "局部熔断", "sre-lisa").accepted());
        manager.emergencyShutdown("全站故障");
        try (EvalLogCapture logs = new EvalLogCapture()) {
            EvaluationResult r = manager.evaluate(FLAG, CTX);
            assertFalse(r.enabled());
            assertEquals(EvaluationReason.KILL_SWITCH_ACTIVE, r.reason(),
                    "全局层优先，归因为全局总闸");
            assertNotNull(r.disableId(), "局部停用命中记录应一并带出");

            String line = logs.lastLine();
            assertLineMatchesResult(line, r);
            assertFullOrigins(line);
            assertTrue(line.contains("全局层") && line.contains("局部层"),
                    "叠加时日志应能区分两层归因: " + line);
            System.out.println("[TEST] 总闸+局部停用叠加日志对账(字段来源+分层归因): " + line);
        }
    }

    @Test
    void flagNotDefinedMarksOriginsExplicitly() {
        FeatureFlagManager manager = twoSourceManager();
        try (EvalLogCapture logs = new EvalLogCapture()) {
            EvaluationResult r = manager.evaluate("feature.missing", CTX);
            assertFalse(r.enabled());
            assertEquals(EvaluationReason.FLAG_NOT_FOUND, r.reason());

            String line = logs.lastLine();
            assertLineMatchesResult(line, r);
            assertTrue(line.contains("origins=<flag-not-defined>"),
                    "开关未定义应显式标记，而非来源缺失: " + line);
            System.out.println("[TEST] 开关未定义日志对账(快照在/字段来源显式标记): " + line);
        }
    }

    @Test
    void noConfigLoadedMarksSnapshotAndOriginsExplicitly() {
        // 从未成功加载任何配置：判定走 NO_CONFIG_LOADED 兜底
        FeatureFlagManager manager = new FeatureFlagManager(List.of(
                new InMemoryConfigSource("remote-center", 100)));
        // 无配置时也可以先下停用指令，验证该分支同样带出停用记录
        assertTrue(manager.disable(FLAG, "预防性熔断", "sre-lisa").accepted());
        try (EvalLogCapture logs = new EvalLogCapture()) {
            EvaluationResult r = manager.evaluate(FLAG, CTX);
            assertFalse(r.enabled());
            assertEquals(EvaluationReason.NO_CONFIG_LOADED, r.reason());
            assertNotNull(r.disableId(), "无配置时命中的停用记录也应带出");

            String line = logs.lastLine();
            assertLineMatchesResult(line, r);
            assertTrue(line.contains("snapshot=<none>"),
                    "没有配置应显式输出 <none>，而非空值或来源未知: " + line);
            assertTrue(line.contains("origins=<no-config>"),
                    "没有配置应显式标记 <no-config>: " + line);
            assertFalse(line.contains("origins=-"), "不得出现来源缺失/未知标记: " + line);
            System.out.println("[TEST] 无配置兜底日志对账(显式<none>/<no-config>+停用记录): " + line);
        }
    }

    @Test
    void refreshFailureEmergencyShutdownAndRollbackKeepAuditInfo() {
        InMemoryConfigSource local = new InMemoryConfigSource("local-defaults", 10);
        local.setProperties(Map.of(FLAG + ".enabled", "true",
                FLAG + ".percentage", "0", FLAG + ".allowlist", "vip-1"));
        InMemoryConfigSource remote = new InMemoryConfigSource("remote-center", 100);
        remote.setProperties(Map.of(FLAG + ".percentage", "100",
                FLAG + ".target.channel", "beta"));
        FeatureFlagManager manager = new FeatureFlagManager(List.of(local, remote));
        assertTrue(manager.refresh().accepted());
        String goodSnapshot = manager.currentSnapshotId();

        try (EvalLogCapture logs = new EvalLogCapture()) {
            // 来源缺失 → 刷新失败保旧：判定日志仍带旧快照与完整字段来源
            remote.setUnavailable(true);
            assertFalse(manager.refresh().accepted());
            EvaluationResult afterSourceLoss = manager.evaluate(FLAG, CTX);
            String lineAfterSourceLoss = logs.lastLine();
            assertLineMatchesResult(lineAfterSourceLoss, afterSourceLoss);
            assertTrue(lineAfterSourceLoss.contains("snapshot=" + goodSnapshot + " "),
                    "来源缺失刷新失败后应继续对账到旧快照: " + lineAfterSourceLoss);
            assertFullOrigins(lineAfterSourceLoss);
            System.out.println("[TEST] 来源缺失刷新失败后的判定日志: " + lineAfterSourceLoss);

            // 非法取值 → 刷新失败保旧：同样不丢对账信息
            remote.setUnavailable(false);
            remote.setProperties(Map.of(FLAG + ".percentage", "not-a-number"));
            assertFalse(manager.refresh().accepted());
            EvaluationResult afterInvalid = manager.evaluate(FLAG, CTX);
            String lineAfterInvalid = logs.lastLine();
            assertLineMatchesResult(lineAfterInvalid, afterInvalid);
            assertTrue(lineAfterInvalid.contains("snapshot=" + goodSnapshot + " "),
                    "非法取值刷新失败后应继续对账到旧快照: " + lineAfterInvalid);
            assertFullOrigins(lineAfterInvalid);
            System.out.println("[TEST] 非法取值刷新失败后的判定日志: " + lineAfterInvalid);

            // 紧急关闭：KILL 快照下字段来源仍然齐全
            manager.emergencyShutdown("演练");
            EvaluationResult killed = manager.evaluate(FLAG, CTX);
            String killedLine = logs.lastLine();
            assertLineMatchesResult(killedLine, killed);
            assertTrue(killedLine.contains("snapshot=v2-KILL "), killedLine);
            assertFullOrigins(killedLine);
            System.out.println("[TEST] 紧急关闭后的判定日志: " + killedLine);

            // 回滚：回到关闭前快照，日志快照标识与字段来源同步恢复
            assertTrue(manager.rollback().accepted());
            EvaluationResult rolledBack = manager.evaluate(FLAG, CTX);
            assertTrue(rolledBack.enabled(), "回滚后判定结论应恢复（语义不变）");
            String rollbackLine = logs.lastLine();
            assertLineMatchesResult(rollbackLine, rolledBack);
            assertTrue(rollbackLine.contains("snapshot=" + goodSnapshot + " "),
                    "回滚后日志应对账到回滚目标快照: " + rollbackLine);
            assertFullOrigins(rollbackLine);
            System.out.println("[TEST] 回滚后的判定日志: " + rollbackLine);
        }
    }

    @Test
    void concurrentEvaluateDisableRefreshProduceCompleteConsistentLogLines() throws Exception {
        InMemoryConfigSource remote = new InMemoryConfigSource("remote-center", 100);
        remote.setProperties(Map.of(FLAG + ".enabled", "true",
                FLAG + ".percentage", "100"));
        FeatureFlagManager manager = new FeatureFlagManager(List.of(remote));
        assertTrue(manager.refresh().accepted());

        try (EvalLogCapture logs = new EvalLogCapture()) {
            int readerThreads = 4;
            java.util.concurrent.ExecutorService pool =
                    java.util.concurrent.Executors.newFixedThreadPool(readerThreads + 2);
            java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.atomic.AtomicBoolean stop =
                    new java.util.concurrent.atomic.AtomicBoolean(false);
            java.util.concurrent.atomic.AtomicInteger reads =
                    new java.util.concurrent.atomic.AtomicInteger();
            java.util.concurrent.ConcurrentLinkedQueue<Throwable> failures =
                    new java.util.concurrent.ConcurrentLinkedQueue<>();

            // 刷新线程：持续推送好配置
            pool.submit(() -> {
                try {
                    start.await();
                    int i = 0;
                    while (!stop.get()) {
                        remote.setProperties(Map.of(FLAG + ".enabled", "true",
                                FLAG + ".percentage", String.valueOf((i++ % 2) * 100)));
                        manager.refresh();
                    }
                } catch (Throwable t) {
                    failures.add(t);
                }
            });
            // 停用/恢复线程
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
            // 读线程：持续判定（含一个未定义开关，覆盖 <flag-not-defined> 分支）
            for (int r = 0; r < readerThreads; r++) {
                int id = r;
                pool.submit(() -> {
                    try {
                        start.await();
                        RequestContext ctx = new RequestContext("visitor-" + id, Map.of());
                        while (!stop.get()) {
                            manager.evaluate(id % 2 == 0 ? FLAG : "feature.missing", ctx);
                            reads.incrementAndGet();
                        }
                    } catch (Throwable t) {
                        failures.add(t);
                    }
                });
            }

            start.countDown();
            Thread.sleep(600);
            stop.set(true);
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(failures.isEmpty(), "并发过程不应有异常: " + failures);
            assertTrue(reads.get() > 0, "应实际发生并发判定");

            // 逐行校验：每条日志都完整（快照/停用/字段来源）且内部一致
            List<String> lines = logs.evalLines();
            assertTrue(lines.size() >= reads.get(), "每次判定都必须有日志");
            int disableHitLines = 0;
            int notDefinedLines = 0;
            for (String line : lines) {
                assertTrue(line.contains("snapshot=v"),
                        "并发日志必须带有效快照标识: " + line);
                assertFalse(line.contains("snapshot=<none>"),
                        "已加载配置后不得出现无快照日志: " + line);
                assertTrue(line.contains("disable="), "缺停用记录字段: " + line);
                boolean hasOrigins = line.contains("origins={")
                        || line.contains("origins=<flag-not-defined>");
                assertTrue(hasOrigins, "字段来源不得缺失/未知: " + line);
                assertFalse(line.contains("origins=-") || line.contains("origins=null"),
                        "字段来源不得退化为缺失标记: " + line);
                if (line.contains("reason=FLAG_TEMPORARILY_DISABLED ")) {
                    assertTrue(line.contains("disable=d-"),
                            "命中停用的日志必须带停用记录: " + line);
                    assertTrue(line.contains("decision=OFF "), line);
                    disableHitLines++;
                }
                if (line.contains("reason=KILL_SWITCH_ACTIVE ")) {
                    assertTrue(line.contains("decision=OFF "), line);
                }
                if (line.contains("decision=ON ")) {
                    assertTrue(line.contains("reason=ALLOWLIST_MATCH ")
                                    || line.contains("reason=PERCENTAGE_BUCKETED "),
                            "ON 结论的原因码必须可对账: " + line);
                }
                if (line.contains("origins=<flag-not-defined>")) {
                    assertTrue(line.contains("reason=FLAG_NOT_FOUND "), line);
                    notDefinedLines++;
                }
            }
            System.out.println("[TEST] 并发日志完整性: 判定=" + reads.get()
                    + " 日志行=" + lines.size()
                    + " 停用命中行=" + disableHitLines
                    + " 未定义开关行=" + notDefinedLines
                    + " —— 每行快照/停用记录/字段来源齐全且与结论一致");
            assertTrue(disableHitLines > 0, "并发中应观察到停用命中日志");
            assertTrue(notDefinedLines > 0, "并发中应覆盖未定义开关分支");
        }
    }
}
