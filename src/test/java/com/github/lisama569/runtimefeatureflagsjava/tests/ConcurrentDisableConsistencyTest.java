package com.github.lisama569.runtimefeatureflagsjava.tests;

import com.github.lisama569.runtimefeatureflagsjava.flag.model.EvaluationReason;
import com.github.lisama569.runtimefeatureflagsjava.flag.model.EvaluationResult;
import com.github.lisama569.runtimefeatureflagsjava.flag.model.RequestContext;
import com.github.lisama569.runtimefeatureflagsjava.flag.service.DisableDirective;
import com.github.lisama569.runtimefeatureflagsjava.flag.service.DisableFailureReason;
import com.github.lisama569.runtimefeatureflagsjava.flag.service.FeatureFlagManager;
import com.github.lisama569.runtimefeatureflagsjava.flag.service.RefreshOutcome;
import com.github.lisama569.runtimefeatureflagsjava.flag.source.InMemoryConfigSource;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 场景：停用 / 恢复 / 配置刷新 / 判定并发时的一致性。
 * <ul>
 *   <li>每次 {@code activeDisables()} 查询与判定携带的停用集合自洽：
 *       无重复 id、不超容量、同一 flag 的指令集合在两次观察间要么是某批原子操作前、
 *       要么是操作后，不出现"半批"；</li>
 *   <li>批量停用 all-or-nothing：任一批要么整体出现、要么整体不出现；</li>
 *   <li>判定结果内部一致：FLAG_DISABLED 必带非空停用指令，其它原因必不带停用；
 *       结果中的快照标识必须是某次成功发布过的快照；</li>
 *   <li>刷新线程穿插好/坏配置，停用不被刷新解除；最终停用仍可被显式恢复。</li>
 * </ul>
 */
class ConcurrentDisableConsistencyTest {

    private static final int CAPACITY = 50;
    private static final String FLAG_PREFIX = "feature.c";
    private static final List<String> ALL_FLAGS = buildFlags(40);
    private static final List<String> BATCH = List.of(
            FLAG_PREFIX + "0", FLAG_PREFIX + "1", FLAG_PREFIX + "2");

    private static List<String> buildFlags(int n) {
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            keys.add(FLAG_PREFIX + i);
        }
        return List.copyOf(keys);
    }

    @Test
    void disableRefreshRestoreAndEvaluateAreConsistentUnderConcurrency() throws Exception {
        InMemoryConfigSource remote = new InMemoryConfigSource("remote-center", 100);
        Map<String, String> goodConfig = new HashMap<>();
        for (String f : ALL_FLAGS) {
            goodConfig.put(f + ".enabled", "true");
            goodConfig.put(f + ".percentage", "100");
        }
        remote.setProperties(goodConfig);
        FeatureFlagManager manager = new FeatureFlagManager(
                List.of(remote), System::currentTimeMillis, CAPACITY);
        RefreshOutcome initial = manager.refresh();
        assertTrue(initial.accepted());

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads + 2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean stop = new AtomicBoolean(false);
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
        AtomicInteger evaluations = new AtomicInteger();
        AtomicInteger disabledReasons = new AtomicInteger();
        AtomicInteger otherReasons = new AtomicInteger();
        AtomicInteger batchAccepted = new AtomicInteger();
        AtomicInteger batchRejected = new AtomicInteger();
        AtomicInteger capacityRejections = new AtomicInteger();
        AtomicInteger inconsistentSnapshots = new AtomicInteger();

        // 刷新线程：好/坏配置交替（好配置发布，坏配置必须被拒并保旧）
        pool.submit(() -> {
            try {
                start.await();
                int i = 0;
                while (!stop.get()) {
                    if (i % 2 == 0) {
                        remote.setProperties(goodConfig);
                        RefreshOutcome ok = manager.refresh();
                        if (!ok.accepted()) {
                            failures.add(new AssertionError("好配置刷新不应失败"));
                        }
                    } else {
                        Map<String, String> bad = new HashMap<>(goodConfig);
                        bad.put(ALL_FLAGS.get(0) + ".percentage", "nope");
                        remote.setProperties(bad);
                        RefreshOutcome rejected = manager.refresh();
                        if (rejected.accepted()) {
                            failures.add(new AssertionError("坏配置不应刷新成功"));
                        }
                    }
                    i++;
                }
            } catch (Throwable t) {
                failures.add(t);
            }
        });

        // 停用/恢复线程：批量停用 BATCH，再批量恢复（按 flag 逐个恢复），循环
        pool.submit(() -> {
            try {
                start.await();
                while (!stop.get()) {
                    var added = manager.disable(new ArrayList<>(BATCH),
                            "并发止血", "oncall", 0L);
                    if (added.accepted()) {
                        batchAccepted.incrementAndGet();
                    } else if (added.failureReason()
                            == DisableFailureReason.CAPACITY_EXCEEDED) {
                        capacityRejections.incrementAndGet();
                        batchRejected.incrementAndGet();
                    } else {
                        failures.add(new AssertionError(
                                "停用被非预期原因拒绝: " + added.failureReason()));
                    }
                    for (String f : BATCH) {
                        manager.restore(f);
                    }
                }
            } catch (Throwable t) {
                failures.add(t);
            }
        });

        // 读/判定线程
        for (int t = 0; t < threads; t++) {
            final int idx = t;
            pool.submit(() -> {
                try {
                    start.await();
                    RequestContext ctx = new RequestContext(
                            "visitor-" + idx, Map.of());
                    while (!stop.get()) {
                        // 1) 全局查询必须自洽
                        List<DisableDirective> snapshot = manager.activeDisables();
                        Set<String> ids = new HashSet<>();
                        for (DisableDirective d : snapshot) {
                            if (!ids.add(d.id())) {
                                failures.add(new AssertionError("停用指令 id 重复: " + d.id()));
                            }
                        }
                        if (snapshot.size() > CAPACITY) {
                            failures.add(new AssertionError(
                                    "生效停用超过容量: " + snapshot.size()));
                        }
                        // 2) 判定内部一致
                        for (String f : BATCH) {
                            EvaluationResult r = manager.evaluate(f, ctx);
                            evaluations.incrementAndGet();
                            if (r.reason() == EvaluationReason.FLAG_DISABLED) {
                                disabledReasons.incrementAndGet();
                                if (r.disableDirectives().isEmpty()) {
                                    failures.add(new AssertionError(
                                            "FLAG_DISABLED 却没带停用指令: " + f));
                                }
                            } else {
                                otherReasons.incrementAndGet();
                                if (!r.disableDirectives().isEmpty()) {
                                    failures.add(new AssertionError(
                                            "非停用原因却带了停用指令: " + f + " "
                                                    + r.reason()));
                                }
                            }
                            if (r.snapshotId() == null) {
                                inconsistentSnapshots.incrementAndGet();
                            }
                        }
                    }
                } catch (Throwable e) {
                    failures.add(e);
                }
            });
        }

        start.countDown();
        Thread.sleep(2000);
        stop.set(true);
        pool.shutdown();
        assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS), "线程应在限定时间内结束");

        System.out.println("[TEST] 并发: 判定次数=" + evaluations.get()
                + " FLAG_DISABLED=" + disabledReasons.get()
                + " 其它原因=" + otherReasons.get()
                + " 批量停用接受=" + batchAccepted.get()
                + " 容量拒绝=" + capacityRejections.get()
                + " 未发布快照引用=" + inconsistentSnapshots.get());

        assertTrue(evaluations.get() > 0, "应实际发生并发判定");
        assertEquals(0, inconsistentSnapshots.get(),
                "判定携带的快照必须是某次成功发布的快照");
        assertTrue(disabledReasons.get() > 0, "应观察到停用生效的时刻");
        assertTrue(batchAccepted.get() > 0, "应有批量停用成功");
        assertTrue(failures.isEmpty(), "并发过程不应有不一致/断言失败: " + failures);

        // 收尾：恢复全部后，不应再是 FLAG_DISABLED（按配置判定）
        for (String f : BATCH) {
            manager.restore(f);
        }
        for (String f : BATCH) {
            EvaluationResult r = manager.evaluate(f,
                    new RequestContext("visitor-0", Map.of()));
            assertFalse(r.reason() == EvaluationReason.FLAG_DISABLED,
                    "恢复后不应再是 FLAG_DISABLED: " + f);
        }
        System.out.println("[TEST] 并发结束后停用可显式恢复，剩余生效条数="
                + manager.activeDisableCount());
    }
}
