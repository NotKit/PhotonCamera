package com.particlesdevs.photoncamera.circularbarlib.control.models;

import android.hardware.camera2.CameraCharacteristics;
import android.os.Vibrator;
import android.util.Range;

import com.particlesdevs.photoncamera.circularbarlib.control.ManualParamModel;
import com.particlesdevs.photoncamera.circularbarlib.control.knob.KnobAngles;
import com.particlesdevs.photoncamera.circularbarlib.control.knob.KnobInfo;
import com.particlesdevs.photoncamera.circularbarlib.control.knob.KnobItemInfo;

import java.util.ArrayList;

/**
 * Model responsible for managing a pure, strictly uniform 50K stepped Kelvin scale (2000K - 10000K).
 * Follows ShutterModel architecture with labeled indicators every 1000K and 4 intermediate 50K ticks.
 */
public class WbModel extends ManualModel<Integer> {

    /** The scale's ends, which the console passes back in as the range. */
    public static final int MIN_KELVIN = 2000;
    public static final int MAX_KELVIN = 10000;

    public WbModel(CameraCharacteristics cameraCharacteristics, Range<Integer> range,
                   ManualParamModel manualParamModel, ValueChangedEvent valueChangedEvent, Vibrator v) {
        super(cameraCharacteristics, range, manualParamModel, valueChangedEvent, v);
    }

    @Override
    protected void fillKnobInfoList() {
        KnobItemInfo auto = getNewAutoItem(ManualParamModel.WB_AUTO, null);
        getKnobInfoList().add(auto);
        currentInfo = auto;

        ArrayList<String> candidates = new ArrayList<>();
        ArrayList<String> labels = new ArrayList<>();
        ArrayList<Integer> values = new ArrayList<>();

        int minK = range != null ? range.getLower() : MIN_KELVIN;
        int maxK = range != null ? range.getUpper() : MAX_KELVIN;
        int stepK = 50;

        // Generate uniform steps from 2000K to 10000K
        for (int k = minK; k <= maxK; k += stepK) {
            candidates.add(String.valueOf(k) + "K");
            values.add(k);

            // Major text label every 1000K (every 5th tick)
            if (k % 1000 == 0) {
                int thousand = k / 1000;
                labels.add(String.valueOf(thousand) + "K");
            } else {
                labels.add(""); // An empty label instructs the knob to draw an intermediate tick mark
            }
        }

        int indicatorCount = 0;
        int tick = 0;
        while (tick < candidates.size()) {
            String label = labels.get(tick);
            if (label != null && !label.isEmpty()) {
                indicatorCount++;
            } else {
                label = "";
            }
            getKnobInfoList().add(new KnobItemInfo(candidates.get(tick), label, tick + 1, (double) values.get(tick)));
            tick++;
        }

        int angle = findPreferredKnobViewAngle(indicatorCount);
        if (angle > KnobAngles.FOCUS_HALF) {
            angle = KnobAngles.FOCUS_HALF;
        }
        knobInfo = new KnobInfo(0, angle, 0, candidates.size(), KnobAngles.FOCUS_AUTO);
    }

    private int findPreferredKnobViewAngle(int indicatorCount) {
        return (indicatorCount - 1) * 30;
    }

    @Override
    public void onItemSelected(KnobItemInfo knobItemInfo) {
        currentInfo = knobItemInfo;
        manualParamModel.setCurrentWbValue(knobItemInfo.value);
    }
}
