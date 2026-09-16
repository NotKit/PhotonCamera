package com.particlesdevs.photoncamera.circularbarlib.control.models;

import android.hardware.camera2.CameraCharacteristics;
import android.os.Vibrator;
import android.util.Range;

import com.particlesdevs.photoncamera.circularbarlib.control.ManualParamModel;
import com.particlesdevs.photoncamera.circularbarlib.control.knob.KnobAngles;
import com.particlesdevs.photoncamera.circularbarlib.control.knob.KnobIcon;
import com.particlesdevs.photoncamera.circularbarlib.control.knob.KnobInfo;
import com.particlesdevs.photoncamera.circularbarlib.control.knob.KnobItemInfo;
import com.particlesdevs.photoncamera.circularbarlib.control.knob.KnobText;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Created by killerink, vibhorSrv, eszdman
 */
public class FocusModel extends ManualModel<Float> {

    public FocusModel(CameraCharacteristics cameraCharacteristics, Range<Float> range,
                      ManualParamModel manualParamModel, ValueChangedEvent valueChangedEvent, Vibrator v) {
        super(cameraCharacteristics, range, manualParamModel, valueChangedEvent, v);
    }

    @Override
    protected void fillKnobInfoList() {
        KnobItemInfo auto;
        if (range == null) {
            auto = getNewAutoItem(-1.0d, KnobText.FIXED);
            getKnobInfoList().add(auto);
            currentInfo = auto;
            return;
        }
        auto = getNewAutoItem(ManualParamModel.FOCUS_AUTO, null);
        getKnobInfoList().add(auto);
        currentInfo = auto;
        float focusStep = (range.getUpper() - range.getLower()) / 40;
        ArrayList<Float> values = new ArrayList<>();
        for (float fValue = range.getUpper(); fValue >= range.getLower(); fValue -= focusStep) {
            values.add(fValue);
        }
        if (values.size() > 0) {
            values.set(values.size() - 1, range.getLower());
        }
        for (int tick = 0; tick < values.size(); tick++) {
            KnobIcon icon = KnobIcon.NONE;
            if (tick == 0) {
                icon = KnobIcon.FOCUS_NEAR;
            } else if (tick == values.size() - 1) {
                icon = KnobIcon.FOCUS_FAR;
            }
            String text = String.format(Locale.ROOT, "%.2f", values.get(tick));
            getKnobInfoList().add(new KnobItemInfo(text, null, icon, tick + 1, (double) values.get(tick)));
        }
        knobInfo = new KnobInfo(0, KnobAngles.FOCUS_HALF, 0, values.size(), KnobAngles.FOCUS_AUTO);
    }

    @Override
    public void onItemSelected(KnobItemInfo knobItemInfo) {
        currentInfo = knobItemInfo;
        manualParamModel.setCurrentFocusValue(knobItemInfo.value);
    }
}
