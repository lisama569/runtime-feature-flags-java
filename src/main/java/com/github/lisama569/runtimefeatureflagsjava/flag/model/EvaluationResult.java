package com.github.lisama569.runtimefeatureflagsjava.flag.model;

/**
 * 一次开关判定的结论：结论值、原因码、判定细节、所依据的快照标识、命中的停用记录标识。
 * <p>
 * {@code disableId} 非空表示本次判定命中了生效中的停用记录（多条命中时为合并标识，
 * 形如 "d-1+d-2"）；此时若 {@code reason} 为
 * {@link EvaluationReason#FLAG_TEMPORARILY_DISABLED} 表示由局部停用层关闭，
 * 若为 {@link EvaluationReason#KILL_SWITCH_ACTIVE} 表示全局总闸层优先关闭、
 * 局部停用同时命中（两层归因均可从结果中区分）。
 */
public record EvaluationResult(
        String flagKey,
        String visitorId,
        boolean enabled,
        EvaluationReason reason,
        String detail,
        String snapshotId,
        String disableId) {

    /** 兼容构造：无停用记录命中。 */
    public EvaluationResult(String flagKey, String visitorId, boolean enabled,
                            EvaluationReason reason, String detail, String snapshotId) {
        this(flagKey, visitorId, enabled, reason, detail, snapshotId, null);
    }
}
