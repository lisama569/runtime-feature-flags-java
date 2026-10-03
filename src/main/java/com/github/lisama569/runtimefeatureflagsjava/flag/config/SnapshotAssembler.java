package com.github.lisama569.runtimefeatureflagsjava.flag.config;

import com.github.lisama569.runtimefeatureflagsjava.flag.model.ConfigSnapshot;
import com.github.lisama569.runtimefeatureflagsjava.flag.source.ConfigSource;
import com.github.lisama569.runtimefeatureflagsjava.flag.source.SourceUnavailableException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 加载全部来源、按优先级合并、校验并组装为不可变快照。
 * <p>
 * 合并规则：来源按 {@link ConfigSource#priority()} 升序处理，
 * 同一键被高优先级来源覆盖；每个键最终值都记录其出处来源名，便于解释。
 * <p>
 * 拒绝规则（满足任一即整份快照拒绝，绝不部分生效、绝不静默回退默认值）：
 * <ul>
 *   <li>来源列表为空 → {@link ConfigProblemType#MISSING_FIELD} 语义（无来源）</li>
 *   <li>任一来源 load 抛异常 → 包装为 {@link SourceUnavailableException}，拒绝原因可区分到来源名</li>
 *   <li>合并后存在非法取值 → {@link InvalidFlagConfigException}，拒绝原因可区分到问题类型+原始键</li>
 * </ul>
 * 全局总闸键：{@code kill.switch}，取值 true/false，非法同样拒绝整份快照。
 */
public class SnapshotAssembler {

    public static final String KILL_SWITCH_KEY = "kill.switch";

    private final FlagConfigParser parser = new FlagConfigParser();

    /**
     * 组装一份新快照。失败时抛出异常（由 Manager 捕获后保留旧快照）。
     */
    public ConfigSnapshot assemble(List<ConfigSource> sources, long version) {
        if (sources == null || sources.isEmpty()) {
            throw new InvalidFlagConfigException(ConfigProblemType.MISSING_FIELD,
                    "<sources>", null, "没有任何可用配置来源，拒绝刷新");
        }

        // 升序：低优先级先放入，高优先级后覆盖
        List<ConfigSource> ordered = sources.stream()
                .sorted((a, b) -> Integer.compare(a.priority(), b.priority()))
                .toList();

        Map<String, String> merged = new LinkedHashMap<>();
        Map<String, String> keyOrigins = new LinkedHashMap<>();
        for (ConfigSource source : ordered) {
            Map<String, String> loaded;
            try {
                loaded = source.load();
            } catch (SourceUnavailableException ex) {
                throw ex;
            } catch (RuntimeException ex) {
                // 来源读取过程中出现任何异常，都视为来源缺失，不得静默跳过
                throw new SourceUnavailableException(source.name(),
                        "配置来源读取失败: source=" + source.name()
                                + ", cause=" + ex.getMessage(), ex);
            }
            if (loaded == null) {
                throw new SourceUnavailableException(source.name(),
                        "配置来源返回空: source=" + source.name());
            }
            for (Map.Entry<String, String> e : loaded.entrySet()) {
                merged.put(e.getKey(), e.getValue());
                keyOrigins.put(e.getKey(), source.name());
            }
        }

        boolean killSwitch = parseKillSwitch(merged);
        var flags = parser.parse(merged, keyOrigins);

        // 快照标识：版本 + 内容指纹，保证同一内容可复现、不同内容可区分
        String fingerprint = fingerprint(version, killSwitch, merged);
        String snapshotId = "v" + version + "-" + fingerprint;

        return new ConfigSnapshot(snapshotId, version, System.currentTimeMillis(),
                killSwitch, Map.copyOf(flags));
    }

    private boolean parseKillSwitch(Map<String, String> merged) {
        String raw = merged.get(KILL_SWITCH_KEY);
        if (raw == null) {
            return false;
        }
        String v = raw.trim();
        if ("true".equalsIgnoreCase(v)) {
            return true;
        }
        if ("false".equalsIgnoreCase(v)) {
            return false;
        }
        throw new InvalidFlagConfigException(ConfigProblemType.INVALID_BOOLEAN,
                KILL_SWITCH_KEY, raw, "总闸 kill.switch 不是合法布尔值: " + raw);
    }

    private String fingerprint(long version, boolean killSwitch, Map<String, String> merged) {
        String canonical = "ver=" + version
                + "|kill=" + killSwitch + "|"
                + merged.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("\n"));
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
