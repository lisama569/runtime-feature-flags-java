package com.github.lisama569.runtimefeatureflagsjava.tests;

import com.github.lisama569.runtimefeatureflagsjava.config.FeatureFlagProperties;
import com.github.lisama569.runtimefeatureflagsjava.eval.BucketHasher;
import com.github.lisama569.runtimefeatureflagsjava.eval.FeatureEvaluator;
import com.github.lisama569.runtimefeatureflagsjava.manager.FeatureFlagManager;
import com.github.lisama569.runtimefeatureflagsjava.merge.SnapshotMerger;
import com.github.lisama569.runtimefeatureflagsjava.model.Decision;
import com.github.lisama569.runtimefeatureflagsjava.model.DecisionReason;
import com.github.lisama569.runtimefeatureflagsjava.model.RefreshResult;
import com.github.lisama569.runtimefeatureflagsjava.model.RequestContext;
import com.github.lisama569.runtimefeatureflagsjava.source.ConfigSource;
import com.github.lisama569.runtimefeatureflagsjava.source.PropertiesFileConfigSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 刷新期间并发读取：读者始终拿到“旧或新的完整快照”，结论与所依据快照严格自洽。 */
class ConcurrentRefreshReadTest {

    private static final Pattern THRESHOLD_IN_TRACE = Pattern.compile("threshold=(\\d+)");

    @Test
    void concurrentReadsNeverSeeIntermediateState(@TempDir Path dir) throws Exception {
        Path base = dir.resolve("base.properties");
        Path override = dir.resolve("override.properties");
        writeAtomically(base, "flag.f.enabled=true\nflag.f.percentage=0\n");
        writeAtomically(override, "flag.f.percentage=1000\n");

        FeatureFlagProperties props = new FeatureFlagProperties();
        props.setHistorySize(200);
        List<ConfigSource> sources = List.of(
                new PropertiesFileConfigSource("base", base, true),
                new PropertiesFileConfigSource("override", override, false));
        FeatureFlagManager manager = new FeatureFlagManager(props, new SnapshotMerger(),
                new FeatureEvaluator(), sources);

        int refreshRounds = 80;
        int[] thresholds = {0, 500, 1000, 2500, 5000, 7500, 9000, 10000};
        AtomicInteger refreshIndex = new AtomicInteger();
        AtomicBoolean stop = new AtomicBoolean(false);
        IssueBag failures = new IssueBag();

        // 写线程：原子替换文件 + refresh，交替写入合法值
        Thread writer = new Thread(() -> {
            try {
                for (int i = 0; i < refreshRounds; i++) {
                    int t = thresholds[i % thresholds.length];
                    writeAtomically(override, "flag.f.percentage=" + t + "\n");
                    RefreshResult result = manager.refresh();
                    if (!result.success()) {
                        failures.add("refresh rejected unexpectedly: " + result.rejections());
                    }
                    refreshIndex.incrementAndGet();
                }
            } catch (Exception e) {
                failures.add("writer exception: " + e);
            } finally {
                stop.set(true);
            }
        }, "config-writer");

        // 读线程：不停判定；每条结论必须与它 trace 中记录的快照阈值自洽
        int readerCount = 6;
        ExecutorService pool = Executors.newFixedThreadPool(readerCount);
        CountDownLatch done = new CountDownLatch(readerCount);
        AtomicInteger reads = new AtomicInteger();
        for (int r = 0; r < readerCount; r++) {
            pool.submit(() -> {
                try {
                    while (!stop.get()) {
                        for (int v = 0; v < 50; v++) {
                            String visitor = "visitor-" + v;
                            Decision d = manager.evaluate("f", new RequestContext(visitor));
                            reads.incrementAndGet();
                            verifyDecisionSelfConsistent(d, visitor, failures);
                        }
                    }
                } finally {
                    done.countDown();
                }
            });
        }

        writer.start();
        writer.join(20_000);
        assertTrue(writer.getState() == Thread.State.TERMINATED, "writer should finish");
        assertTrue(done.await(10, TimeUnit.SECONDS), "readers should finish");
        pool.shutdownNow();

        System.out.println("[Concurrent] refreshes=" + refreshIndex.get() + " reads=" + reads.get()
                + " failures=" + failures.values());
        assertTrue(failures.values().isEmpty(), "inconsistent reads observed: " + failures.values());
        assertEquals(refreshRounds, refreshIndex.get());
    }

    @Test
    void concurrentReadsSurviveRejectedRefreshes(@TempDir Path dir) throws Exception {
        Path base = dir.resolve("base.properties");
        Path override = dir.resolve("override.properties");
        writeAtomically(base, "flag.f.enabled=true\nflag.f.percentage=5000\n");
        writeAtomically(override, "");
        FeatureFlagProperties props = new FeatureFlagProperties();
        props.setHistorySize(50);
        FeatureFlagManager manager = new FeatureFlagManager(props, new SnapshotMerger(), new FeatureEvaluator(),
                List.of(new PropertiesFileConfigSource("base", base, true),
                        new PropertiesFileConfigSource("override", override, false)));

        String goodSnapshot = manager.currentSnapshot().getSnapshotId();
        AtomicBoolean failures = new AtomicBoolean(false);
        AtomicBoolean stop = new AtomicBoolean(false);

        Thread writer = new Thread(() -> {
            try {
                for (int i = 0; i < 60; i++) {
                    // 交替：非法 override（被拒绝，保持旧快照）/ 合法 override
                    String content = (i % 2 == 0)
                            ? "flag.f.percentage=BOOM\n"
                            : "flag.f.percentage=" + (5000 + i) + "\n";
                    writeAtomically(override, content);
                    RefreshResult result = manager.refresh();
                    if (i % 2 == 0 && result.success()) {
                        failures.set(true);
                    }
                }
            } catch (Exception e) {
                failures.set(true);
            } finally {
                stop.set(true);
            }
        });

        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch done = new CountDownLatch(4);
        IssueBag bad = new IssueBag();
        for (int r = 0; r < 4; r++) {
            pool.submit(() -> {
                try {
                    while (!stop.get()) {
                        Decision d = manager.evaluate("f", new RequestContext("visitor-7"));
                        // 读者永远不可能拿到 null 快照 / ERROR 原因（首份配置是好的，且拒绝不换快照）
                        if (d.snapshotId() == null || (d.reason() != DecisionReason.ROLLOUT_HIT
                                && d.reason() != DecisionReason.ROLLOUT_MISS)) {
                            bad.add("unexpected decision during rejected refresh: " + d);
                        }
                    }
                } finally {
                    done.countDown();
                }
            });
        }
        writer.start();
        writer.join(20_000);
        done.await(10, TimeUnit.SECONDS);
        pool.shutdownNow();

        System.out.println("[Concurrent] rejected-refresh run finished, initial snapshot=" + goodSnapshot
                + " final=" + manager.currentSnapshot().getSnapshotId() + " badReads=" + bad.values());
        assertTrue(!failures.get(), "invalid refresh must be rejected");
        assertTrue(bad.values().isEmpty(), bad.values().toString());
    }

    private void verifyDecisionSelfConsistent(Decision d, String visitor, IssueBag failures) {
        if (d.snapshotId() == null
                || (d.reason() != DecisionReason.ROLLOUT_HIT && d.reason() != DecisionReason.ROLLOUT_MISS)) {
            failures.add("unexpected decision: " + d);
            return;
        }
        Integer threshold = null;
        for (String line : d.trace()) {
            Matcher m = THRESHOLD_IN_TRACE.matcher(line);
            if (m.find()) {
                threshold = Integer.parseInt(m.group(1));
            }
        }
        if (threshold == null) {
            failures.add("trace missing threshold: " + d.snapshotId());
            return;
        }
        boolean expected = BucketHasher.bucketOf("f", visitor) < threshold;
        if (expected != d.on()) {
            failures.add("inconsistent: visitor=" + visitor + " snapshot=" + d.snapshotId()
                    + " threshold=" + threshold + " decision=" + d.on());
        }
    }

    private static void writeAtomically(Path target, String content) throws Exception {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(tmp, content);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** 简易并发收集器，避免命名与 JDK 类型冲突时的阅读干扰。 */
    private static final class IssueBag {
        private final ConcurrentLinkedQueue<String> queue = new ConcurrentLinkedQueue<>();

        void add(String value) {
            queue.add(value);
        }

        List<String> values() {
            return List.copyOf(queue);
        }
    }
}
