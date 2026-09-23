package com.github.lisama569.runtimefeatureflagsjava.model;

/** 配置被拒绝的可区分原因。 */
public enum RejectionKind {
    /** 必需/已配置的来源文件不存在。 */
    SOURCE_MISSING,
    /** 来源存在但无法读取或解析。 */
    SOURCE_UNREADABLE,
    /** 来源中的取值非法（类型错误、越界、未知字段等）。 */
    INVALID_VALUE
}
