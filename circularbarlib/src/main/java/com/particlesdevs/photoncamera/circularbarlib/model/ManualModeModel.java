package com.particlesdevs.photoncamera.circularbarlib.model;

import java.util.Observable;

/**
 * The Observable data class holding what the manual bar shows: the five value
 * texts, which parameter is selected, and where taps go.
 * <p>
 * Authors - Vibhor, KillerInk
 */
public class ManualModeModel extends Observable {
    private String focusText;
    private String exposureText;
    private String isoText;
    private String evText;
    private String wbText;
    private ParamClickListener paramClickListener;
    private boolean manualPanelVisible;
    private ManualParam selectedParam;

    public ManualParam getSelectedParam() {
        return selectedParam;
    }

    public void setSelectedParam(ManualParam selectedParam) {
        this.selectedParam = selectedParam;
        notifyObservers(ManualModelFields.SELECTED_PARAM);
    }

    public boolean isManualPanelVisible() {
        return manualPanelVisible;
    }

    public void setManualPanelVisible(boolean manualPanelVisible) {
        this.manualPanelVisible = manualPanelVisible;
        notifyObservers(ManualModelFields.PANEL_VISIBILITY);
    }

    public ParamClickListener getParamClickListener() {
        return paramClickListener;
    }

    public void setParamClickListener(ParamClickListener paramClickListener) {
        this.paramClickListener = paramClickListener;
        notifyObservers(ManualModelFields.CLICK_LISTENER);
    }

    public String getFocusText() {
        return focusText;
    }

    public void setFocusText(String focusText) {
        this.focusText = focusText;
        notifyObservers(ManualModelFields.FOCUS_TEXT);
    }

    public String getExposureText() {
        return exposureText;
    }

    public void setExposureText(String exposureText) {
        this.exposureText = exposureText;
        notifyObservers(ManualModelFields.EXP_TEXT);

    }

    public String getIsoText() {
        return isoText;
    }

    public void setIsoText(String isoText) {
        this.isoText = isoText;
        notifyObservers(ManualModelFields.ISO_TEXT);
    }

    public String getEvText() {
        return evText;
    }

    public void setEvText(String evText) {
        this.evText = evText;
        notifyObservers(ManualModelFields.EV_TEXT);
    }

    public String getWbText() {
        return wbText;
    }

    public void setWbText(String wbText) {
        this.wbText = wbText;
        notifyObservers(ManualModelFields.WB_TEXT);
    }

    @Override
    public void notifyObservers(Object arg) {
        setChanged();
        super.notifyObservers(arg);
    }

    public enum ManualModelFields {
        FOCUS_TEXT, EXP_TEXT, ISO_TEXT, EV_TEXT, WB_TEXT, PANEL_VISIBILITY, SELECTED_PARAM, CLICK_LISTENER
    }
}
