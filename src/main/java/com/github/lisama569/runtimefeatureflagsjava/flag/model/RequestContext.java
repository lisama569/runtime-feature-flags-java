package com.github.lisama569.runtimefeatureflagsjava.flag.model;

import java.util.Map;

/**
 * 一次开关判定的请求上下文：稳定的访问者标识 + 定向属性。
 */
public record RequestContext(String visitorId, Map<String, String> attributes) {
}
