package com.github.lisama569.runtimefeatureflagsjava.flag.config;

/**
 * 非法取值的细分问题类型，保证拒绝原因可区分。
 */
public enum ConfigProblemType {
    MISSING_FIELD,
    INVALID_BOOLEAN,
    INVALID_INTEGER,
    INVALID_PERCENTAGE_RANGE,
    INVALID_KEY,
    UNKNOWN_FIELD
}
