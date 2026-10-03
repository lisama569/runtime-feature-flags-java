package com.github.lisama569.runtimefeatureflagsjava.flag.service;

/**
 * 紧急停用/恢复操作被拒绝的细分原因码（必须可互相区分）。
 */
public enum DisableFailureReason {
    SUCCESS,
    /** 参数非法：开关名/原因/操作人为空，或 TTL 为负。 */
    INVALID_ARGUMENT,
    /** 生效中停用条目已达容量上限，本次操作整体拒绝，现状不变。 */
    CAPACITY_EXCEEDED,
    /** 恢复目标不存在（开关没有生效中的停用，或指令 id 不存在）。 */
    NOT_FOUND
}
