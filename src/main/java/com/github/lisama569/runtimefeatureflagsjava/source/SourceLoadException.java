package com.github.lisama569.runtimefeatureflagsjava.source;

import com.github.lisama569.runtimefeatureflagsjava.model.RejectionKind;

/** 来源加载失败时抛出，携带可区分的拒绝原因。 */
public class SourceLoadException extends Exception {
    private final RejectionKind kind;

    public SourceLoadException(RejectionKind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public SourceLoadException(RejectionKind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public RejectionKind getKind() { return kind; }
}
