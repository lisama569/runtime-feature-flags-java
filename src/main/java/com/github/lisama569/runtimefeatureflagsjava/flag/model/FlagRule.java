package com.github.lisama569.runtimefeatureflagsjava.flag.model;

import java.util.Map;
import java.util.Set;

/**
 * 合并后单个开关的最终规则（不可变）。
 * <p>
 * {@code fieldOrigins} 记录每个生效字段分别来自哪个配置来源
 * （取默认值的字段标记为 {@code <default>}），用于判定日志对账。
 */
public record FlagRule(
        String key,
        boolean enabled,
        int percentage,
        Set<String> allowList,
        Map<String, String> targeting,
        String salt,
        Map<String, String> fieldOrigins) {
}
