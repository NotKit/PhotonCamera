package com.particlesdevs.photoncamera.circularbarlib.control.knob;

public interface KnobChangedListener {
    void onRotationStateChanged(KnobHost knobHost, RotationState rotationState);

    void onSelectedKnobItemChanged(KnobHost knobHost, KnobItemInfo oldItem, KnobItemInfo newItem);
}
