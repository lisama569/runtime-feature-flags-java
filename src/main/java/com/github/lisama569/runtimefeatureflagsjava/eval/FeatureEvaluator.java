package com.github.lisama569.runtimefeatureflagsjava.eval;

import com.github.lisama569.runtimefeatureflagsjava.model.Decision;
import com.github.lisama569.runtimefeatureflagsjava.model.DecisionReason;
import com.github.lisama569.runtimefeatureflagsjava.model.FlagDefinition;
import com.github.lisama569.runtimefeatureflagsjava.model.FlagSnapshot;
import com.github.lisama569.runtimefeatureflagsjava.model.RequestContext;
import com.github.lisama569.runtimefeatureflagsjava.model.TargetingRule;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 纯函数判定器：给定不可变快照与请求上下文，产出稳定、可复现、可解释的结论。
 *
 * <p>判定链（顺序即优先级，命中即返回，过程全部写入 trace）：</p>
 * <ol>
 *   <li>无任何配置快照 → {@link DecisionReason#ERROR_NO_CONFIG}，按 failOpen 兜底；</li>
 *   <li>全局 kill switch 开启 → {@link DecisionReason#GLOBAL_KILL_SWITCH}，结论 OFF；</li>
 *   <li>开关不存在 → {@link DecisionReason#ERROR_FLAG_NOT_FOUND}，按 failOpen 兜底；</li>
 *   <li>开关 enabled=false → {@link DecisionReason#FLAG_DISABLED}；</li>
 *   <li>命中 blocklist → {@link DecisionReason#BLOCKLIST_HIT}（黑名单优先于白名单）；</li>
 *   <li>命中 allowlist → {@link DecisionReason#ALLOWLIST_HIT}；</li>
 *   <li>存在 targeting 规则：上下文满足全部规则 → TARGETING_MATCH，否则 TARGETING_NOT_MATCH（直接拒绝）；</li>
 *   <li>按比例放量：bucketOf(flagKey, visitorId) &lt; percentage（基点）→ ROLLOUT_HIT / ROLLOUT_MISS。</li>
 * </ol>
 *
 * <p>兜底语义（显式、可解释，绝不抛出运行时异常给调用方）：</p>
 * <ul>
 *   <li>fail-closed（默认）：无配置 / 开关不存在 → 结论 OFF，原因带 ERROR_ 前缀；</li>
 *   <li>fail-open：同样两类错误 → 结论 ON，原因依然带 ERROR_ 前缀，调用方可以区分“正常放量命中”与“兜底放开”。</li>
 * </ul>
 */
@org.springframework.stereotype.Component
public class FeatureEvaluator {

    public Decision evaluate(FlagSnapshot snapshot, String flagKey, RequestContext context, boolean failOpen) {
        List<String> trace = new ArrayList<>();
        String visitorId = context == null ? null : context.getVisitorId();
        trace.add("evaluate flag=" + flagKey + " visitor=" + visitorId + " failOpen=" + failOpen);

        if (snapshot == null) {
            trace.add("no active snapshot available -> fallback " + (failOpen ? "ON(fail-open)" : "OFF(fail-closed)"));
            return new Decision(flagKey, visitorId, failOpen, DecisionReason.ERROR_NO_CONFIG, null, List.copyOf(trace));
        }
        trace.add("using snapshot " + snapshot.getSnapshotId() + " version=" + snapshot.getVersion());

        if (snapshot.isGlobalKillSwitch()) {
            trace.add("global kill-switch is ON -> OFF");
            return finish(flagKey, visitorId, false, DecisionReason.GLOBAL_KILL_SWITCH, snapshot, trace);
        }

        FlagDefinition def = snapshot.getFlags().get(flagKey);
        if (def == null) {
            trace.add("flag '" + flagKey + "' not defined in snapshot -> fallback "
                    + (failOpen ? "ON(fail-open)" : "OFF(fail-closed)"));
            return finish(flagKey, visitorId, failOpen, DecisionReason.ERROR_FLAG_NOT_FOUND, snapshot, trace);
        }

        trace.add("merged definition: enabled=" + def.isEnabled()
                + " percentage=" + def.getPercentage() + "bp(" + (def.getPercentage() / 100.0) + "%)"
                + " allowlist=" + def.getAllowlist() + " blocklist=" + def.getBlocklist()
                + " targeting=" + def.getTargetingRules()
                + " provenance=" + def.getProvenance());

        if (!def.isEnabled()) {
            trace.add("flag disabled -> OFF");
            return finish(flagKey, visitorId, false, DecisionReason.FLAG_DISABLED, snapshot, trace);
        }

        if (visitorId != null && def.getBlocklist().contains(visitorId)) {
            trace.add("visitor on blocklist -> OFF (blocklist takes precedence over allowlist)");
            return finish(flagKey, visitorId, false, DecisionReason.BLOCKLIST_HIT, snapshot, trace);
        }

        if (visitorId != null && def.getAllowlist().contains(visitorId)) {
            trace.add("visitor on allowlist -> ON (sticky regardless of percentage)");
            return finish(flagKey, visitorId, true, DecisionReason.ALLOWLIST_HIT, snapshot, trace);
        }

        if (!def.getTargetingRules().isEmpty()) {
            Map<String, String> attrs = context == null ? Map.of() : context.getAttributes();
            boolean allMatch = true;
            for (TargetingRule rule : def.getTargetingRules()) {
                String actual = attrs.get(rule.attribute());
                boolean match = rule.expected().equals(actual);
                trace.add("targeting " + rule.attribute() + " expected=" + rule.expected()
                        + " actual=" + actual + " -> " + (match ? "match" : "MISMATCH"));
                if (!match) {
                    allMatch = false;
                    break;
                }
            }
            if (!allMatch) {
                trace.add("targeting rules not all satisfied -> OFF (no fallthrough to percentage rollout)");
                return finish(flagKey, visitorId, false, DecisionReason.TARGETING_NOT_MATCH, snapshot, trace);
            }
            trace.add("targeting rules all satisfied -> ON");
            return finish(flagKey, visitorId, true, DecisionReason.TARGETING_MATCH, snapshot, trace);
        }

        if (visitorId == null) {
            trace.add("no visitorId, cannot bucket -> OFF");
            return finish(flagKey, visitorId, false, DecisionReason.ROLLOUT_MISS, snapshot, trace);
        }
        int bucket = BucketHasher.bucketOf(flagKey, visitorId);
        boolean hit = bucket < def.getPercentage();
        trace.add("bucket FNV1a(flag|visitor)=" + bucket + "/10000 vs threshold=" + def.getPercentage()
                + " -> " + (hit ? "ROLLOUT_HIT -> ON" : "ROLLOUT_MISS -> OFF"));
        return finish(flagKey, visitorId, hit,
                hit ? DecisionReason.ROLLOUT_HIT : DecisionReason.ROLLOUT_MISS, snapshot, trace);
    }

    private Decision finish(String flagKey, String visitorId, boolean on, DecisionReason reason,
                            FlagSnapshot snapshot, List<String> trace) {
        trace.add("decision on=" + on + " reason=" + reason);
        return new Decision(flagKey, visitorId, on, reason, snapshot.getSnapshotId(), List.copyOf(trace));
    }
}
