package com.particlesdevs.photoncamera.circularbarlib.console;

import android.app.Activity;
import android.content.Context;
import android.hardware.camera2.CameraCharacteristics;
import android.os.Vibrator;

import com.particlesdevs.photoncamera.circularbarlib.api.ManualModeConsole;
import com.particlesdevs.photoncamera.circularbarlib.api.ManualUi;
import com.particlesdevs.photoncamera.circularbarlib.api.ManualUiFactory;
import com.particlesdevs.photoncamera.circularbarlib.camera.CameraProperties;
import com.particlesdevs.photoncamera.circularbarlib.control.ManualParamModel;
import com.particlesdevs.photoncamera.circularbarlib.control.knob.KnobItemInfo;
import com.particlesdevs.photoncamera.circularbarlib.control.models.EvModel;
import com.particlesdevs.photoncamera.circularbarlib.control.models.FocusModel;
import com.particlesdevs.photoncamera.circularbarlib.control.models.IsoModel;
import com.particlesdevs.photoncamera.circularbarlib.control.models.ManualModel;
import com.particlesdevs.photoncamera.circularbarlib.control.models.ShutterModel;
import com.particlesdevs.photoncamera.circularbarlib.control.models.WbModel;
import com.particlesdevs.photoncamera.circularbarlib.model.KnobModel;
import com.particlesdevs.photoncamera.circularbarlib.model.ManualModeModel;
import com.particlesdevs.photoncamera.circularbarlib.model.ManualParam;
import com.particlesdevs.photoncamera.circularbarlib.model.ParamClickListener;

import java.util.Observer;

/**
 * Responsible for initialising and updating {@link KnobModel} and
 * {@link ManualModeModel}
 * <p>
 * This class also manages the attaching/detaching of {@link ManualModel}
 * subclasses to the knob widget and setting listeners to models
 * <p>
 * Authors - Vibhor, KillerInk
 */
public class ManualModeConsoleImpl implements ManualModeConsole {
    private static final String TAG = "ManualModeConsole";
    private static ManualModeConsoleImpl sInstance;
    private final ManualModeModel manualModeModel;
    private final KnobModel knobModel;
    private final ManualParamModel manualParamModel = new ManualParamModel();
    private ManualModel<?> mfModel, isoModel, expoTimeModel, evModel, wbModel, selectedModel;
    private ManualUiFactory uiFactory;
    private ManualUi manualUi;
    private boolean preserveManualWb = false;

    private ManualModeConsoleImpl() {
        this.manualModeModel = new ManualModeModel();
        this.knobModel = new KnobModel();
    }

    public static ManualModeConsoleImpl getInstance() {
        if (sInstance == null) {
            sInstance = newInstance();
        }
        return sInstance;
    }

    public static ManualModeConsoleImpl newInstance() {
        return new ManualModeConsoleImpl();
    }

    /** Set by the platform before {@link #init}; it builds the manual-mode surface. */
    public void setUiFactory(ManualUiFactory uiFactory) {
        this.uiFactory = uiFactory;
    }

    public ManualModeModel getManualModeModel() {
        return manualModeModel;
    }

    @Override
    public void addParamObserver(Observer observer) {
        manualParamModel.addObserver(observer);
    }

    @Override
    public void removeParamObservers() {
        manualParamModel.deleteObservers();
    }

    @Override
    public ManualParamModel getManualParamModel() {
        return manualParamModel;
    }

    public KnobModel getKnobModel() {
        return knobModel;
    }

    @Override
    public void init(Activity activity, CameraCharacteristics cameraCharacteristics) {
        if (uiFactory != null) {
            manualUi = uiFactory.create(activity);
        }
        addObserver();
        addKnobs(activity, cameraCharacteristics);
        setupOnClickListeners();
        setAutoText();
    }

    @Override
    public void onResume() {
        ManualUi ui = manualUi;
        if (ui != null) {
            ui.onResume();
        }
        addObserver();
    }

    @Override
    public void onPause() {
        ManualUi ui = manualUi;
        if (ui != null) {
            ui.onPause();
        }
        removeObservers();
    }

    @Override
    public void onDestroy() {
        sInstance = null;
    }

    private void addObserver() {
        ManualUi ui = manualUi;
        if (ui != null) {
            removeObservers();
            knobModel.addObserver(ui);
            manualModeModel.addObserver(ui);
        }
    }

    private void removeObservers() {
        knobModel.deleteObservers();
        manualModeModel.deleteObservers();
    }

    private void addKnobs(Context context, CameraCharacteristics cameraCharacteristics) {
        CameraProperties cameraProperties = new CameraProperties(cameraCharacteristics);
        double preservedWb = (this.preserveManualWb) ? manualParamModel.getCurrentWbValue() : ManualParamModel.WB_AUTO;
        manualParamModel.reset();
        Vibrator v = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        mfModel = new FocusModel(cameraCharacteristics, cameraProperties.focusRange, manualParamModel,
                manualModeModel::setFocusText, v);
        evModel = new EvModel(cameraCharacteristics, cameraProperties.evRange, manualParamModel,
                manualModeModel::setEvText, v);
        ((EvModel) evModel).setEvStep(
                (cameraCharacteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP).floatValue()));
        isoModel = new IsoModel(cameraCharacteristics, cameraProperties.isoRange, manualParamModel,
                manualModeModel::setIsoText, v);
        expoTimeModel = new ShutterModel(cameraCharacteristics, cameraProperties.expRange, manualParamModel,
                manualModeModel::setExposureText, v);
        wbModel = new WbModel(cameraCharacteristics, null, manualParamModel,
                manualModeModel::setWbText, v);

        // Restore manual White Balance temperature across camera lenses if enabled
        if (preservedWb != ManualParamModel.WB_AUTO) {
            for (KnobItemInfo item : wbModel.getKnobInfoList()) {
                if (Math.abs(item.value - preservedWb) < 0.1) {
                    wbModel.onItemSelected(item);
                    manualModeModel.setWbText(item.text);
                    break;
                }
            }
        }

        knobModel.setKnobVisible(false);
        manualModeModel.setSelectedParam(null);
    }

    @Override
    public void setPanelVisibility(boolean visible) {
        manualModeModel.setManualPanelVisible(visible);
        if (!visible) {
            manualParamModel.reset(this.preserveManualWb);
        }
    }

    @Override
    public boolean isManualMode() {
        return manualParamModel.isManualMode();
    }

    @Override
    public void resetAllValues() {
        manualParamModel.reset();
    }

    @Override
    public boolean isPanelVisible() {
        return manualModeModel.isManualPanelVisible();
    }

    private void setupOnClickListeners() {
        manualModeModel.setParamClickListener(new ParamClickListener() {
            @Override
            public void onParamClicked(ManualParam param) {
                ManualModel<?> model = modelOf(param);
                if (model != null) {
                    setModelToKnob(param, model);
                }
            }

            @Override
            public void onParamLongClicked(ManualParam param) {
                ManualModel<?> model = modelOf(param);
                if (model == null) {
                    return;
                }
                if (selectedModel == model) {
                    knobModel.setKnobResetCalled(true);
                }
                model.resetModel();
            }
        });
    }

    private ManualModel<?> modelOf(ManualParam param) {
        switch (param) {
            case ISO:
                return isoModel;
            case EXPOSURE:
                return expoTimeModel;
            case EV:
                return evModel;
            case FOCUS:
                return mfModel;
            case WB:
                return wbModel;
            default:
                return null;
        }
    }

    private void setAutoText() {
        if (evModel != null)
            evModel.setAutoTxt();
        if (mfModel != null)
            mfModel.setAutoTxt();
        if (expoTimeModel != null)
            expoTimeModel.setAutoTxt();
        if (isoModel != null)
            isoModel.setAutoTxt();
        if (wbModel != null) {
            if (!this.preserveManualWb || manualParamModel.getCurrentWbValue() == ManualParamModel.WB_AUTO) {
                wbModel.setAutoTxt();
            }
        }
    }

    @Override
    public void retractAllKnobs() {
        knobModel.setKnobVisible(false);
        if (!this.preserveManualWb || manualParamModel.getCurrentWbValue() == ManualParamModel.WB_AUTO || !(selectedModel instanceof WbModel)) {
            knobModel.setKnobResetCalled(true);
        }
        selectedModel = null;
        if (mfModel != null)
            mfModel.resetModel();
        if (expoTimeModel != null)
            expoTimeModel.resetModel();
        if (isoModel != null)
            isoModel.resetModel();
        if (evModel != null)
            evModel.resetModel();
        if (wbModel != null && (!this.preserveManualWb || manualParamModel.getCurrentWbValue() == ManualParamModel.WB_AUTO))
            wbModel.resetModel();
        manualModeModel.setSelectedParam(null);
    }

    @Override
    public boolean isFocusParameterSelected() {
        return selectedModel instanceof FocusModel;
    }

    @Override
    public boolean isManualFocusModeActive() {
        ManualModel<?> focus = mfModel;
        if (focus == null) {
            return false;
        }
        KnobItemInfo currentInfo = focus.getCurrentInfo();
        return currentInfo != null && currentInfo.value != ManualParamModel.FOCUS_AUTO;
    }

    @Override
    public void setPreserveManualWb(boolean preserve) {
        this.preserveManualWb = preserve;
    }

    @Override
    public void setManualWbValue(double kelvinValue) {
        ManualModel<?> wb = wbModel;
        if (wb == null) {
            manualParamModel.setCurrentWbValue(kelvinValue);
            return;
        }

        // 1. Locate the matching knob item for the measured Kelvin value
        KnobItemInfo matchedItem = null;
        for (KnobItemInfo item : wb.getKnobInfoList()) {
            if (Math.abs(item.value - kelvinValue) < 0.1) {
                matchedItem = item;
                break;
            }
        }

        // 2. Synchronize WbModel, manual bar text, and active knob rotation
        if (matchedItem != null) {
            wb.onItemSelected(matchedItem);
            manualModeModel.setWbText(matchedItem.text);
            if (selectedModel == wb) {
                knobModel.setManualModel(wb);
            }
        } else {
            manualParamModel.setCurrentWbValue(kelvinValue);
        }
    }

    private void setModelToKnob(ManualParam param, ManualModel<?> modelToKnob) {
        if (modelToKnob == selectedModel) {
            knobModel.setManualModel(null);
            knobModel.setKnobVisible(false);
            manualModeModel.setSelectedParam(null);
            selectedModel = null;
        } else {
            if (modelToKnob.getKnobInfoList().size() > 1) {
                knobModel.setManualModel(modelToKnob);
                knobModel.setKnobVisible(true);
                manualModeModel.setSelectedParam(param);
                selectedModel = modelToKnob;
            }
        }
    }
}
