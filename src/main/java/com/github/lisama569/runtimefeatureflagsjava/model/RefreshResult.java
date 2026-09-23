package com.github.lisama569.runtimefeatureflagsjava.model;

import java.util.List;

/**
 * 一次配置刷新的结果。
 *
 * <p>两类需要区分的记录：</p>
 * <ul>
 *   <li>{@code rejections}：硬拒绝（必需来源缺失/不可读、取值非法）。只要非空，本次刷新被整体拒绝，
 *       调用方继续使用上一份可用快照；</li>
 *   <li>{@code warnings}：非阻断记录（可选来源缺失），本次仍产生了新快照，但原因同样可区分可追查。</li>
 * </ul>
 */
public record RefreshResult(boolean success, String snapshotId, long version,
                            List<Rejection> rejections, List<Rejection> warnings, String message) {

    public static RefreshResult accepted(FlagSnapshot snapshot, List<Rejection> warnings) {
        return new RefreshResult(true, snapshot.getSnapshotId(), snapshot.getVersion(),
                List.of(), List.copyOf(warnings),
                warnings.isEmpty() ? "refresh accepted"
                        : "refresh accepted with " + warnings.size() + " non-blocking warning(s)");
    }

    public static RefreshResult rejected(List<Rejection> rejections) {
        return new RefreshResult(false, null, -1,
                List.copyOf(rejections), List.of(),
                "refresh REJECTED, previous usable snapshot retained");
    }
}
