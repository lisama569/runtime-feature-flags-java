package com.github.lisama569.runtimefeatureflagsjava.model;

import java.util.List;
import java.util.Map;

/** 单个开关在合并后的最终定义；provenance 记录每个字段来自哪个来源。 */
public final class FlagDefinition {
    private final String key;
    private final boolean enabled;
    private final List<String> allowlist;
    private final List<String> blocklist;
    private final int percentage;
    private final List<TargetingRule> targetingRules;
    private final Map<String, String> provenance;

    public FlagDefinition(String key, boolean enabled, List<String> allowlist, List<String> blocklist,
                          int percentage, List<TargetingRule> targetingRules, Map<String, String> provenance) {
        this.key = key;
        this.enabled = enabled;
        this.allowlist = allowlist;
        this.blocklist = blocklist;
        this.percentage = percentage;
        this.targetingRules = targetingRules;
        this.provenance = provenance;
    }

    public String getKey() { return key; }
    public boolean isEnabled() { return enabled; }
    public List<String> getAllowlist() { return allowlist; }
    public List<String> getBlocklist() { return blocklist; }
    public int getPercentage() { return percentage; }
    public List<TargetingRule> getTargetingRules() { return targetingRules; }
    public Map<String, String> getProvenance() { return provenance; }
}
