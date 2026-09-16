package com.particlesdevs.photoncamera.circularbarlib.control.knob;

import java.util.List;

/**
 * The widget a knob model is attached to - the KnobView on Android, the Compose
 * knob on the port.  A model talks to this, never to a View.
 */
public interface KnobHost {
    void setKnobInfo(KnobInfo info);

    void setKnobItems(List<KnobItemInfo> items);

    void setTickByValue(double value);

    KnobItemInfo getCurrentKnobItem();
}
