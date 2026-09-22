package com.particlesdevs.photoncamera.circularbarlib.control.knob;

/**
 * One tick of a knob, as plain data: what it is worth, what to draw for it and
 * whether it is the one selected.  The widget owns the drawing.
 */
public class KnobItemInfo implements Comparable<KnobItemInfo> {
    /** Value text for the manual bar, always set. */
    public final String text;
    /** Text drawn at this tick, or EMPTY for a bare tick. */
    public final String label;
    public final KnobIcon icon;
    public final int tick;
    public final double value;
    public boolean isSelected;
    public double rotationCenter;
    public double rotationLeft;
    public double rotationRight;

    public KnobItemInfo(String text, String label, int tick, double value) {
        this(text, label, KnobIcon.NONE, tick, value);
    }

    public KnobItemInfo(String text, String label, KnobIcon icon, int tick, double value) {
        this.text = text;
        // "" and not null: every bare tick passes null here, and j2k asserts on
        // a reference field's assignment -- which throws for the whole focus and
        // white-balance knob.  The widgets already read the two the same way.
        this.label = label == null ? "" : label;
        this.icon = icon;
        this.tick = tick;
        this.value = value;
    }

    @Override
    public int compareTo(KnobItemInfo another) {
        if (Math.abs(this.rotationCenter - another.rotationCenter) < 0.01d) {
            return 0;
        }
        if (this.rotationCenter > another.rotationCenter) {
            return 1;
        }
        return -1;
    }

    @Override
    public String toString() {
        return "KnobItemInfo [Tick: " + this.tick + ", Text: " + this.text + ", Value: " + this.value + ", Rotation: " + this.rotationCenter + ", Rotation left: " + this.rotationLeft + ", Rotation right: " + this.rotationRight + "]";
    }
}
