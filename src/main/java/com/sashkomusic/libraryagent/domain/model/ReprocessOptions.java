package com.sashkomusic.libraryagent.domain.model;

public record ReprocessOptions(
    boolean skipRetag,
    boolean force
) {
    public static ReprocessOptions defaults() {
        return new ReprocessOptions(false, false);
    }
}
