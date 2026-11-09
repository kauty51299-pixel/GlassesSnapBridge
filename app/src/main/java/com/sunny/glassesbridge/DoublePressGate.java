package com.sunny.glassesbridge;

/** Pairs two short presses without reusing a press for another pair. */
public final class DoublePressGate {
    private final long windowMs;
    private long previous = -1;
    public DoublePressGate(long windowMs) {
        if (windowMs <= 0) throw new IllegalArgumentException("Positive press window required");
        this.windowMs = windowMs;
    }
    public boolean shortPress(long nowMs) {
        if (previous >= 0 && nowMs >= previous && nowMs - previous <= windowMs) {
            previous = -1;
            return true;
        }
        previous = nowMs;
        return false;
    }
    public void reset() { previous = -1; }
}
