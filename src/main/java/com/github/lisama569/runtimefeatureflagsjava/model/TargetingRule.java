package com.github.lisama569.runtimefeatureflagsjava.model;

/** 定向条件：请求上下文中的指定属性必须等于期望值（多条之间为 AND）。 */
public record TargetingRule(String attribute, String expected) {
}
