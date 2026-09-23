package com.github.lisama569.runtimefeatureflagsjava.flag.source;

import java.util.Map;

/**
 * 配置来源：优先级数值越大优先级越高。
 */
public interface ConfigSource {

    String name();

    int priority();

    Map<String, String> load() throws SourceUnavailableException;
}
