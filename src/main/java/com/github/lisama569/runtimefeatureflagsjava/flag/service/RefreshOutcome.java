package com.github.lisama569.runtimefeatureflagsjava.flag.service;

/**
 * 一次刷新/运维操作的结果：是否接受、生效（或继续使用）的快照、失败原因与细节。
 */
public record RefreshOutcome(
        boolean accepted,
        RefreshFailureReason failureReason,
        String publishedSnapshotId,
        String activeSnapshotId,
        String detail) {

    public static RefreshOutcome ok(String snapshotId) {
        return new RefreshOutcome(true, RefreshFailureReason.SUCCESS,
                snapshotId, snapshotId, "操作成功，快照 " + snapshotId + " 已生效");
    }

    public static RefreshOutcome fail(RefreshFailureReason reason,
                                      String activeSnapshotId,
                                      String detail) {
        return new RefreshOutcome(false, reason, null, activeSnapshotId, detail);
    }
}
