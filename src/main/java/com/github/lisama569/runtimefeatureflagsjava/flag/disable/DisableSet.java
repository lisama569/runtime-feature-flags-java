package com.github.lisama569.runtimefeatureflagsjava.flag.disable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 生效中停用指令的不可变集合（按指令 id 索引）。
 * <p>
 * 任何变更都产出一个新实例（写时复制），与配置快照一起经 {@code ControlState}
 * 原子发布：并发读永远看到一份完整一致的停用状态，不会看到"批量停用只生效一半"。
 * <p>
 * 到期采用惰性判定：指令带 {@code expiresAtMillis}，读取/判定/统计容量时
 * 以当前时间过滤已到期条目；到点即自动恢复，无需后台线程。
 */
public final class DisableSet {

    private static final Comparator<DisableDirective> BY_CREATED =
            Comparator.comparingLong(DisableDirective::createdAtMillis)
                    .thenComparing(DisableDirective::id);

    private final Map<String, DisableDirective> byId;

    private DisableSet(Map<String, DisableDirective> byId) {
        this.byId = byId;
    }

    public static DisableSet empty() {
        return new DisableSet(Map.of());
    }

    /** 当前条目数（含已到期但尚未被清理的；容量判断前应先 {@link #pruneExpired(long)}）。 */
    public int size() {
        return byId.size();
    }

    /** 追加一批指令，返回新集合；同 id 覆盖（id 由管理器单调分配，正常不会冲突）。 */
    public DisableSet plusAll(List<DisableDirective> directives) {
        TreeMap<String, DisableDirective> next = new TreeMap<>(byId);
        for (DisableDirective d : directives) {
            next.put(d.id(), d);
        }
        return new DisableSet(Map.copyOf(next));
    }

    /** 移除某开关的全部停用指令（显式恢复），返回新集合。 */
    public DisableSet removeFlag(String flagKey) {
        TreeMap<String, DisableDirective> next = new TreeMap<>(byId);
        next.values().removeIf(d -> d.flagKey().equals(flagKey));
        return new DisableSet(Map.copyOf(next));
    }

    /** 清理已到期指令，返回新集合（用于变更操作前回收容量）。 */
    public DisableSet pruneExpired(long nowMillis) {
        TreeMap<String, DisableDirective> next = new TreeMap<>(byId);
        next.values().removeIf(d -> d.isExpired(nowMillis));
        return new DisableSet(Map.copyOf(next));
    }

    public boolean hasActiveFor(String flagKey, long nowMillis) {
        return effectiveFor(flagKey, nowMillis) != null;
    }

    /**
     * 某开关在当前时刻的生效停用合并视图；无生效指令（或全部已到期）返回 null。
     * 多条指令命中同一开关时按 {@link DisableView} 文档规则合并。
     */
    public DisableView effectiveFor(String flagKey, long nowMillis) {
        List<DisableDirective> hits = new ArrayList<>();
        for (DisableDirective d : byId.values()) {
            if (d.flagKey().equals(flagKey) && !d.isExpired(nowMillis)) {
                hits.add(d);
            }
        }
        return merge(flagKey, hits);
    }

    /** 当前所有生效停用的合并视图（已到期的不出现），按开关名排序。 */
    public List<DisableView> activeViews(long nowMillis) {
        Map<String, List<DisableDirective>> byFlag = new TreeMap<>();
        for (DisableDirective d : byId.values()) {
            if (!d.isExpired(nowMillis)) {
                byFlag.computeIfAbsent(d.flagKey(), k -> new ArrayList<>()).add(d);
            }
        }
        List<DisableView> views = new ArrayList<>();
        for (Map.Entry<String, List<DisableDirective>> e : byFlag.entrySet()) {
            views.add(merge(e.getKey(), e.getValue()));
        }
        return List.copyOf(views);
    }

    private static DisableView merge(String flagKey, List<DisableDirective> hits) {
        if (hits.isEmpty()) {
            return null;
        }
        hits.sort(BY_CREATED);
        List<String> ids = new ArrayList<>();
        LinkedHashSet<String> reasons = new LinkedHashSet<>();
        LinkedHashSet<String> operators = new LinkedHashSet<>();
        long createdAt = Long.MAX_VALUE;
        Long expiresAt = null; // 任一永久 => 整体永久
        boolean allTimed = true;
        long maxExpires = Long.MIN_VALUE;
        for (DisableDirective d : hits) {
            ids.add(d.id());
            reasons.add(d.reason());
            operators.add(d.operator());
            createdAt = Math.min(createdAt, d.createdAtMillis());
            if (d.expiresAtMillis() == null) {
                allTimed = false;
            } else {
                maxExpires = Math.max(maxExpires, d.expiresAtMillis());
            }
        }
        if (allTimed) {
            expiresAt = maxExpires;
        }
        return new DisableView(flagKey, List.copyOf(ids),
                String.join(" + ", reasons),
                String.join(" + ", operators),
                createdAt, expiresAt,
                hits.get(0).baseSnapshotId());
    }
}
