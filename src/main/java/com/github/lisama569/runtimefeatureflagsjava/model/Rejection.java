package com.github.lisama569.runtimefeatureflagsjava.model;

/** 一次刷新中某个来源被拒绝的记录，原因必须可区分、可解释。 */
public record Rejection(String sourceName, RejectionKind kind, String detail) {
}
