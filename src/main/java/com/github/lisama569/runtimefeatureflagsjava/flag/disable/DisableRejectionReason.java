package com.github.lisama569.runtimefeatureflagsjava.flag.disable;

/**
 * 停用/恢复操作被拒绝的顶层原因码（可区分，绝不静默丢条目）。
 */
public enum DisableRejectionReason {
    /** 操作被接受。 */
    SUCCESS,
    /** 请求非法：开关名为空、TTL 非正数、批量为空等。 */
    INVALID_REQUEST,
    /** 生效中的停用条目数已达容量上限，本次操作整体拒绝、现状不变。 */
    CAPACITY_EXCEEDED,
    /** 恢复目标当前没有生效中的停用记录。 */
    NOT_DISABLED
}
