package com.dictalab.domain;

public enum CefrLevel {
    A1,
    A2,
    B1,
    B2;

    public CefrLevel harder() {
        return values()[Math.min(ordinal() + 1, values().length - 1)];
    }

    public CefrLevel easier() {
        return values()[Math.max(ordinal() - 1, 0)];
    }
}
