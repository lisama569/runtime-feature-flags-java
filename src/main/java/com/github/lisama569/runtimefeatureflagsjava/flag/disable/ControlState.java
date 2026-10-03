package com.github.lisama569.runtimefeatureflagsjava.flag.disable;

import com.github.lisama569.runtimefeatureflagsjava.flag.model.ConfigSnapshot;

/**
 * 判定所需的完整控制面状态（不可变）：配置快照 + 停用集合。
 * <p>
 * 两者被放进同一个不可变对象整体原子发布：读请求任意时刻拿到的都是
 * 一对完整一致的 (快照, 停用集合)，绝不会出现"新快照配旧停用"这类半生效状态。
 */
public record ControlState(ConfigSnapshot snapshot, DisableSet disables) {

    public static ControlState initial() {
        return new ControlState(null, DisableSet.empty());
    }

    public ControlState withSnapshot(ConfigSnapshot next) {
        return new ControlState(next, disables);
    }

    public ControlState withDisables(DisableSet next) {
        return new ControlState(snapshot, next);
    }
}
