package com.github.lisama569.runtimefeatureflagsjava.flag.model;

import java.util.Map;

/**
 * 一份不可变的配置快照：刷新成功后整体原子发布，读请求永远只看到完整快照。
 */
public record ConfigSnapshot(
        String snapshotId,
        long version,
        long createdAtMillis,
        boolean killSwitch,
        Map<String, FlagRule> flags) {
}
