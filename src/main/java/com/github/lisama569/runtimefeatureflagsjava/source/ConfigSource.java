package com.github.lisama569.runtimefeatureflagsjava.source;

/** 配置来源抽象：实现方负责加载原始数据，加载失败必须抛出 SourceLoadException。 */
public interface ConfigSource {

    /** 来源名称，用于 provenance 与拒绝记录。 */
    String name();

    /** 加载原始配置数据。 */
    RawSourceData load() throws SourceLoadException;
}
