package com.github.lisama569.runtimefeatureflagsjava.flag.model;

/**
 * 开关判定结论的可解释原因码。
 */
public enum EvaluationReason {
    NO_CONFIG_LOADED,
    KILL_SWITCH_ACTIVE,
    FLAG_NOT_FOUND,
    MASTER_SWITCH_OFF,
    ALLOWLIST_MATCH,
    TARGETING_NOT_MATCHED,
    PERCENTAGE_BUCKETED,
    OUT_OF_PERCENTAGE,
    EVALUATION_ERROR
}
