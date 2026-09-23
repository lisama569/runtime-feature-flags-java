package com.github.lisama569.runtimefeatureflagsjava.model;

import java.util.Map;

/** 不可变配置快照：读请求永远只会看到完整快照，不会看到中间态。 */
public final class FlagSnapshot {
    private final String snapshotId;
    private final long version;
    private final long createdAt;
    private final boolean globalKillSwitch;
    private final Map<String, FlagDefinition> flags;

    public FlagSnapshot(String snapshotId, long version, long createdAt,
                        boolean globalKillSwitch, Map<String, FlagDefinition> flags) {
        this.snapshotId = snapshotId;
        this.version = version;
        this.createdAt = createdAt;
        this.globalKillSwitch = globalKillSwitch;
        this.flags = flags;
    }

    public String getSnapshotId() { return snapshotId; }
    public long getVersion() { return version; }
    public long getCreatedAt() { return createdAt; }
    public boolean isGlobalKillSwitch() { return globalKillSwitch; }
    public Map<String, FlagDefinition> getFlags() { return flags; }
}
