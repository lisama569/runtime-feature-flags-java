package com.github.lisama569.runtimefeatureflagsjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 运行参数：来源文件、兜底语义、历史保留数等。 */
@ConfigurationProperties(prefix = "feature-flags")
public class FeatureFlagProperties {

    /** 低优先级来源文件路径（必需）。 */
    private String baseFile = "config/flags-base.properties";

    /** 高优先级来源文件路径（可选，缺失仅记录拒绝不阻断）。 */
    private String overrideFile = "config/flags-override.properties";

    /** 判定失败时是否兜底为 ON（默认 fail-closed）。 */
    private boolean failOpen = false;

    /** 保留的历史快照数量（用于回滚）。 */
    private int historySize = 10;

    public String getBaseFile() { return baseFile; }
    public void setBaseFile(String baseFile) { this.baseFile = baseFile; }

    public String getOverrideFile() { return overrideFile; }
    public void setOverrideFile(String overrideFile) { this.overrideFile = overrideFile; }

    public boolean isFailOpen() { return failOpen; }
    public void setFailOpen(boolean failOpen) { this.failOpen = failOpen; }

    public int getHistorySize() { return historySize; }
    public void setHistorySize(int historySize) { this.historySize = historySize; }
}
