package com.github.lisama569.runtimefeatureflagsjava.flag.service;

import java.util.List;

/**
 * 一次停用/恢复操作的结果：是否接受、命中的指令、拒绝原因与细节。
 * 停用一组开关时 {@code directives} 为本次实际新增的全部指令；
 * 被拒绝（如超容量）时为空列表，且现状保持不变。
 */
public record DisableOutcome(
        boolean accepted,
        DisableFailureReason failureReason,
        List<DisableDirective> directives,
        String detail) {

    public static DisableOutcome ok(List<DisableDirective> directives, String detail) {
        return new DisableOutcome(true, DisableFailureReason.SUCCESS,
                List.copyOf(directives), detail);
    }

    public static DisableOutcome fail(DisableFailureReason reason, String detail) {
        return new DisableOutcome(false, reason, List.of(), detail);
    }
}
