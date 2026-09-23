package com.github.lisama569.runtimefeatureflagsjava.model;

import java.util.HashMap;
import java.util.Map;

/** 请求上下文：访问者标识 + 定向属性（region/tenant 等）。 */
public final class RequestContext {
    private final String visitorId;
    private final Map<String, String> attributes = new HashMap<>();

    public RequestContext(String visitorId) {
        this.visitorId = visitorId;
    }

    public RequestContext with(String attribute, String value) {
        attributes.put(attribute, value);
        return this;
    }

    public String getVisitorId() { return visitorId; }
    public Map<String, String> getAttributes() { return attributes; }
}
