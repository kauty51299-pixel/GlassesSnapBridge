package com.sunny.glassesbridge;

/** Counts delivered events from one control; never combines different controls. */
public final class TapSequenceGate {
    private final long gapMs;
    private int required;
    private int count;
    private long last = -1;
    private String source;
    public TapSequenceGate(long gapMs, int required) {
        if (gapMs <= 0) throw new IllegalArgumentException("Positive tap window required");
        this.gapMs = gapMs;
        setRequired(required);
    }
    public void setRequired(int value) {
        if (value != 3 && value != 4) throw new IllegalArgumentException("Choose 3 or 4 taps");
        required = value;
        reset();
    }
    public boolean tap(long nowMs, String source) {
        if (source == null || source.isEmpty()) throw new IllegalArgumentException("Source required");
        if (last < 0 || nowMs < last || nowMs - last > gapMs || !source.equals(this.source)) count = 0;
        this.source = source;
        last = nowMs;
        count++;
        if (count == required) { reset(); return true; }
        return false;
    }
    public int count() { return count; }
    public void reset() { count = 0; last = -1; source = null; }
}
