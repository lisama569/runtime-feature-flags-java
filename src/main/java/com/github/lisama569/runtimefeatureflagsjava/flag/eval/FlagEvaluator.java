package com.github.lisama569.runtimefeatureflagsjava.flag.eval;

import com.github.lisama569.runtimefeatureflagsjava.flag.model.ConfigSnapshot;
import com.github.lisama569.runtimefeatureflagsjava.flag.model.EvaluationReason;
import com.github.lisama569.runtimefeatureflagsjava.flag.model.EvaluationResult;
import com.github.lisama569.runtimefeatureflagsjava.flag.model.FlagRule;
import com.github.lisama569.runtimefeatureflagsjava.flag.model.RequestContext;
import com.github.lisama569.runtimefeatureflagsjava.flag.service.DisableDirective;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于单一不可变快照 + 该时刻生效中的停用指令完成开关判定（确定性、可复现、可解释）。
 * <p>
 * 判定顺序（短路，优先级从高到低）：
 * <ol>
 *   <li>无已加载快照但该开关被局部停用 → FLAG_DISABLED（fail-CLOSED，归因明确）；
 *       无快照且无停用 → NO_CONFIG_LOADED，fail-CLOSED</li>
 *   <li>全局总闸 kill.switch=true → KILL_SWITCH_ACTIVE，一律 false；
 *       若该开关同时被局部停用，结论中携带停用指令且 alsoGlobalKillActive=true，
 *       可分清"全局层"与"局部层"同时关闭</li>
 *   <li>该开关存在生效中的紧急停用指令 → FLAG_DISABLED，false，
 *       与开关是否在配置中定义无关（紧急控制不依赖配置内容）</li>
 *   <li>开关不存在 → fail-CLOSED，原因 FLAG_NOT_FOUND</li>
 *   <li>开关总开关 enabled=false → false，原因 MASTER_SWITCH_OFF</li>
 *   <li>命中白名单 → true，原因 ALLOWLIST_MATCH（白名单不受放量比例影响）</li>
 *   <li>定向条件存在且未全部命中 → false，原因 TARGETING_NOT_MATCHED</li>
 *   <li>按稳定哈希分桶：bucket &lt; percentage → true，否则 false</li>
 * </ol>
 * <b>粘性</b>：bucket 仅由 salt + visitorId 决定，与 percentage 无关；
 * 放量比例增减只移动阈值，不重排桶位。
 * <p>
 * 停用指令由调用方在锁外基于已发布的不可变状态取出（已过滤到期条目），
 * 因此读到的"快照 + 停用"是同一版本的完整状态。
 */
public class FlagEvaluator {

    private static final Logger log = LoggerFactory.getLogger(FlagEvaluator.class);

    public EvaluationResult evaluate(String flagKey, RequestContext context,
                                     ConfigSnapshot snapshot) {
        return evaluate(flagKey, context, snapshot, List.of());
    }

    public EvaluationResult evaluate(String flagKey, RequestContext context,
                                     ConfigSnapshot snapshot,
                                     List<DisableDirective> activeDisables) {
        String visitorId = context == null ? null : context.visitorId();
        List<DisableDirective> disables = activeDisables == null ? List.of() : activeDisables;
        boolean disabled = !disables.isEmpty();
        Map<String, String> origins = originsFor(flagKey, snapshot);

        if (snapshot == null) {
            if (disabled) {
                // 无配置快照期间局部停用仍然有效（紧急控制不依赖配置）
                String detail = "无已加载配置快照，且该开关被紧急停用，fail-CLOSED。"
                        + describeDisables(disables);
                return finish(new EvaluationResult(flagKey, visitorId, false,
                        EvaluationReason.FLAG_DISABLED, detail, null,
                        Map.of(), disables, false));
            }
            // 兜底语义：从未成功加载过任何配置时，fail-CLOSED
            return finish(new EvaluationResult(flagKey, visitorId, false,
                    EvaluationReason.NO_CONFIG_LOADED,
                    "尚未加载任何配置快照，按 fail-CLOSED 拒绝开启", null,
                    Map.of(), List.of(), false));
        }

        if (snapshot.killSwitch()) {
            // 全局层关闭；若局部层也命中，指令随结论带出，归因可分清两层
            String detail = "全局总闸 kill.switch 已开启，所有开关强制关闭"
                    + (disabled ? "；同时该开关存在局部停用（两层叠加）"
                            + describeDisables(disables) : "（该开关无局部停用）");
            return finish(new EvaluationResult(flagKey, visitorId, false,
                    EvaluationReason.KILL_SWITCH_ACTIVE, detail,
                    snapshot.snapshotId(), origins,
                    disables, true));
        }

        if (disabled) {
            // 局部停用层关闭：与配置中 enabled/白名单/分桶无关，一律 OFF
            String detail = "该开关命中紧急停用指令，强制关闭" + describeDisables(disables);
            return finish(new EvaluationResult(flagKey, visitorId, false,
                    EvaluationReason.FLAG_DISABLED, detail,
                    snapshot.snapshotId(), origins, disables, false));
        }

        FlagRule rule = snapshot.flags().get(flagKey);
        if (rule == null) {
            // 兜底语义：开关未定义，fail-CLOSED（不会"默认开启"）
            return finish(new EvaluationResult(flagKey, visitorId, false,
                    EvaluationReason.FLAG_NOT_FOUND,
                    "快照中不存在该开关定义，按 fail-CLOSED 拒绝开启",
                    snapshot.snapshotId(), Map.of(), List.of(), false));
        }

        if (!rule.enabled()) {
            return finish(new EvaluationResult(flagKey, visitorId, false,
                    EvaluationReason.MASTER_SWITCH_OFF,
                    "开关总开关 enabled=false",
                    snapshot.snapshotId(), origins, List.of(), false));
        }

        if (visitorId != null && rule.allowList().contains(visitorId)) {
            return finish(new EvaluationResult(flagKey, visitorId, true,
                    EvaluationReason.ALLOWLIST_MATCH,
                    "访问者命中白名单，直接放量",
                    snapshot.snapshotId(), origins, List.of(), false));
        }

        if (!rule.targeting().isEmpty()) {
            Map<String, String> attrs = context == null || context.attributes() == null
                    ? Map.of() : context.attributes();
            for (Map.Entry<String, String> t : rule.targeting().entrySet()) {
                String actual = attrs.get(t.getKey());
                if (!t.getValue().equals(actual)) {
                    return finish(new EvaluationResult(flagKey, visitorId, false,
                            EvaluationReason.TARGETING_NOT_MATCHED,
                            "定向条件未命中: " + t.getKey() + " 期望=[" + t.getValue()
                                    + "] 实际=[" + actual + "]",
                            snapshot.snapshotId(), origins, List.of(), false));
                }
            }
        }

        int bucket = stableBucket(rule.salt(), visitorId);
        boolean hit = bucket < rule.percentage();
        String detail = "稳定分桶: salt=" + rule.salt() + ", visitorId=" + visitorId
                + ", bucket=" + bucket + ", percentage=" + rule.percentage()
                + " => " + (hit ? "命中(bucket<percentage)" : "未命中(bucket>=percentage)");
        return finish(new EvaluationResult(flagKey, visitorId, hit,
                EvaluationReason.PERCENTAGE_BUCKETED,
                detail, snapshot.snapshotId(), origins, List.of(), false));
    }

    /** 提取本开关相关原始键的来源出处，并附带 kill.switch 的出处（若存在）。 */
    private Map<String, String> originsFor(String flagKey, ConfigSnapshot snapshot) {
        if (snapshot == null || snapshot.fieldOrigins() == null
                || snapshot.fieldOrigins().isEmpty()) {
            return Map.of();
        }
        Map<String, String> picked = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : snapshot.fieldOrigins().entrySet()) {
            if (e.getKey().startsWith(flagKey + ".")) {
                picked.put(e.getKey().substring(flagKey.length() + 1), e.getValue());
            } else if ("kill.switch".equals(e.getKey())) {
                picked.put("kill.switch", e.getValue());
            }
        }
        return picked;
    }

    private static String describeDisables(List<DisableDirective> disables) {
        StringBuilder sb = new StringBuilder("；命中停用指令 ").append(disables.size()).append(" 条: ");
        for (int i = 0; i < disables.size(); i++) {
            DisableDirective d = disables.get(i);
            if (i > 0) {
                sb.append(" | ");
            }
            sb.append("{id=").append(d.id())
                    .append(", reason=").append(d.reason())
                    .append(", operator=").append(d.operator())
                    .append(", createdAt=").append(d.createdAtMillis())
                    .append(", expireAt=")
                    .append(d.permanent() ? "never" : String.valueOf(d.expireAtMillis()))
                    .append(", baseSnapshot=")
                    .append(d.baseSnapshotId() == null ? "<none>" : d.baseSnapshotId())
                    .append('}');
        }
        return sb.toString();
    }

    /**
     * 稳定分桶：HMAC 风格拼接后取 SHA-256，映射到 [0,100)。
     * 同一 (salt, visitorId) 永远得到同一桶位，与当前放量比例无关。
     */
    private int stableBucket(String salt, String visitorId) {
        String input = salt + "|" + (visitorId == null ? "" : visitorId);
        byte[] digest = sha256(input.getBytes(StandardCharsets.UTF_8));
        // 取前 4 字节为无符号整数，再 mod 100
        long n = ((long) (digest[0] & 0xff) << 24)
                | ((long) (digest[1] & 0xff) << 16)
                | ((long) (digest[2] & 0xff) << 8)
                | (digest[3] & 0xff);
        return (int) (n % 100);
    }

    private byte[] sha256(byte[] input) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(input);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private EvaluationResult finish(EvaluationResult r) {
        log.info("[FLAG-EVAL] snapshot={} flag={} visitor={} decision={} reason={}"
                        + " disables={} origins={} | {}",
                r.snapshotId(), r.flagKey(), r.visitorId(),
                r.enabled() ? "ON" : "OFF", r.reason(),
                r.disableDirectives().stream().map(DisableDirective::id).toList(),
                r.fieldOrigins(), r.detail());
        return r;
    }
}
