package com.particlesdevs.photoncamera.circularbarlib.model;

/** What the manual bar reports back when one of its parameters is tapped. */
public interface ParamClickListener {
    void onParamClicked(ManualParam param);

    void onParamLongClicked(ManualParam param);
}
