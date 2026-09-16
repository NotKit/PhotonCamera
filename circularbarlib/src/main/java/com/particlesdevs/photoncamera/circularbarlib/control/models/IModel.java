package com.particlesdevs.photoncamera.circularbarlib.control.models;


import com.particlesdevs.photoncamera.circularbarlib.control.knob.KnobInfo;
import com.particlesdevs.photoncamera.circularbarlib.control.knob.KnobItemInfo;

import java.util.List;

public interface IModel {
    List<KnobItemInfo> getKnobInfoList();

    KnobItemInfo getCurrentInfo();

    KnobInfo getKnobInfo();
}
