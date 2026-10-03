package com.github.lisama569.runtimefeatureflagsjava.flag.disable;

import java.util.List;

/**
 * 某开关当前生效停用的合并视图（多条指令命中同一开关时的"说得清"合并结果）。
 * <p>
 * 合并规则：
 * <ul>
 *   <li>{@code directiveIds}：全部命中指令 id，按创建时间升序；</li>
 *   <li>{@code reason}/{@code operator}：各指令取值去重后以 " + " 连接，来源可追溯；</li>
 *   <li>{@code createdAtMillis}：取最早一条（停用起点）；</li>
 *   <li>{@code expiresAtMillis}：取最晚到期；任一指令为永久（null）则整体永久；</li>
 *   <li>{@code baseSnapshotId}：取最早一条指令生效时基于的配置快照。</li>
 * </ul>
 */
public record DisableView(
        String flagKey,
        List<String> directiveIds,
        String reason,
        String operator,
        long createdAtMillis,
        Long expiresAtMillis,
        String baseSnapshotId) {

    /** 合并后的停用标识（用于判定结果与日志对账），形如 "d-1+d-2"。 */
    public String mergedId() {
        return String.join("+", directiveIds);
    }
}
