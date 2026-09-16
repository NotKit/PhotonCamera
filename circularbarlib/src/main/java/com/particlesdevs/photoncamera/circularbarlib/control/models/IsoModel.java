package com.particlesdevs.photoncamera.circularbarlib.control.models;

import android.hardware.camera2.CameraCharacteristics;
import android.os.Vibrator;
import android.util.Log;
import android.util.Range;

import com.particlesdevs.photoncamera.circularbarlib.camera.IsoExpoSelector;
import com.particlesdevs.photoncamera.circularbarlib.control.ManualParamModel;
import com.particlesdevs.photoncamera.circularbarlib.control.knob.KnobAngles;
import com.particlesdevs.photoncamera.circularbarlib.control.knob.KnobInfo;
import com.particlesdevs.photoncamera.circularbarlib.control.knob.KnobItemInfo;

import java.util.ArrayList;
/**
 * Created by killerink, vibhorSrv, eszdman
 */
public class IsoModel extends ManualModel<Integer> {

    public IsoModel(CameraCharacteristics cameraCharacteristics, Range<Integer> range,
                    ManualParamModel manualParamModel, ValueChangedEvent valueChangedEvent, Vibrator v) {
        super(cameraCharacteristics, range, manualParamModel, valueChangedEvent, v);
    }

    @Override
    protected void fillKnobInfoList() {
        KnobItemInfo auto = getNewAutoItem(ManualParamModel.ISO_AUTO, null);
        getKnobInfoList().add(auto);
        currentInfo = auto;

        ArrayList<String> candidates = new ArrayList<>();
        ArrayList<Integer> values = new ArrayList<>();
        int miniso = range.getLower();
        int maxiso = range.getUpper();
        Log.v("IsoModel", "Max iso:" + maxiso);
        Log.v("IsoModel", "Max iso cnt:" + Math.log10((double) maxiso / miniso) / Math.log10(2));
        for (double isoCnt = Math.log10(1) / Math.log10(2); isoCnt < Math.log10((double) maxiso / miniso) / Math.log10(2); isoCnt += 1.0 / 4.0) {
            int val = (int) (Math.pow(2.0, isoCnt) * miniso);
            candidates.add(String.valueOf(val));
            values.add((int) (val / IsoExpoSelector.getMPY(cameraCharacteristics)));
        }
        candidates.add(String.valueOf(maxiso));
        values.add((int) (maxiso / IsoExpoSelector.getMPY(cameraCharacteristics)));
        int indicatorCount = 0;
        int tick = 0;
        int preferredIntervalCount = 4;
        while (tick < candidates.size()) {
            boolean isLastItem = tick == candidates.size() + -1;
            String label = null;
            if (tick % preferredIntervalCount == 0 || isLastItem) {
                label = candidates.get(tick);
                indicatorCount++;
            }
            getKnobInfoList().add(new KnobItemInfo(candidates.get(tick), label, tick + 1, (double) values.get(tick)));
            tick++;
        }
        int angle = findPreferredKnobViewAngle(indicatorCount);
        if (angle > KnobAngles.ISO_HALF) {
            angle = KnobAngles.ISO_HALF;
        }
        knobInfo = new KnobInfo(0, angle, 0, candidates.size(), KnobAngles.ISO_AUTO);
    }

    @Override
    public void onItemSelected(KnobItemInfo knobItemInfo) {
        currentInfo = knobItemInfo;
        manualParamModel.setCurrentISOValue(knobItemInfo.value);
    }

    private int findPreferredKnobViewAngle(int indicatorCount) {
        return (indicatorCount - 1) * 20;
    }

}
