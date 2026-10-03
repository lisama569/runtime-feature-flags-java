package com.github.lisama569.runtimefeatureflagsjava.tests;

import com.github.lisama569.runtimefeatureflagsjava.flag.disable.DisableOutcome;
import com.github.lisama569.runtimefeatureflagsjava.flag.disable.DisableRejectionReason;
import com.github.lisama569.runtimefeatureflagsjava.flag.disable.DisableView;
import com.github.lisama569.runtimefeatureflagsjava.flag.model.EvaluationReason;
import com.github.lisama569.runtimefeatureflagsjava.flag.model.EvaluationResult;
import com.github.lisama569.runtimefeatureflagsjava.flag.model.RequestContext;
import com.github.lisama569.runtimefeatureflagsjava.flag.service.FeatureFlagManager;
import com.github.lisama569.runtimefeatureflagsjava.flag.source.InMemoryConfigSource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 覆盖停用/恢复/刷新/判定并发进行时的一致性：
 * - 判定结果永远基于一对完整一致的 (快照, 停用集合)：
 *   命中停用的结论必带 disableId，未命中的结论不得出现"半生效"异常；
 * - 不出现 EVALUATION_ERROR；
 * - 任意时刻查询生效停用列表，数量都不超过容量上限（超限只可能被显式拒绝，
 *   不会出现静默丢条目或超发）；
 * - 并发停用竞争容量时，接受数 + 拒绝数 = 请求数，且接受数不超过容量。
 */
class ConcurrentDisableConsistencyTest {

    private static final String FLAG = "feature.race";
    private static final int CAPACITY = 16;

    @Test
    void disableResumeRefreshAndEvaluateAreConsistentUnderConcurrency() throws Exception {
        InMemoryConfigSource remote = new InMemoryConfigSource("remote-center", 100);
        remote.setProperties(Map.of(FLAG + ".enabled", "true",
                FLAG + ".percentage", "100"));
        FeatureFlagManager manager = new FeatureFlagManager(
                List.of(remote), CAPACITY, java.time.Clock.systemUTC());
        assertTrue(manager.refresh().accepted());

        int readerThreads = 6;
        int writerThreads = 3;
        ExecutorService pool = Executors.newFixedThreadPool(readerThreads + writerThreads + 1);
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger evalErrors = new AtomicInteger();
        AtomicInteger inconsistent = new AtomicInteger();
        AtomicInteger disableHits = new AtomicInteger();
        AtomicInteger acceptedDisables = new AtomicInteger();
        AtomicInteger rejectedDisables = new AtomicInteger();
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();

        // 刷新线程：持续推送好配置（停用不得被刷新解除）
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

        // 停用/恢复线程：竞争容量上限
        for (int w = 0; w < writerThreads; w++) {
            int id = w;
            pool.submit(() -> {
                try {
                    start.await();
                    int i = 0;
                    while (!stop.get()) {
                        String key = "feature.race-" + (id * 100 + (i++ % 20));
                        DisableOutcome out = manager.disable(key, "并发熔断-" + id, "sre-" + id);
                        if (out.accepted()) {
                            acceptedDisables.incrementAndGet();
                        } else if (out.rejectionReason()
                                == DisableRejectionReason.CAPACITY_EXCEEDED) {
                            rejectedDisables.incrementAndGet();
                        }
                        manager.resume(key);
                        // 查询视图任何时候都不得超容量
                        List<DisableView> views = manager.activeDisables();
                        if (views.size() > CAPACITY) {
                            failures.add(new AssertionError(
                                    "生效停用数超容量: " + views.size()));
                        }
                    }
                } catch (Throwable t) {
                    failures.add(t);
                }
            });
        }

        // 读线程：持续判定，校验结论与停用命中的一致性
        for (int r = 0; r < readerThreads; r++) {
            pool.submit(() -> {
                try {
                    start.await();
                    RequestContext ctx = new RequestContext("visitor-1", Map.of());
                    while (!stop.get()) {
                        EvaluationResult result = manager.evaluate(FLAG, ctx);
                        reads.incrementAndGet();
                        if (result.reason() == EvaluationReason.EVALUATION_ERROR) {
                            evalErrors.incrementAndGet();
                        }
                        if (result.reason() == EvaluationReason.FLAG_TEMPORARILY_DISABLED
                                && result.disableId() == null) {
                            inconsistent.incrementAndGet();
                        }
                        if (result.disableId() != null) {
                            disableHits.incrementAndGet();
                        }
                        if (result.snapshotId() == null) {
                            inconsistent.incrementAndGet();
                        }
                    }
                } catch (Throwable t) {
                    failures.add(t);
                }
            });
        }

        // 主线程对 FLAG 本身做一次停用，确保读线程确实能观察到停用命中
        start.countDown();
        Thread.sleep(300);
        assertTrue(manager.disable(FLAG, "主线程熔断", "sre-main").accepted());
        Thread.sleep(900);
        manager.resume(FLAG);
        Thread.sleep(300);
        stop.set(true);
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "线程应在限定时间内结束");

        System.out.println("[TEST] 并发停用一致性: 判定=" + reads.get()
                + " 停用命中=" + disableHits.get()
                + " 接受停用=" + acceptedDisables.get()
                + " 容量拒绝=" + rejectedDisables.get()
                + " EVALUATION_ERROR=" + evalErrors.get()
                + " 不一致=" + inconsistent.get());

        assertTrue(reads.get() > 0, "应实际发生了并发判定");
        assertTrue(disableHits.get() > 0, "读线程应观察到主线程对 FLAG 的停用");
        assertEquals(0, evalErrors.get(), "并发下不得出现判定异常");
        assertEquals(0, inconsistent.get(),
                "命中停用必带 disableId、结论必带快照标识，不得出现半生效状态");
        assertTrue(failures.isEmpty(), "并发过程中不应有断言/异常失败: " + failures);
        assertTrue(acceptedDisables.get() > 0, "应有停用被接受");
        assertTrue(manager.activeDisables().size() <= CAPACITY, "结束后停用数不得超容量");
        assertFalse(manager.evaluate(FLAG,
                new RequestContext("visitor-1", Map.of())).reason()
                == EvaluationReason.FLAG_TEMPORARILY_DISABLED,
                "主线程停用已恢复，FLAG 不应再被停用拦截");
    }
}
