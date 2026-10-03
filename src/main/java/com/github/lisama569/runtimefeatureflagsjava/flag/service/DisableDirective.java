package com.github.lisama569.runtimefeatureflagsjava.flag.service;

/**
 * 一条针对单个开关的紧急停用指令（不可变值对象）。
 * <p>
 * 停用独立于配置刷新存在：配置刷新/回滚/刷新失败都不会删除指令，
 * 只有显式恢复（{@code restore}）或到达 {@code expireAtMillis} 才解除。
 *
 * @param id                指令唯一标识（恢复单条时使用）
 * @param flagKey           被停用的开关键，如 feature.checkout-redesign
 * @param reason            停用原因（必填）
 * @param operator          操作人（必填）
 * @param createdAtMillis   创建时刻（epoch millis）
 * @param expireAtMillis    到期时刻；{@code <= 0} 表示不限期（只能显式恢复）
 * @param baseSnapshotId    生效时所基于的配置快照标识（可能为 null，表示无快照期间创建）
 * @param baseSnapshotVersion 生效时所基于的配置快照版本号
 */
public record DisableDirective(
        String id,
        String flagKey,
        String reason,
        String operator,
        long createdAtMillis,
        long expireAtMillis,
        String baseSnapshotId,
        long baseSnapshotVersion) {

    /** 是否为不限期停用（只能显式恢复）。 */
    public boolean permanent() {
        return expireAtMillis <= 0L;
    }

    /** 给定时刻是否仍未到期。 */
    public boolean activeAt(long nowMillis) {
        return permanent() || nowMillis < expireAtMillis;
    }
}
