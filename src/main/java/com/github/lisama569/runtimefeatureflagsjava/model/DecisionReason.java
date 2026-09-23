package com.github.lisama569.runtimefeatureflagsjava.model;

/** 开关判定结论的原因，必须稳定、可复现、可解释。 */
public enum DecisionReason {
    GLOBAL_KILL_SWITCH,
    FLAG_DISABLED,
    BLOCKLIST_HIT,
    ALLOWLIST_HIT,
    TARGETING_MATCH,
    TARGETING_NOT_MATCH,
    ROLLOUT_HIT,
    ROLLOUT_MISS,
    ERROR_FLAG_NOT_FOUND,
    ERROR_NO_CONFIG
}
