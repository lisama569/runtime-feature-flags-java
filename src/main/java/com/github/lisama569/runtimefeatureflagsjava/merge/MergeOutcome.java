package com.github.lisama569.runtimefeatureflagsjava.merge;

import com.github.lisama569.runtimefeatureflagsjava.model.FlagSnapshot;
import com.github.lisama569.runtimefeatureflagsjava.model.Rejection;

import java.util.List;

/** 合并结果：要么产出完整快照，要么携带全部拒绝原因（二者互斥）。 */
public record MergeOutcome(FlagSnapshot snapshot, List<Rejection> rejections) {

    public boolean isAccepted() {
        return snapshot != null;
    }
}
