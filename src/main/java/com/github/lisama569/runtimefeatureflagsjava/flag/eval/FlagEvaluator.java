package com.github.lisama569.runtimefeatureflagsjava.flag.eval;

import com.github.lisama569.runtimefeatureflagsjava.flag.disable.DisableSet;
import com.github.lisama569.runtimefeatureflagsjava.flag.disable.DisableView;
import com.github.lisama569.runtimefeatureflagsjava.flag.model.ConfigSnapshot;
import com.github.lisama569.runtimefeatureflagsjava.flag.model.EvaluationReason;
import com.github.lisama569.runtimefeatureflagsjava.flag.model.EvaluationResult;
import com.github.lisama569.runtimefeatureflagsjava.flag.model.FlagRule;
import com.github.lisama569.runtimefeatureflagsjava.flag.model.RequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

/**
 * 基于单一不可变快照 + 一致停用集合完成开关判定（确定性、可复现、可解释）。
 * <p>
 * 判定顺序（短路）：
 * <ol>
 *   <li>无已加载快照 → fail-CLOSED（拒绝开启），原因 NO_CONFIG_LOADED</li>
 *   <li>全局总闸 kill.switch=true → 一律 false，原因 KILL_SWITCH_ACTIVE；
 *       若该开关同时命中局部停用，结果中 disableId 一并带出（两层归因可区分，
 *       全局层优先）</li>
 *   <li>命中生效中的局部停用 → false，原因 FLAG_TEMPORARILY_DISABLED，
 *       disableId 记录命中的（合并）停用记录</li>
 *   <li>开关不存在 → fail-CLOSED，原因 FLAG_NOT_FOUND</li>
 *   <li>开关总开关 enabled=false → 一律 false，原因 MASTER_SWITCH_OFF</li>
 *   <li>命中白名单 → true，原因 ALLOWLIST_MATCH（白名单不受放量比例影响）</li>
 *   <li>定向条件存在且未全部命中 → false，原因 TARGETING_NOT_MATCHED</li>
 *   <li>按稳定哈希分桶：bucket &lt; percentage → true，否则 false</li>
 * </ol>
 * <b>粘性</b>：bucket 仅由 salt + visitorId 决定，与 percentage 无关；
 * 放量比例增减只移动阈值，不重排桶位——已命中且 bucket 小于新阈值的访问者
 * 不会反复抖动。
 */
public class FlagEvaluator {

    private static final Logger log = LoggerFactory.getLogger(FlagEvaluator.class);

    /** 兼容入口：无停用集合（等价于无任何停用生效）。 */
    public EvaluationResult evaluate(String flagKey, RequestContext context, ConfigSnapshot snapshot) {
        return evaluate(flagKey, context, snapshot, DisableSet.empty(), System.currentTimeMillis());
    }

    public EvaluationResult evaluate(String flagKey, RequestContext context,
                                     ConfigSnapshot snapshot,
                                     DisableSet disables, long nowMillis) {
        String visitorId = context == null ? null : context.visitorId();
        DisableSet safeDisables = disables == null ? DisableSet.empty() : disables;
        DisableView disable = safeDisables.effectiveFor(flagKey, nowMillis);
        String disableId = disable == null ? null : disable.mergedId();

        if (snapshot == null) {
            // 兜底语义：从未成功加载过任何配置时，fail-CLOSED
            return finish(new EvaluationResult(flagKey, visitorId, false,
                    EvaluationReason.NO_CONFIG_LOADED,
                    "尚未加载任何配置快照，按 fail-CLOSED 拒绝开启", null, disableId), null);
        }

        // 提前取出规则：即使被总闸/局部停用短路，日志也要能对账各字段出处来源
        FlagRule rule = snapshot.flags().get(flagKey);

        if (snapshot.killSwitch()) {
            // 全局层优先；若局部停用同时命中，归因此处明确标注两层
            String detail = "全局总闸 kill.switch 已开启，所有开关强制关闭（全局层）";
            if (disable != null) {
                detail += "；该开关同时命中局部停用 " + disableId
                        + "（局部层），当前由全局层关闭";
            }
            return finish(new EvaluationResult(flagKey, visitorId, false,
                    EvaluationReason.KILL_SWITCH_ACTIVE, detail,
                    snapshot.snapshotId(), disableId), rule);
        }

        if (disable != null) {
            // 局部停用层：显式恢复或到期之前，任何配置刷新都不得解除
            String detail = "开关被临时停用（局部层）: disable=" + disableId
                    + ", reason=" + disable.reason()
                    + ", operator=" + disable.operator()
                    + ", baseSnapshot=" + disable.baseSnapshotId()
                    + ", expiresAt=" + expiryText(disable);
            return finish(new EvaluationResult(flagKey, visitorId, false,
                    EvaluationReason.FLAG_TEMPORARILY_DISABLED, detail,
                    snapshot.snapshotId(), disableId), rule);
        }

        if (rule == null) {
            // 兜底语义：开关未定义，fail-CLOSED（不会"默认开启"）
            return finish(new EvaluationResult(flagKey, visitorId, false,
                    EvaluationReason.FLAG_NOT_FOUND,
                    "快照中不存在该开关定义，按 fail-CLOSED 拒绝开启",
                    snapshot.snapshotId()), null);
        }

        if (!rule.enabled()) {
            return finish(new EvaluationResult(flagKey, visitorId, false,
                    EvaluationReason.MASTER_SWITCH_OFF,
                    "开关总开关 enabled=false",
                    snapshot.snapshotId()), rule);
        }

        if (visitorId != null && rule.allowList().contains(visitorId)) {
            return finish(new EvaluationResult(flagKey, visitorId, true,
                    EvaluationReason.ALLOWLIST_MATCH,
                    "访问者命中白名单，直接放量",
                    snapshot.snapshotId()), rule);
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
                            snapshot.snapshotId()), rule);
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
                detail, snapshot.snapshotId()), rule);
    }

    private static String expiryText(DisableView disable) {
        return disable.expiresAtMillis() == null
                ? "永久(需显式恢复)"
                : Instant.ofEpochMilli(disable.expiresAtMillis()).toString();
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
                | ((digest[1] & 0xff) << 16)
                | ((digest[2] & 0xff) << 8)
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

    /**
     * 统一出口：每条判定日志都稳定携带对账信息——
     * 配置快照标识、命中的停用记录、开关各字段出处来源。
     * 显式标记（区别于"来源缺失/未知"）：
     * <ul>
     *   <li>无已加载配置：{@code snapshot=<none>} 且 {@code origins=<no-config>}</li>
     *   <li>快照中未定义该开关：{@code origins=<flag-not-defined>}</li>
     *   <li>取缺省值的字段：在 origins 中标记 {@code <default>}</li>
     * </ul>
     */
    private EvaluationResult finish(EvaluationResult r, FlagRule rule) {
        boolean noConfig = r.snapshotId() == null;
        String snapshotText = noConfig ? "<none>" : r.snapshotId();
        String originsText = noConfig
                ? "<no-config>"
                : (rule == null ? "<flag-not-defined>" : rule.fieldOrigins().toString());
        log.info("[FLAG-EVAL] snapshot={} flag={} visitor={} decision={} reason={} disable={} origins={} | {}",
                snapshotText, r.flagKey(), r.visitorId(),
                r.enabled() ? "ON" : "OFF", r.reason(),
                r.disableId() == null ? "-" : r.disableId(),
                originsText,
                r.detail());
        return r;
    }
}
