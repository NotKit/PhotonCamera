package com.particlesdevs.photoncamera.circularbarlib.control.models;

import android.hardware.camera2.CameraCharacteristics;
import android.os.Vibrator;
import android.util.Log;
import android.util.Range;

import com.particlesdevs.photoncamera.circularbarlib.control.ManualParamModel;
import com.particlesdevs.photoncamera.circularbarlib.control.knob.KnobAngles;
import com.particlesdevs.photoncamera.circularbarlib.control.knob.KnobInfo;
import com.particlesdevs.photoncamera.circularbarlib.control.knob.KnobItemInfo;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Created by killerink, vibhorSrv, eszdman
 */
public class EvModel extends ManualModel<Float> {

    private static final String TAG = "EvModel";
    private float evStep;

    public EvModel(CameraCharacteristics cameraCharacteristics, Range<Float> range,
                   ManualParamModel manualParamModel, ValueChangedEvent valueChangedEvent, Vibrator v) {
        super(cameraCharacteristics, range, manualParamModel, valueChangedEvent, v);
    }

    public void setEvStep(float evStep) {
        this.evStep = evStep;
    }

    @Override
    protected void fillKnobInfoList() {
        Range<Float> evRange = range;
        if (evRange == null || (evRange.getLower() == 0.0f && evRange.getUpper() == 0.0f)) {
            Log.d(TAG, "fillKnobInfoList() - evRange is not valid.");
            return;
        }
        KnobItemInfo auto = getNewAutoItem(ManualParamModel.EV_AUTO, null);
        getKnobInfoList().add(auto);
        currentInfo = auto;
        int positiveValueCount = 0;
        int negativeValueCount = 0;
        float step = 0.25f;
        ArrayList<Float> values = new ArrayList<>();
        for (float fValue = evRange.getUpper(); fValue >= evRange.getLower(); fValue -= step) {
            float roundedValue = ((float) Math.round(10000.0f * fValue)) / 10000.0f;
            if (!isZero(fValue)) {
                if (fValue > 0.0f) {
                    positiveValueCount++;
                } else {
                    negativeValueCount++;
                }
            }
            values.add(roundedValue);
        }
        if (values.size() > 0) {
            values.set(values.size() - 1, evRange.getLower());
        }
        for (int tick = 0; tick < values.size(); tick++) {
            float value = values.get(tick);
            if (!isZero(value)) {
                String label = "";
                if (isInteger(value)) {
                    label = String.valueOf((int) value);
                    if (value > 0.0f) {
                        label = "+" + label;
                    }
                }
                String text = String.format(Locale.ROOT, "%.2f", value);
                if (value > 0.0f) {
                    getKnobInfoList().add(new KnobItemInfo(text, label, positiveValueCount - tick, (double) value));
                } else {
                    getKnobInfoList().add(new KnobItemInfo(text, label, negativeValueCount - tick, (double) value));
                }
            }
        }
        knobInfo = new KnobInfo(-KnobAngles.EV_HALF, KnobAngles.EV_HALF, -negativeValueCount, positiveValueCount, KnobAngles.EV_AUTO);
    }

    @Override
    public void onItemSelected(KnobItemInfo knobItemInfo) {
        currentInfo = knobItemInfo;
        manualParamModel.setCurrentEvValue((int) (knobItemInfo.value / evStep));
    }

    private boolean isZero(float value) {
        return ((double) Math.abs(value)) <= 0.001d;
    }

    private boolean isInteger(float value) {
        int checkNumber = ((int) (Math.abs(value) * 10000.0f)) % 10000;
        return checkNumber == 0 || checkNumber == 9999;
    }
}
