package com.github.lisama569.runtimefeatureflagsjava.flag.model;

import com.github.lisama569.runtimefeatureflagsjava.flag.service.DisableDirective;

import java.util.List;
import java.util.Map;

/**
 * 一次开关判定的结论。
 *
 * @param fieldOrigins       本次判定所用规则各字段来自哪个配置来源（可能为空）
 * @param disableDirectives  命中的生效中紧急停用指令；为空表示未被局部停用
 * @param alsoGlobalKillActive 全局总闸是否同时处于开启状态（用于区分"只有局部停用"
 *                           与"全局+局部叠加"两种归因）
 */
public record EvaluationResult(
        String flagKey,
        String visitorId,
        boolean enabled,
        EvaluationReason reason,
        String detail,
        String snapshotId,
        Map<String, String> fieldOrigins,
        List<DisableDirective> disableDirectives,
        boolean alsoGlobalKillActive) {

    public EvaluationResult(String flagKey, String visitorId, boolean enabled,
                            EvaluationReason reason, String detail, String snapshotId) {
        this(flagKey, visitorId, enabled, reason, detail, snapshotId,
                Map.of(), List.of(), false);
    }
}
