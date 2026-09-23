package com.github.lisama569.runtimefeatureflagsjava.flag.source;

/**
 * 来源缺失/不可用时抛出，携带来源名，用于产生可区分的拒绝原因。
 */
public class SourceUnavailableException extends RuntimeException {

    private final String sourceName;

    public SourceUnavailableException(String sourceName, String message) {
        super(message);
        this.sourceName = sourceName;
    }

    public SourceUnavailableException(String sourceName, String message, Throwable cause) {
        super(message, cause);
        this.sourceName = sourceName;
    }

    public String sourceName() {
        return sourceName;
    }
}
