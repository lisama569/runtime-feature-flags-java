package com.github.lisama569.runtimefeatureflagsjava.flag.config;

import com.github.lisama569.runtimefeatureflagsjava.flag.model.FlagRule;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.TreeMap;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 将合并后的原始键值对解析/校验为开关规则。
 * <p>
 * 约定的原始键格式（{@code feature.<flagKey>.<field>}）：
 * <ul>
 *   <li>{@code feature.f.enabled}：必填，true/false（缺失=拒绝，缺省值不兜底）</li>
 *   <li>{@code feature.f.percentage}：选填，0..100 整数，默认 0（显式安全缺省，非非法回退）</li>
 *   <li>{@code feature.f.allowlist}：选填，逗号分隔的访问者白名单，默认空</li>
 *   <li>{@code feature.f.target.<attr>}：选填，定向属性名=期望值，可多条，全部命中才放量</li>
 *   <li>{@code feature.f.salt}：选填，分桶盐值，默认取 flagKey</li>
 * </ul>
 * 任何字段取值非法都抛 {@link InvalidFlagConfigException}，由组装层拒绝整份快照，
 * 绝不静默使用默认值顶替非法输入。
 */
public class FlagConfigParser {

    private static final String PREFIX = "feature.";

    public Map<String, FlagRule> parse(Map<String, String> raw) {
        // 按 flagKey -> fieldPath(去掉 feature.<key>. 前缀) -> value 归集
        Map<String, Map<String, String>> grouped = new TreeMap<>();
        for (Map.Entry<String, String> e : new TreeMap<>(raw).entrySet()) {
            String key = e.getKey();
            String value = e.getValue();
            if (key == null || !key.startsWith(PREFIX)) {
                // 非 feature.* 的键由组装层负责（如 kill.switch），这里不处理
                continue;
            }
            String rest = key.substring(PREFIX.length());
            int dot = rest.indexOf('.');
            String nameSegment = dot < 0 ? rest : rest.substring(0, dot);
            String field = dot < 0 ? "" : rest.substring(dot + 1);
            if (nameSegment.isBlank() || !nameSegment.matches("[A-Za-z0-9_\\-]+")) {
                throw new InvalidFlagConfigException(ConfigProblemType.INVALID_KEY,
                        key, value, "非法开关键名: " + key);
            }
            if (field.isBlank()) {
                throw new InvalidFlagConfigException(ConfigProblemType.INVALID_KEY,
                        key, value, "缺少字段名: " + key);
            }
            // 开关的完整名保留 "feature.<name>"，与配置键一致，便于解释
            String flagKey = key.substring(0, PREFIX.length() + dot);
            grouped.computeIfAbsent(flagKey, k -> new TreeMap<>()).put(field, value);
        }

        Map<String, FlagRule> rules = new TreeMap<>();
        for (Map.Entry<String, Map<String, String>> g : grouped.entrySet()) {
            rules.put(g.getKey(), parseOne(g.getKey(), g.getValue()));
        }
        return Map.copyOf(rules);
    }

    private FlagRule parseOne(String flagKey, Map<String, String> fields) {
        // flagKey 形如 feature.<name>；错误信息里统一使用完整键
        // enabled 必填
        if (!fields.containsKey("enabled")) {
            throw new InvalidFlagConfigException(ConfigProblemType.MISSING_FIELD,
                    flagKey + ".enabled", null,
                    "开关 " + flagKey + " 缺少必填字段 enabled");
        }
        boolean enabled = parseBoolean(flagKey, "enabled", fields.get("enabled"));

        int percentage = 0;
        if (fields.containsKey("percentage")) {
            percentage = parsePercentage(flagKey, fields.get("percentage"));
        }

        Set<String> allowList = Set.of();
        if (fields.containsKey("allowlist")) {
            String v = fields.get("allowlist");
            if (v != null && !v.isBlank()) {
                allowList = Arrays.stream(v.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .collect(Collectors.toCollection(LinkedHashSet::new));
            }
        }

        Map<String, String> targeting = new TreeMap<>();
        String salt = flagKey.substring(PREFIX.length());
        for (Map.Entry<String, String> e : fields.entrySet()) {
            String field = e.getKey();
            String value = e.getValue();
            if (field.equals("enabled") || field.equals("percentage")
                    || field.equals("allowlist") || field.equals("salt")) {
                continue;
            }
            if (field.startsWith("target.")) {
                String attr = field.substring("target.".length());
                if (attr.isBlank() || !attr.matches("[A-Za-z0-9_\\-]+")) {
                    throw new InvalidFlagConfigException(ConfigProblemType.INVALID_KEY,
                            flagKey + "." + field, value,
                            "非法定向属性名: " + field);
                }
                targeting.put(attr, value == null ? "" : value);
                continue;
            }
            // 未知字段：显式拒绝，避免脏配置被静默忽略
            throw new InvalidFlagConfigException(ConfigProblemType.UNKNOWN_FIELD,
                    flagKey + "." + field, value,
                    "开关 " + flagKey + " 含未知字段: " + field);
        }
        if (fields.containsKey("salt")) {
            String s = fields.get("salt");
            if (s != null && !s.isBlank()) {
                salt = s.trim();
            }
        }

        return new FlagRule(flagKey, enabled, percentage,
                Set.copyOf(allowList),
                Map.copyOf(targeting), salt);
    }

    private boolean parseBoolean(String flagKey, String field, String raw) {
        if (raw == null || raw.isBlank()) {
            throw new InvalidFlagConfigException(ConfigProblemType.INVALID_BOOLEAN,
                    flagKey + "." + field, raw,
                    "开关 " + flagKey + " 的 " + field + " 不是合法布尔值(空)");
        }
        String v = raw.trim();
        if ("true".equalsIgnoreCase(v)) {
            return true;
        }
        if ("false".equalsIgnoreCase(v)) {
            return false;
        }
        throw new InvalidFlagConfigException(ConfigProblemType.INVALID_BOOLEAN,
                flagKey + "." + field, raw,
                "开关 " + flagKey + " 的 " + field + " 不是合法布尔值: " + raw);
    }

    private int parsePercentage(String flagKey, String raw) {
        String key = PREFIX + flagKey + ".percentage";
        if (raw == null || raw.isBlank()) {
            throw new InvalidFlagConfigException(ConfigProblemType.INVALID_INTEGER,
                    key, raw, "开关 " + flagKey + " 的 percentage 不是整数(空)");
        }
        int p;
        try {
            p = Integer.parseInt(raw.trim());
        } catch (NumberFormatException ex) {
            throw new InvalidFlagConfigException(ConfigProblemType.INVALID_INTEGER,
                    key, raw, "开关 " + flagKey + " 的 percentage 不是整数: " + raw);
        }
        if (p < 0 || p > 100) {
            throw new InvalidFlagConfigException(ConfigProblemType.INVALID_PERCENTAGE_RANGE,
                    key, raw, "开关 " + flagKey + " 的 percentage 超出 0..100: " + raw);
        }
        return p;
    }
}
