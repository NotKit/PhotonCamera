package com.particlesdevs.photoncamera.circularbarlib.control.models;

import android.hardware.camera2.CameraCharacteristics;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.util.Range;

import com.particlesdevs.photoncamera.circularbarlib.control.ManualParamModel;
import com.particlesdevs.photoncamera.circularbarlib.control.knob.KnobChangedListener;
import com.particlesdevs.photoncamera.circularbarlib.control.knob.KnobHost;
import com.particlesdevs.photoncamera.circularbarlib.control.knob.KnobInfo;
import com.particlesdevs.photoncamera.circularbarlib.control.knob.KnobItemInfo;
import com.particlesdevs.photoncamera.circularbarlib.control.knob.KnobText;
import com.particlesdevs.photoncamera.circularbarlib.control.knob.RotationState;

import java.util.ArrayList;
import java.util.List;

/**
 * The base model class for the data model attached to a {@link KnobHost}
 * <p>
 * Also responsible for updating {@link ManualParamModel}
 * <p>
 * Created by KillerInk on 31/Aug/2020
 * Modified by Vibhor
 *
 * @param <T> the type of data contained by the model
 */
public abstract class ManualModel<T extends Comparable<? super T>> implements KnobChangedListener, IModel {
    private static final String TAG = "ManualModel";
    protected final ManualParamModel manualParamModel;
    private final List<KnobItemInfo> knobInfoList;
    private final ValueChangedEvent valueChangedEvent;
    private final Vibrator vibrator;
    private final VibrationEffect tick;
    protected CameraCharacteristics cameraCharacteristics;
    protected Range<T> range;
    protected KnobInfo knobInfo;
    protected KnobItemInfo currentInfo, autoModel;

    public ManualModel(CameraCharacteristics cameraCharacteristics, Range<T> range, ManualParamModel manualParamModel, ValueChangedEvent valueChangedEvent, Vibrator v) {
        this.cameraCharacteristics = cameraCharacteristics;
        this.range = range;
        this.valueChangedEvent = valueChangedEvent;
        this.manualParamModel = manualParamModel;
        this.vibrator = v;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            this.tick = VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK);
        } else this.tick = VibrationEffect.createOneShot(9, 255);
        knobInfoList = new ArrayList<KnobItemInfo>();
        fillKnobInfoList();
    }

    public void setAutoTxt() {
        fireValueChangedEvent(autoModel.text);
    }

    private void fireValueChangedEvent(final String txt) {
        if (valueChangedEvent != null)
            valueChangedEvent.onValueChanged(txt);
    }

    protected KnobItemInfo getNewAutoItem(double defaultVal, String defaultText) {
        String autoString = defaultText != null ? defaultText : KnobText.AUTO;
        autoModel = new KnobItemInfo(autoString, autoString, 0, defaultVal);
        return autoModel;
    }

    protected abstract void fillKnobInfoList();

    @Override
    public List<KnobItemInfo> getKnobInfoList() {
        return knobInfoList;
    }

    @Override
    public KnobItemInfo getCurrentInfo() {
        return currentInfo;
    }

    @Override
    public KnobInfo getKnobInfo() {
        return knobInfo;
    }

    @Override
    public void onRotationStateChanged(KnobHost knobHost, RotationState rotationState) {
    }

    @Override
    public void onSelectedKnobItemChanged(KnobHost knobHost, KnobItemInfo oldItem, final KnobItemInfo newItem) {
        Log.d(TAG, "onSelectedKnobItemChanged");
        vibrator.vibrate(tick);
        if (oldItem == newItem)
            return;
        onItemSelected(newItem);
        if (oldItem != null) {
            oldItem.isSelected = false;
        }
        newItem.isSelected = true;
        fireValueChangedEvent(newItem.text);
    }

    public void resetModel() {
        onSelectedKnobItemChanged(null, null, autoModel);
    }

    /** The concrete model's hook; named apart from the listener overload so the
     *  two do not collide. */
    public abstract void onItemSelected(KnobItemInfo knobItemInfo);

    public interface ValueChangedEvent {
        void onValueChanged(String value);
    }
}
