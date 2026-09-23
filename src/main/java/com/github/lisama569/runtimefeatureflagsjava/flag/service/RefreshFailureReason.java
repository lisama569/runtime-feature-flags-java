package com.github.lisama569.runtimefeatureflagsjava.flag.service;

/**
 * 刷新被拒绝的顶层原因码。
 */
public enum RefreshFailureReason {
    SUCCESS,
    NO_SOURCES,
    SOURCE_UNAVAILABLE,
    INVALID_VALUE
}
