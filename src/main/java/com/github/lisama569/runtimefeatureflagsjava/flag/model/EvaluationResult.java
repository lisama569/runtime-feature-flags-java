package com.github.lisama569.runtimefeatureflagsjava.flag.model;

/**
 * 一次开关判定的结论：结论值、原因码、判定细节、所依据的快照标识。
 */
public record EvaluationResult(
        String flagKey,
        String visitorId,
        boolean enabled,
        EvaluationReason reason,
        String detail,
        String snapshotId) {
}
