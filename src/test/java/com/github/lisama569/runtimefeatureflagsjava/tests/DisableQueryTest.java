package com.github.lisama569.runtimefeatureflagsjava.tests;

import com.github.lisama569.runtimefeatureflagsjava.flag.service.DisableDirective;
import com.github.lisama569.runtimefeatureflagsjava.flag.service.FeatureFlagManager;
import com.github.lisama569.runtimefeatureflagsjava.flag.source.InMemoryConfigSource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 场景：随时可查"当前哪些开关正被停用"，每条信息完整：
 * 涉及开关、原因、操作人、创建/到期时间、基于哪一版配置快照生效；
 * 列表按开关键与创建时间稳定排序；到期或恢复后从列表消失。
 */
class DisableQueryTest {

    @Test
    void activeListingContainsAllRequiredFieldsAndStaysSorted() {
        AtomicLong clock = new AtomicLong(5_000L);
        InMemoryConfigSource remote = new InMemoryConfigSource("remote-center", 100);
        remote.setProperties(Map.of(
                "feature.zeta.enabled", "true",
                "feature.alpha.enabled", "true"));
        FeatureFlagManager manager = new FeatureFlagManager(
                List.of(remote), clock::get);
        String snapshotId = manager.refresh().activeSnapshotId();
        long baseVersion = 1L;

        manager.disable(List.of("feature.zeta"), "原因Z", "op-z", 10_000L);
        clock.set(6_000L);
        manager.disable(List.of("feature.alpha"), "原因A", "op-a", 0L);

        List<DisableDirective> list = manager.activeDisables();
        assertEquals(2, list.size());
        // 按开关键排序：alpha 在 zeta 前
        assertEquals("feature.alpha", list.get(0).flagKey());
        assertEquals("feature.zeta", list.get(1).flagKey());

        DisableDirective alpha = list.get(0);
        assertEquals("原因A", alpha.reason());
        assertEquals("op-a", alpha.operator());
        assertEquals(6_000L, alpha.createdAtMillis());
        assertTrue(alpha.permanent(), "ttl=0 为不限期");
        assertEquals(snapshotId, alpha.baseSnapshotId());
        assertEquals(baseVersion, alpha.baseSnapshotVersion());

        DisableDirective zeta = list.get(1);
        assertEquals(5_000L + 10_000L, zeta.expireAtMillis());
        assertFalse(zeta.permanent());
        System.out.println("[TEST] 停用列表: " + list);

        // zeta 到期后，列表只剩 alpha
        clock.set(15_001L);
        List<DisableDirective> afterExpiry = manager.activeDisables();
        assertEquals(1, afterExpiry.size());
        assertEquals("feature.alpha", afterExpiry.get(0).flagKey());

        // alpha 恢复后列表为空
        assertTrue(manager.restore("feature.alpha").accepted());
        assertEquals(0, manager.activeDisables().size());
        System.out.println("[TEST] 到期/恢复后列表收缩，查询结果完整一致");
    }
}
