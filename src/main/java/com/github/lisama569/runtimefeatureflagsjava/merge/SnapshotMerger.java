package com.github.lisama569.runtimefeatureflagsjava.merge;

import com.github.lisama569.runtimefeatureflagsjava.model.FlagDefinition;
import com.github.lisama569.runtimefeatureflagsjava.model.FlagSnapshot;
import com.github.lisama569.runtimefeatureflagsjava.model.Rejection;
import com.github.lisama569.runtimefeatureflagsjava.model.RejectionKind;
import com.github.lisama569.runtimefeatureflagsjava.model.TargetingRule;
import com.github.lisama569.runtimefeatureflagsjava.source.RawSourceData;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 将多个来源（按优先级从低到高）合并为单个不可变快照。
 *
 * <p>合并规则：</p>
 * <ol>
 *   <li>逐字段合并：每个字段取“定义了该字段的最高优先级来源”的值；</li>
 *   <li>高优先级来源定义了该字段但取值非法 → 直接拒绝整次刷新，
 *       <b>不会</b>静默回退到低优先级取值或旧配置；</li>
 *   <li>每个最终取值记录来源（provenance），使判定结论可解释；</li>
 *   <li>全局 kill switch 与各开关独立解析，任何一个非法值都会拒绝整次刷新。</li>
 * </ol>
 */
@org.springframework.stereotype.Component
public class SnapshotMerger {

    static final String GLOBAL_KILL_SWITCH = "global.kill-switch";
    private static final Pattern FLAG_KEY_PATTERN =
            Pattern.compile("flag\\.([A-Za-z0-9][A-Za-z0-9_.-]*)\\.(enabled|percentage|allowlist|blocklist|targeting)\\.(.*)");
    private static final Pattern SCALAR_PATTERN =
            Pattern.compile("flag\\.([A-Za-z0-9][A-Za-z0-9_.-]*)\\.(enabled|percentage|allowlist|blocklist)");

    private static final List<String> KNOWN_SUFFIXES = List.of(
            "enabled", "percentage", "allowlist", "blocklist", "targeting");

    public MergeOutcome merge(List<RawSourceData> sourcesInPriorityOrder, long version) {
        List<Rejection> rejections = new ArrayList<>();
        // sourcesInPriorityOrder: index 0 优先级最低，末尾最高
        List<RawSourceData> ordered = sourcesInPriorityOrder == null ? List.of() : sourcesInPriorityOrder;

        // 1) 逐来源扫描，收集所有 flag key 与全局字段，校验键名合法性
        LinkedHashSet<String> flagKeys = new LinkedHashSet<>();
        for (RawSourceData source : ordered) {
            for (String rawKey : source.properties().stringPropertyNames()) {
                if (GLOBAL_KILL_SWITCH.equals(rawKey)) {
                    continue;
                }
                Matcher scalar = SCALAR_PATTERN.matcher(rawKey);
                if (scalar.matches()) {
                    flagKeys.add(scalar.group(1));
                    continue;
                }
                Matcher compound = FLAG_KEY_PATTERN.matcher(rawKey);
                if (compound.matches() && "targeting".equals(compound.group(2))) {
                    flagKeys.add(compound.group(1));
                    continue;
                }
                rejectIfUnknownKey(rawKey, source.sourceName(), rejections);
            }
        }

        // 2) 全局 kill switch：取最高优先级的定义
        boolean globalKill = false;
        boolean globalKillDefined = false;
        for (int i = ordered.size() - 1; i >= 0 && !globalKillDefined; i--) {
            Properties p = ordered.get(i).properties();
            if (p.containsKey(GLOBAL_KILL_SWITCH)) {
                String raw = p.getProperty(GLOBAL_KILL_SWITCH).trim();
                if (isBoolean(raw)) {
                    globalKill = Boolean.parseBoolean(raw);
                    globalKillDefined = true;
                } else {
                    rejections.add(new Rejection(ordered.get(i).sourceName(), RejectionKind.INVALID_VALUE,
                            "key '" + GLOBAL_KILL_SWITCH + "' expects true|false but was '" + raw + "'"));
                }
            }
        }

        // 3) 逐开关逐字段合并
        Map<String, FlagDefinition> definitions = new LinkedHashMap<>();
        for (String flagKey : flagKeys) {
            FlagDefinition def = mergeFlag(flagKey, ordered, rejections);
            if (def != null) {
                definitions.put(flagKey, def);
            }
        }

        if (!rejections.isEmpty()) {
            return new MergeOutcome(null, rejections);
        }

        String snapshotId = "snap-v" + version + "-" + Instant.now().toEpochMilli();
        return new MergeOutcome(
                new FlagSnapshot(snapshotId, version, System.currentTimeMillis(), globalKill,
                        Map.copyOf(definitions)),
                List.of());
    }

    private FlagDefinition mergeFlag(String flagKey, List<RawSourceData> ordered, List<Rejection> rejections) {
        boolean enabled = false;
        int percentage = 0;
        List<String> allowlist = List.of();
        List<String> blocklist = List.of();
        List<TargetingRule> targeting = List.of();
        Map<String, String> provenance = new LinkedHashMap<>();

        for (int i = ordered.size() - 1; i >= 0; i--) {
            RawSourceData source = ordered.get(i);
            Properties p = source.properties();

            String enabledKey = "flag." + flagKey + ".enabled";
            if (!provenance.containsKey("enabled") && p.containsKey(enabledKey)) {
                String raw = p.getProperty(enabledKey).trim();
                if (isBoolean(raw)) {
                    enabled = Boolean.parseBoolean(raw);
                    provenance.put("enabled", source.sourceName());
                } else {
                    rejections.add(new Rejection(source.sourceName(), RejectionKind.INVALID_VALUE,
                            "key '" + enabledKey + "' expects true|false but was '" + raw + "'"));
                }
            }

            String pctKey = "flag." + flagKey + ".percentage";
            if (!provenance.containsKey("percentage") && p.containsKey(pctKey)) {
                String raw = p.getProperty(pctKey).trim();
                Integer value = parsePercentage(raw);
                if (value != null) {
                    percentage = value;
                    provenance.put("percentage", source.sourceName());
                } else {
                    rejections.add(new Rejection(source.sourceName(), RejectionKind.INVALID_VALUE,
                            "key '" + pctKey + "' expects integer in [0,10000] (basis points) but was '" + raw + "'"));
                }
            }

            String allowKey = "flag." + flagKey + ".allowlist";
            if (!provenance.containsKey("allowlist") && p.containsKey(allowKey)) {
                String raw = p.getProperty(allowKey).trim();
                List<String> parsed = parseList(allowKey, raw, source.sourceName(), rejections);
                if (parsed != null) {
                    allowlist = parsed;
                    provenance.put("allowlist", source.sourceName());
                }
            }

            String blockKey = "flag." + flagKey + ".blocklist";
            if (!provenance.containsKey("blocklist") && p.containsKey(blockKey)) {
                String raw = p.getProperty(blockKey).trim();
                List<String> parsed = parseList(blockKey, raw, source.sourceName(), rejections);
                if (parsed != null) {
                    blocklist = parsed;
                    provenance.put("blocklist", source.sourceName());
                }
            }
        }

        // targeting 规则按来源层级覆盖（高优先级来源定义了任意 targeting.* 即整体覆盖）
        targeting = mergeTargeting(flagKey, ordered, rejections, provenance);

        // 同一名单同时出现在 allowlist 与 blocklist 是矛盾配置：拒绝而非静默择一
        for (String id : allowlist) {
            if (blocklist.contains(id)) {
                rejections.add(new Rejection("(merged)", RejectionKind.INVALID_VALUE,
                        "flag '" + flagKey + "' visitor '" + id + "' appears in both allowlist and blocklist"));
            }
        }

        return new FlagDefinition(flagKey, enabled, List.copyOf(allowlist), List.copyOf(blocklist),
                percentage, targeting, Map.copyOf(provenance));
    }

    private List<TargetingRule> mergeTargeting(String flagKey, List<RawSourceData> ordered,
                                               List<Rejection> rejections, Map<String, String> provenance) {
        String prefix = "flag." + flagKey + ".targeting.";
        for (int i = ordered.size() - 1; i >= 0; i--) {
            RawSourceData source = ordered.get(i);
            List<String> attrs = new ArrayList<>();
            for (String key : source.properties().stringPropertyNames()) {
                if (key.startsWith(prefix)) {
                    attrs.add(key.substring(prefix.length()));
                }
            }
            if (!attrs.isEmpty()) {
                List<TargetingRule> rules = new ArrayList<>();
                for (String attr : attrs) {
                    if (attr.isBlank() || !attr.matches("[A-Za-z0-9_-]+")) {
                        rejections.add(new Rejection(source.sourceName(), RejectionKind.INVALID_VALUE,
                                "key '" + prefix + attr + "' has illegal targeting attribute name"));
                        continue;
                    }
                    String expected = source.properties().getProperty(prefix + attr).trim();
                    if (expected.isEmpty()) {
                        rejections.add(new Rejection(source.sourceName(), RejectionKind.INVALID_VALUE,
                                "key '" + prefix + attr + "' value must not be blank"));
                        continue;
                    }
                    rules.add(new TargetingRule(attr, expected));
                }
                provenance.put("targeting", source.sourceName());
                return List.copyOf(rules);
            }
        }
        return List.of();
    }

    private void rejectIfUnknownKey(String rawKey, String sourceName, List<Rejection> rejections) {
        boolean looksLikeFlag = rawKey.startsWith("flag.");
        boolean knownShape = false;
        if (looksLikeFlag) {
            String remainder = rawKey.substring("flag.".length());
            int dot = remainder.indexOf('.');
            if (dot > 0) {
                String suffix = remainder.substring(dot + 1);
                for (String known : KNOWN_SUFFIXES) {
                    if (suffix.equals(known) || suffix.startsWith(known + ".")) {
                        knownShape = true;
                        break;
                    }
                }
            }
        }
        rejections.add(new Rejection(sourceName, RejectionKind.INVALID_VALUE,
                "unknown or malformed configuration key '" + rawKey + "'"
                        + (looksLikeFlag && !knownShape ? " (expected one of enabled/percentage/allowlist/blocklist/targeting[.attr])" : "")));
    }

    private static boolean isBoolean(String raw) {
        return "true".equalsIgnoreCase(raw) || "false".equalsIgnoreCase(raw);
    }

    private static Integer parsePercentage(String raw) {
        try {
            int value = Integer.parseInt(raw);
            return (value >= 0 && value <= 10_000) ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static List<String> parseList(String key, String raw, String sourceName, List<Rejection> rejections) {
        List<String> items = Arrays.stream(raw.split(","))
                .map(String::trim)
                .collect(Collectors.toList());
        for (String item : items) {
            if (item.isEmpty()) {
                rejections.add(new Rejection(sourceName, RejectionKind.INVALID_VALUE,
                        "key '" + key + "' contains an empty list entry in '" + raw + "'"));
                return null;
            }
        }
        return items;
    }
}
