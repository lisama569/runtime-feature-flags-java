package com.github.lisama569.runtimefeatureflagsjava.flag.model;

import java.util.Map;

/**
 * 一份不可变的配置快照：刷新成功后整体原子发布，读请求永远只看到完整快照。
 *
 * @param fieldOrigins 每个开关各配置字段（enabled/percentage/allowlist/target.&lt;attr&gt;/salt）
 *                     最终值来自哪个配置来源（来源名），用于判定日志对账；
 *                     另含 {@code kill.switch} 键的出处。可能为空（如无快照/KILL 派生快照沿用旧值）。
 */
public record ConfigSnapshot(
        String snapshotId,
        long version,
        long createdAtMillis,
        boolean killSwitch,
        Map<String, FlagRule> flags,
        Map<String, String> fieldOrigins) {
}
