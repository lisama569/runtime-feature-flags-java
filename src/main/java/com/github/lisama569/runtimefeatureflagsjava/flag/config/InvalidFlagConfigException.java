package com.github.lisama569.runtimefeatureflagsjava.flag.config;

/**
 * 配置取值非法时抛出：问题类型 + 原始键值，整次刷新将被拒绝。
 * <p>
 * 拒绝原因细分（{@link ConfigProblemType}），保证日志与测试可以区分
 * "缺字段 / 布尔非法 / 整数非法 / 越界 / 键名非法 / 未知字段"，
 * 而不是静默回退到默认值。
 */
public class InvalidFlagConfigException extends RuntimeException {

    private final ConfigProblemType problemType;
    private final String rawKey;
    private final String rawValue;

    public InvalidFlagConfigException(ConfigProblemType problemType,
                                      String rawKey,
                                      String rawValue,
                                      String message) {
        super(message);
        this.problemType = problemType;
        this.rawKey = rawKey;
        this.rawValue = rawValue;
    }

    public ConfigProblemType problemType() {
        return problemType;
    }

    public String rawKey() {
        return rawKey;
    }

    public String rawValue() {
        return rawValue;
    }
}
