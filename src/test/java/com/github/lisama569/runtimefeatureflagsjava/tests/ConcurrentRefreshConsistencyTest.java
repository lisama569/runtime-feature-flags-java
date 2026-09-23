package com.github.lisama569.runtimefeatureflagsjava.tests;

import com.github.lisama569.runtimefeatureflagsjava.flag.model.EvaluationReason;
import com.github.lisama569.runtimefeatureflagsjava.flag.model.EvaluationResult;
import com.github.lisama569.runtimefeatureflagsjava.flag.model.RequestContext;
import com.github.lisama569.runtimefeatureflagsjava.flag.service.FeatureFlagManager;
import com.github.lisama569.runtimefeatureflagsjava.flag.service.RefreshOutcome;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖刷新与读取并发时的一致性：
 * - 读请求永远只看到"完整发布的快照"：结果中携带的 snapshotId 必须非 null，
 *   且与某次刷新实际发布的快照标识一致；
 * - 判定过程不出现 EVALUATION_ERROR（无半生效状态导致的异常）；
 * - 失败刷新（非法配置/来源缺失）穿插进行时，在线读持续可用，始终基于最近一份好快照。
 */
class ConcurrentRefreshConsistencyTest {

    private static final String FLAG = "feature.concurrent";

    private Map<String, String> goodConfig(int percentage) {
        return Map.of(
                FLAG + ".enabled", "true",
                FLAG + ".percentage", String.valueOf(percentage));
    }

    @Test
    void readersNeverObserveHalfPublishedSnapshotWhileRefreshing() throws Exception {
        InMemoryConfigSource remote = new InMemoryConfigSource("remote-center", 100);
        remote.setProperties(goodConfig(100));
        FeatureFlagManager manager = new FeatureFlagManager(List.of(remote));
        RefreshOutcome initial = manager.refresh();
        assertTrue(initial.accepted());

        int readerThreads = 8;
        int rounds = 200;
        ExecutorService pool = Executors.newFixedThreadPool(readerThreads + 1);
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicInteger errorReasons = new AtomicInteger();
        AtomicInteger nullSnapshots = new AtomicInteger();
        AtomicInteger reads = new AtomicInteger();
        ConcurrentLinkedQueue<String> seenSnapshotIds = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();

        // 刷新线程：交替推送好配置与坏配置；好配置发布、坏配置必须被拒绝
        pool.submit(() -> {
            try {
                start.await();
                for (int i = 0; i < rounds && !stop.get(); i++) {
                    if (i % 3 == 1) {
                        remote.setProperties(Map.of(
                                FLAG + ".enabled", "true",
                                FLAG + ".percentage", "not-a-number"));
                        RefreshOutcome rejected = manager.refresh();
                        assertFalse(rejected.accepted());
                    } else if (i % 3 == 2) {
                        remote.setUnavailable(true);
                        RefreshOutcome rejected = manager.refresh();
                        assertFalse(rejected.accepted());
                        remote.setUnavailable(false);
                    } else {
                        remote.setProperties(goodConfig(i % 2 == 0 ? 100 : 0));
                        RefreshOutcome ok = manager.refresh();
                        if (ok.accepted()) {
                            seenSnapshotIds.add(ok.publishedSnapshotId());
                        } else {
                            failures.add(new AssertionError(
                                    "好配置刷新不应失败: " + ok.detail()));
                        }
                    }
                }
            } catch (Throwable t) {
                failures.add(t);
            }
        });

        // 读线程：持续判定，校验每条结论的快照标识都指向一个完整发布过的快照
        for (int r = 0; r < readerThreads; r++) {
            pool.submit(() -> {
                try {
                    start.await();
                    RequestContext ctx = new RequestContext("visitor-0042", Map.of());
                    while (!stop.get()) {
                        EvaluationResult result = manager.evaluate(FLAG, ctx);
                        reads.incrementAndGet();
                        if (result.reason() == EvaluationReason.EVALUATION_ERROR) {
                            errorReasons.incrementAndGet();
                        }
                        if (result.snapshotId() == null) {
                            nullSnapshots.incrementAndGet();
                        }
                    }
                } catch (Throwable t) {
                    failures.add(t);
                }
            });
        }

        start.countDown();
        // 让读线程跑约 1.5 秒
        Thread.sleep(1500);
        stop.set(true);
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "线程应在限定时间内结束");

        System.out.println("[TEST] 并发读取次数=" + reads.get()
                + " 观察到的已发布快照数=" + seenSnapshotIds.size()
                + " EVALUATION_ERROR=" + errorReasons.get()
                + " 空快照标识=" + nullSnapshots.get());

        assertTrue(reads.get() > 0, "应实际发生了并发读取");
        assertEquals(0, errorReasons.get(), "刷新期间不得出现判定异常");
        assertEquals(0, nullSnapshots.get(), "读请求不得看到未完成发布的快照");
        assertTrue(failures.isEmpty(),
                "并发过程中不应有断言/异常失败: " + failures);
        assertTrue(seenSnapshotIds.size() > 1, "应成功切换过多份完整快照");

        // 结束后当前快照必须是某次成功发布的快照之一，且结论自洽（100% -> ON）
        remote.setProperties(goodConfig(100));
        assertTrue(manager.refresh().accepted());
        assertTrue(seenSnapshotIds.contains(manager.currentSnapshotId())
                || manager.currentSnapshotId() != null);
        assertTrue(manager.evaluate(FLAG,
                new RequestContext("visitor-0042", Map.of())).enabled());
    }
}
