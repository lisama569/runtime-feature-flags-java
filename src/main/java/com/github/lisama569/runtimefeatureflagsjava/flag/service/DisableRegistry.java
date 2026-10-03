package com.github.lisama569.runtimefeatureflagsjava.flag.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * 紧急停用登记处：停用指令的函数式核心（无自身可变状态）。
 * <p>
 * 设计要点：
 * <ul>
 *   <li>停用状态是一个<b>不可变</b>的 {@code Map<flagKey, List<DisableDirective>>}，
 *       由 {@link FeatureFlagManager} 与配置快照组合进同一个 volatile 状态整体发布，
 *       因此读请求永远看到"快照 + 停用"成对一致的完整状态，不会只生效一半。</li>
 *   <li>所有变更操作（{@link #addDirectives} / {@link #restoreFlag} /
 *       {@link #restoreId}）都是纯函数：入参当前状态，出参 {@link Mutation}
 *       携带操作结果与下一版不可变状态；被拒绝时 next 与 current 完全相同。</li>
 *   <li>停用独立于配置刷新：本类完全不感知快照切换，刷新/回滚/刷新失败都不会触碰指令；
 *       解除只有两条路径——显式恢复，或到达 {@code expireAtMillis} 自动到期。</li>
 *   <li>到期判定以注入的时钟为准（测试可控制），读路径实时过滤；
 *       到期后的指令等同于"从未存在"，开关结论恢复为按当前配置直接判定。</li>
 * </ul>
 */
public class DisableRegistry {

    /** 生效中停用指令条目的硬上限（按指令条数计）。 */
    public static final int MAX_ACTIVE_DIRECTIVES = 100;

    private final LongSupplier clockMillis;
    private final int capacity;

    public DisableRegistry() {
        this(System::currentTimeMillis, MAX_ACTIVE_DIRECTIVES);
    }

    /** 测试/定制用：注入时钟与容量上限。 */
    public DisableRegistry(LongSupplier clockMillis, int capacity) {
        this.clockMillis = clockMillis;
        this.capacity = capacity;
    }

    /** 一次变更的结果：操作结论 + 下一版不可变停用状态（被拒绝时与入参相同）。 */
    public record Mutation(DisableOutcome outcome,
                           Map<String, List<DisableDirective>> next) {
    }

    /** 空状态（不可变）。 */
    public Map<String, List<DisableDirective>> emptyState() {
        return Map.of();
    }

    /**
     * 对一组开关各添加一条停用指令（原子：要么全部加入，要么全部拒绝、现状不变）。
     *
     * @param ttlMillis 有效期毫秒；0 表示不限期（只能显式恢复），负数非法
     */
    public Mutation addDirectives(Map<String, List<DisableDirective>> current,
                                  List<String> flagKeys,
                                  String reason, String operator,
                                  long ttlMillis,
                                  String baseSnapshotId, long baseSnapshotVersion) {
        long now = clockMillis.getAsLong();

        // ---- 参数校验：拒绝时现状完全不变 ----
        if (flagKeys == null || flagKeys.isEmpty()) {
            return reject(current, DisableFailureReason.INVALID_ARGUMENT,
                    "停用目标开关列表为空");
        }
        List<String> keys = new ArrayList<>(new LinkedHashSet<>(flagKeys));
        for (String key : keys) {
            if (key == null || key.isBlank()) {
                return reject(current, DisableFailureReason.INVALID_ARGUMENT,
                        "存在空的开关键名");
            }
        }
        if (reason == null || reason.isBlank()) {
            return reject(current, DisableFailureReason.INVALID_ARGUMENT,
                    "停用原因必填，不允许为空");
        }
        if (operator == null || operator.isBlank()) {
            return reject(current, DisableFailureReason.INVALID_ARGUMENT,
                    "操作人必填，不允许为空");
        }
        if (ttlMillis < 0) {
            return reject(current, DisableFailureReason.INVALID_ARGUMENT,
                    "TTL 不能为负数: " + ttlMillis + "（0 表示不限期）");
        }

        // 写前清理已到期条目，再计算容量
        Map<String, List<DisableDirective>> purged = purgeExpired(current, now);
        int activeCount = countAll(purged);
        if ((long) activeCount + keys.size() > capacity) {
            // 超容量：整体拒绝，返回清理后的状态（到期条目本来就已失效），不新增任何东西
            return new Mutation(DisableOutcome.fail(DisableFailureReason.CAPACITY_EXCEEDED,
                    "生效中停用条目已达上限 " + capacity + "（当前 " + activeCount
                            + " 条），本次请求新增 " + keys.size()
                            + " 条将超限，已整体拒绝且现状不变"),
                    purged);
        }

        long expireAt = ttlMillis == 0L ? 0L : now + ttlMillis;
        Map<String, List<DisableDirective>> next = new LinkedHashMap<>(purged);
        List<DisableDirective> added = new ArrayList<>();
        for (String key : keys) {
            DisableDirective directive = new DisableDirective(
                    newDirectiveId(), key, reason.trim(), operator.trim(),
                    now, expireAt, baseSnapshotId, baseSnapshotVersion);
            List<DisableDirective> list = new ArrayList<>(
                    next.getOrDefault(key, List.of()));
            list.add(directive);
            next.put(key, List.copyOf(list));
            added.add(directive);
        }
        Map<String, List<DisableDirective>> immutableNext = Map.copyOf(next);
        String expireText = expireAt == 0L ? "不限期"
                : ("有效期至 " + expireAt + "（" + ttlMillis + "ms）");
        return new Mutation(DisableOutcome.ok(added,
                "已停用 " + added.size() + " 个开关，" + expireText
                        + "，操作人=" + operator.trim() + "，原因=" + reason.trim()),
                immutableNext);
    }

    /** 恢复某开关下全部生效中的停用指令；没有生效指令时 NOT_FOUND，现状不变。 */
    public Mutation restoreFlag(Map<String, List<DisableDirective>> current, String flagKey) {
        long now = clockMillis.getAsLong();
        if (flagKey == null || flagKey.isBlank()) {
            return reject(current, DisableFailureReason.INVALID_ARGUMENT, "开关键名为空");
        }
        List<DisableDirective> active = activeFor(current, flagKey);
        if (active.isEmpty()) {
            return reject(current, DisableFailureReason.NOT_FOUND,
                    "开关 " + flagKey + " 当前没有生效中的停用指令");
        }
        // 物理移除该键的全部条目（含已到期的），其他开关不动
        Map<String, List<DisableDirective>> next = new LinkedHashMap<>(current);
        next.remove(flagKey);
        return new Mutation(DisableOutcome.ok(active,
                "已恢复开关 " + flagKey + "，移除停用指令 " + active.size() + " 条"),
                Map.copyOf(next));
    }

    /** 按指令 id 恢复单条；id 不存在或已到期时 NOT_FOUND，现状不变。 */
    public Mutation restoreId(Map<String, List<DisableDirective>> current, String directiveId) {
        long now = clockMillis.getAsLong();
        if (directiveId == null || directiveId.isBlank()) {
            return reject(current, DisableFailureReason.INVALID_ARGUMENT, "指令 id 为空");
        }
        for (Map.Entry<String, List<DisableDirective>> e : current.entrySet()) {
            List<DisableDirective> list = e.getValue();
            DisableDirective found = null;
            for (DisableDirective d : list) {
                if (d.id().equals(directiveId)) {
                    found = d;
                    break;
                }
            }
            if (found == null) {
                continue;
            }
            if (!found.activeAt(now)) {
                return reject(current, DisableFailureReason.NOT_FOUND,
                        "停用指令 " + directiveId + " 已到期，无需恢复");
            }
            List<DisableDirective> remaining = new ArrayList<>(list);
            remaining.remove(found);
            Map<String, List<DisableDirective>> next = new LinkedHashMap<>(current);
            if (remaining.isEmpty()) {
                next.remove(e.getKey());
            } else {
                next.put(e.getKey(), List.copyOf(remaining));
            }
            return new Mutation(DisableOutcome.ok(List.of(found),
                    "已按指令 id 恢复开关 " + e.getKey() + " 的停用: " + directiveId),
                    Map.copyOf(next));
        }
        return reject(current, DisableFailureReason.NOT_FOUND,
                "停用指令不存在: " + directiveId);
    }

    /** 某开关当前生效中的停用指令（到期的过滤掉），按创建时间排序。 */
    public List<DisableDirective> activeFor(Map<String, List<DisableDirective>> state,
                                            String flagKey) {
        long now = clockMillis.getAsLong();
        List<DisableDirective> list = state.get(flagKey);
        if (list == null) {
            return List.of();
        }
        List<DisableDirective> alive = new ArrayList<>();
        for (DisableDirective d : list) {
            if (d.activeAt(now)) {
                alive.add(d);
            }
        }
        return List.copyOf(alive);
    }

    /** 全部生效中的停用指令（到期的过滤掉），按开关键、创建时间排序。 */
    public List<DisableDirective> listActive(Map<String, List<DisableDirective>> state) {
        long now = clockMillis.getAsLong();
        List<DisableDirective> all = new ArrayList<>();
        for (Map.Entry<String, List<DisableDirective>> e :
                new LinkedHashMap<>(state).entrySet()) {
            for (DisableDirective d : e.getValue()) {
                if (d.activeAt(now)) {
                    all.add(d);
                }
            }
        }
        all.sort((a, b) -> {
            int c = a.flagKey().compareTo(b.flagKey());
            if (c != 0) {
                return c;
            }
            return Long.compare(a.createdAtMillis(), b.createdAtMillis());
        });
        return List.copyOf(all);
    }

    /** 当前生效中指令条数（到期的不计）。 */
    public int activeCount(Map<String, List<DisableDirective>> state) {
        return countAll(purgeExpired(state, clockMillis.getAsLong()));
    }

    // ---- 内部工具 ----

    private static Mutation reject(Map<String, List<DisableDirective>> current,
                                   DisableFailureReason reason, String detail) {
        return new Mutation(DisableOutcome.fail(reason, detail), current);
    }

    private static int countAll(Map<String, List<DisableDirective>> state) {
        int n = 0;
        for (List<DisableDirective> list : state.values()) {
            n += list.size();
        }
        return n;
    }

    /** 返回剔除全部到期条目后的不可变新状态；没有到期条目时原样返回。 */
    private Map<String, List<DisableDirective>> purgeExpired(
            Map<String, List<DisableDirective>> current, long now) {
        Map<String, List<DisableDirective>> purged = null;
        for (Map.Entry<String, List<DisableDirective>> e : current.entrySet()) {
            List<DisableDirective> alive = null;
            for (DisableDirective d : e.getValue()) {
                if (!d.activeAt(now)) {
                    if (purged == null) {
                        purged = new LinkedHashMap<>(current);
                    }
                    if (alive == null) {
                        alive = new ArrayList<>(e.getValue());
                    }
                    alive.remove(d);
                }
            }
            if (alive != null) {
                if (alive.isEmpty()) {
                    purged.remove(e.getKey());
                } else {
                    purged.put(e.getKey(), List.copyOf(alive));
                }
            }
        }
        return purged == null ? current : Map.copyOf(purged);
    }

    private static String newDirectiveId() {
        return "ds-" + UUID.randomUUID();
    }
}
