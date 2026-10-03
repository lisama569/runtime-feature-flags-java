package com.github.lisama569.runtimefeatureflagsjava.flag.model;

/**
 * 开关判定结论的可解释原因码。
 */
public enum EvaluationReason {
    NO_CONFIG_LOADED,
    KILL_SWITCH_ACTIVE,
    /** 该开关被紧急停用指令命中（局部停用层关闭）。 */
    FLAG_DISABLED,
    FLAG_NOT_FOUND,
    MASTER_SWITCH_OFF,
    ALLOWLIST_MATCH,
    TARGETING_NOT_MATCHED,
    PERCENTAGE_BUCKETED,
    OUT_OF_PERCENTAGE,
    EVALUATION_ERROR
}
