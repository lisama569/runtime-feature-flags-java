package com.github.lisama569.runtimefeatureflagsjava.model;

import java.util.List;

/** 一次判定的完整结论：结果、依据的快照标识、原因与判定过程轨迹。 */
public record Decision(String flagKey, String visitorId, boolean on, DecisionReason reason,
                       String snapshotId, List<String> trace) {
}
