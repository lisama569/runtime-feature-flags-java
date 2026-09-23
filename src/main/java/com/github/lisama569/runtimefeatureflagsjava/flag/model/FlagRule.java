package com.github.lisama569.runtimefeatureflagsjava.flag.model;

import java.util.Map;
import java.util.Set;

/**
 * 合并后单个开关的最终规则（不可变）。
 */
public record FlagRule(
        String key,
        boolean enabled,
        int percentage,
        Set<String> allowList,
        Map<String, String> targeting,
        String salt) {
}
