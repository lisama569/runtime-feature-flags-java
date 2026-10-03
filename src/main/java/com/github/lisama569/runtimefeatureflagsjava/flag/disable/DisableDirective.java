package com.github.lisama569.runtimefeatureflagsjava.flag.disable;

/**
 * 一条停用指令（不可变）：谁、因为什么、停哪个开关、何时到期、基于哪版配置生效。
 * <p>
 * {@code expiresAtMillis == null} 表示永久停用，只能显式恢复解除；
 * 否则到点自动恢复（判定与查询时按当前时间惰性判定到期）。
 */
public record DisableDirective(
        String id,
        String flagKey,
        String reason,
        String operator,
        long createdAtMillis,
        Long expiresAtMillis,
        String baseSnapshotId) {

    public boolean isExpired(long nowMillis) {
        return expiresAtMillis != null && nowMillis >= expiresAtMillis;
    }
}
