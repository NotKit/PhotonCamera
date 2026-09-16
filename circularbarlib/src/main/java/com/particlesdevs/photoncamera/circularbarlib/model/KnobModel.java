package com.particlesdevs.photoncamera.circularbarlib.model;

import com.particlesdevs.photoncamera.circularbarlib.control.models.ManualModel;

import java.util.Observable;

/**
 * The Observable data class responsible for the behaviour and appearance of the
 * knob widget: which model it shows, whether it is visible, whether to reset it.
 * <p>
 * Authors - Vibhor, KillerInk
 */
public class KnobModel extends Observable {
    boolean knobResetCalled;
    private boolean knobVisible;
    private ManualModel<?> manualModel;
    private ManualModel<?> secondaryManualModel;

    public ManualModel<?> getManualModel() {
        return manualModel;
    }

    public void setManualModel(ManualModel<?> manualModel) {
        this.manualModel = manualModel;
        notifyObservers(KnobModelFields.MANUAL_MODEL);

    }

    /**
     * The remembered previous control, shown as the smaller inner ruler
     * inside the current wheel. Null when only one control is active.
     */
    public ManualModel<?> getSecondaryManualModel() {
        return secondaryManualModel;
    }

    public void setSecondaryManualModel(ManualModel<?> secondaryManualModel) {
        this.secondaryManualModel = secondaryManualModel;
        notifyObservers(KnobModelFields.SECONDARY_MODEL);

    }

    public boolean isKnobResetCalled() {
        return knobResetCalled;
    }

    public void setKnobResetCalled(boolean resetCalled) {
        this.knobResetCalled = resetCalled;
        notifyObservers(KnobModelFields.RESET);

    }

    public boolean isKnobVisible() {
        return knobVisible;
    }

    public void setKnobVisible(boolean knobVisible) {
        this.knobVisible = knobVisible;
        notifyObservers(KnobModelFields.VISIBILITY);


    }

    @Override
    public void notifyObservers(Object arg) {
        setChanged();
        super.notifyObservers(arg);

    }

    public enum KnobModelFields {
        MANUAL_MODEL, SECONDARY_MODEL, VISIBILITY, RESET
    }
}
