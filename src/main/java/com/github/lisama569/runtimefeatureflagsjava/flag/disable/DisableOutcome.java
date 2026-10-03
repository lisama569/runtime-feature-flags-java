package com.github.lisama569.runtimefeatureflagsjava.flag.disable;

import java.util.List;

/**
 * 一次停用/恢复操作的结果：是否接受、可区分的拒绝原因、细节、受影响开关。
 * 被拒绝时保证现状不变（不静默丢掉任何已生效条目）。
 */
public record DisableOutcome(
        boolean accepted,
        DisableRejectionReason rejectionReason,
        String detail,
        List<String> affectedFlags) {

    public static DisableOutcome ok(String detail, List<String> affectedFlags) {
        return new DisableOutcome(true, DisableRejectionReason.SUCCESS,
                detail, List.copyOf(affectedFlags));
    }

    public static DisableOutcome fail(DisableRejectionReason reason, String detail) {
        return new DisableOutcome(false, reason, detail, List.of());
    }
}
