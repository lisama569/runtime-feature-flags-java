package com.github.lisama569.runtimefeatureflagsjava.flag.source;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 可热更新的内存配置来源（测试与模拟远端配置中心用）。
 * <p>
 * 通过 {@link #setProperties(Map)} 在运行时替换配置；
 * 通过 {@link #setUnavailable(boolean)} 模拟来源缺失/不可达。
 * 读取时对内部快照做防御性拷贝，保证读取出的 Map 稳定，
 * 与外部并发写入互不干扰。
 */
public class InMemoryConfigSource implements ConfigSource {

    private final String name;
    private final int priority;

    private volatile Map<String, String> properties = Map.of();
    private volatile boolean unavailable = false;

    public InMemoryConfigSource(String name, int priority) {
        this.name = name;
        this.priority = priority;
    }

    /** 原子替换该来源的全部配置（模拟远端推送/热更新）。 */
    public void setProperties(Map<String, String> next) {
        this.properties = Map.copyOf(next);
    }

    /** 置为 true 后 load() 抛出 {@link SourceUnavailableException}，模拟来源缺失。 */
    public void setUnavailable(boolean unavailable) {
        this.unavailable = unavailable;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public int priority() {
        return priority;
    }

    @Override
    public Map<String, String> load() throws SourceUnavailableException {
        if (unavailable) {
            throw new SourceUnavailableException(name,
                    "配置来源不可达或缺失: source=" + name);
        }
        // 防御性拷贝：调用方拿到的是独立可变 Map，合并时可安全使用
        return new LinkedHashMap<>(properties);
    }
}
