package com.github.lisama569.runtimefeatureflagsjava.source;

import java.util.Properties;

/** 来源加载出的原始数据，合并前不做任何解释。 */
public record RawSourceData(String sourceName, Properties properties) {
}
