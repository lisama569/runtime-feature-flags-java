package com.github.lisama569.runtimefeatureflagsjava.flag.eval;

import com.github.lisama569.runtimefeatureflagsjava.flag.model.ConfigSnapshot;
import com.github.lisama569.runtimefeatureflagsjava.flag.model.EvaluationReason;
import com.github.lisama569.runtimefeatureflagsjava.flag.model.EvaluationResult;
import com.github.lisama569.runtimefeatureflagsjava.flag.model.FlagRule;
import com.github.lisama569.runtimefeatureflagsjava.flag.model.RequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 基于单一不可变快照完成开关判定（确定性、可复现、可解释）。
 * <p>
 * 判定顺序（短路）：
 * <ol>
 *   <li>无已加载快照 → fail-CLOSED（拒绝开启），原因 NO_CONFIG_LOADED</li>
 *   <li>全局总闸 kill.switch=true → 一律 false，原因 KILL_SWITCH_ACTIVE</li>
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

    public EvaluationResult evaluate(String flagKey, RequestContext context, ConfigSnapshot snapshot) {
        String visitorId = context == null ? null : context.visitorId();
        if (snapshot == null) {
            // 兜底语义：从未成功加载过任何配置时，fail-CLOSED
            return finish(new EvaluationResult(flagKey, visitorId, false,
                    EvaluationReason.NO_CONFIG_LOADED,
                    "尚未加载任何配置快照，按 fail-CLOSED 拒绝开启", null));
        }

        if (snapshot.killSwitch()) {
            return finish(new EvaluationResult(flagKey, visitorId, false,
                    EvaluationReason.KILL_SWITCH_ACTIVE,
                    "全局总闸 kill.switch 已开启，所有开关强制关闭",
                    snapshot.snapshotId()));
        }

        FlagRule rule = snapshot.flags().get(flagKey);
        if (rule == null) {
            // 兜底语义：开关未定义，fail-CLOSED（不会"默认开启"）
            return finish(new EvaluationResult(flagKey, visitorId, false,
                    EvaluationReason.FLAG_NOT_FOUND,
                    "快照中不存在该开关定义，按 fail-CLOSED 拒绝开启",
                    snapshot.snapshotId()));
        }

        if (!rule.enabled()) {
            return finish(new EvaluationResult(flagKey, visitorId, false,
                    EvaluationReason.MASTER_SWITCH_OFF,
                    "开关总开关 enabled=false",
                    snapshot.snapshotId()));
        }

        if (visitorId != null && rule.allowList().contains(visitorId)) {
            return finish(new EvaluationResult(flagKey, visitorId, true,
                    EvaluationReason.ALLOWLIST_MATCH,
                    "访问者命中白名单，直接放量",
                    snapshot.snapshotId()));
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
                            snapshot.snapshotId()));
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
                detail, snapshot.snapshotId()));
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

    private EvaluationResult finish(EvaluationResult r) {
        log.info("[FLAG-EVAL] snapshot={} flag={} visitor={} decision={} reason={} | {}",
                r.snapshotId(), r.flagKey(), r.visitorId(),
                r.enabled() ? "ON" : "OFF", r.reason(), r.detail());
        return r;
    }
}
