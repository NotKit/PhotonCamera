package com.particlesdevs.photoncamera.circularbarlib.control.knob;

/**
 * The sweep of each knob, in degrees.  These were res/values/integers.xml; they
 * are geometry of the model, not of the widget, and never varied by config.
 */
public final class KnobAngles {
    public static final int EXPOSURE_HALF = 250;
    public static final int EXPOSURE_AUTO = 30;
    public static final int FOCUS_HALF = 120;
    public static final int FOCUS_AUTO = 30;
    public static final int ISO_HALF = 150;
    public static final int ISO_AUTO = 30;
    public static final int EV_HALF = 40;
    public static final int EV_AUTO = 5;

    private KnobAngles() {
    }
}
