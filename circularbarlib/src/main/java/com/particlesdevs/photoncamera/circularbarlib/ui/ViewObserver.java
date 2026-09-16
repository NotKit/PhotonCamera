package com.particlesdevs.photoncamera.circularbarlib.ui;

import android.app.Activity;
import android.provider.Settings;
import android.view.OrientationEventListener;
import android.view.Surface;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.particlesdevs.photoncamera.circularbarlib.R;
import com.particlesdevs.photoncamera.circularbarlib.api.ManualUi;
import com.particlesdevs.photoncamera.circularbarlib.model.KnobModel;
import com.particlesdevs.photoncamera.circularbarlib.model.ManualModeModel;
import com.particlesdevs.photoncamera.circularbarlib.model.ManualParam;
import com.particlesdevs.photoncamera.circularbarlib.model.ParamClickListener;
import com.particlesdevs.photoncamera.circularbarlib.ui.views.knobview.KnobView;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Observable;

/**
 * Created by vibhorSrv
 */
public class ViewObserver implements ManualUi {
    private final Activity activity;
    private final RelativeLayout manualMode;
    private final KnobView knobView;
    private final Map<ManualParam, TextView> options = new EnumMap<>(ManualParam.class);
    private final List<TextView> textViews;
    private final OrientationEventListener orientationEventListener;
    private final LinearLayout buttonsContainer;
    private int rotation = 0;


    public ViewObserver(Activity activity) {
        this.activity = activity;
        manualMode = findViewById(R.id.manual_mode);
        buttonsContainer = findViewById(R.id.buttons_container);
        knobView = findViewById(R.id.knobView);
        options.put(ManualParam.ISO, findViewById(R.id.iso_option_tv));
        options.put(ManualParam.EXPOSURE, findViewById(R.id.exposure_option_tv));
        options.put(ManualParam.EV, findViewById(R.id.ev_option_tv));
        options.put(ManualParam.FOCUS, findViewById(R.id.focus_option_tv));
        options.put(ManualParam.WB, findViewById(R.id.wb_option_tv));
        textViews = new ArrayList<>(options.values());
        orientationEventListener = new OrientationEventListener(activity.getBaseContext()) {
            private static final int ROT_DUR = 350;
            private int prevOrientation = OrientationEventListener.ORIENTATION_UNKNOWN;

            @Override
            public void onOrientationChanged(int orientation) {
                if (android.provider.Settings.System.getInt(activity.getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 0) == 0) // 0 = Auto Rotate Disabled
                    return;
                int currentOrientation = OrientationEventListener.ORIENTATION_UNKNOWN;
                if (orientation >= 340 || orientation < 20 && rotation != 0) {
                    currentOrientation = Surface.ROTATION_0;
                    rotation = 0;

                } else if (orientation >= 70 && orientation < 110 && rotation != 90) {
                    currentOrientation = Surface.ROTATION_270;
                    rotation = -90;

                } else if (orientation >= 160 && orientation < 200 && rotation != 180) {
                    currentOrientation = Surface.ROTATION_180;
                    rotation = 180;

                } else if (orientation >= 250 && orientation < 290 && rotation != 270) {
                    currentOrientation = Surface.ROTATION_90;
                    rotation = 90;
                }
                if (prevOrientation != currentOrientation && orientation != OrientationEventListener.ORIENTATION_UNKNOWN) {
                    prevOrientation = currentOrientation;
                    if (currentOrientation != OrientationEventListener.ORIENTATION_UNKNOWN) {
                        Binding.rotateKnobView(knobView, rotation);
                        Binding.rotateManualOptionContent(buttonsContainer, rotation, ROT_DUR);
                    }
                }
            }
        };
    }

    @Override
    public void onResume() {
        if (orientationEventListener != null && orientationEventListener.canDetectOrientation()) {
            orientationEventListener.enable();
        }
    }

    @Override
    public void onPause() {
        if (orientationEventListener != null) {
            orientationEventListener.disable();
        }
    }

    private <T extends View> T findViewById(int id) {
        return activity.findViewById(id);
    }

    /**
     * The selection pill is the background of the (unrotated) cell around the
     * label, so the cell mirrors the label's selected state.
     */
    private void setOptionSelected(TextView textView, boolean selected) {
        textView.setSelected(selected);
        if (textView.getParent() instanceof View) {
            ((View) textView.getParent()).setSelected(selected);
        }
    }

    /**
     * The remembered control (inner ruler) mirrors its label's activated state
     * onto the cell, where the same pill draws as a thin ring.
     */
    private void setOptionRemembered(TextView textView, boolean remembered) {
        textView.setActivated(remembered);
        if (textView.getParent() instanceof View) {
            ((View) textView.getParent()).setActivated(remembered);
        }
    }

    private void bindClicks(ParamClickListener listener) {
        for (Map.Entry<ManualParam, TextView> entry : options.entrySet()) {
            ManualParam param = entry.getKey();
            TextView textView = entry.getValue();
            if (listener == null) {
                textView.setOnClickListener(null);
                textView.setOnLongClickListener(null);
                continue;
            }
            textView.setOnClickListener(v -> listener.onParamClicked(param));
            textView.setOnLongClickListener(v -> {
                listener.onParamLongClicked(param);
                return true;
            });
        }
    }

    @Override
    public void update(Observable o, Object arg) {
        if (o != null && arg != null) {
            if (o instanceof KnobModel) {
                KnobModel knobModel = (KnobModel) o;
                switch ((KnobModel.KnobModelFields) arg) {
                    case RESET:
                        Binding.resetKnob(knobView, knobModel.isKnobResetCalled());
                        break;
                    case VISIBILITY:
                        Binding.setKnobVisibility(manualMode, knobView, knobModel.isKnobVisible());
                        break;
                    case MANUAL_MODEL:
                    case SECONDARY_MODEL:
                        Binding.setModelToKnob(knobView, knobModel.getManualModel(), knobModel.getSecondaryManualModel());
                        break;
                }
            }
            if (o instanceof ManualModeModel) {
                ManualModeModel manualModeModel = (ManualModeModel) o;
                switch ((ManualModeModel.ManualModelFields) arg) {
                    case EV_TEXT:
                        options.get(ManualParam.EV).setText(manualModeModel.getEvText());
                        break;
                    case EXP_TEXT:
                        options.get(ManualParam.EXPOSURE).setText(manualModeModel.getExposureText());
                        break;
                    case ISO_TEXT:
                        options.get(ManualParam.ISO).setText(manualModeModel.getIsoText());
                        break;
                    case FOCUS_TEXT:
                        options.get(ManualParam.FOCUS).setText(manualModeModel.getFocusText());
                        break;
                    case WB_TEXT:
                        options.get(ManualParam.WB).setText(manualModeModel.getWbText());
                        break;
                    case CLICK_LISTENER:
                        bindClicks(manualModeModel.getParamClickListener());
                        break;
                    case SELECTED_PARAM:
                        ManualParam selected = manualModeModel.getSelectedParam();
                        ManualParam secondary = manualModeModel.getSecondaryParam();
                        TextView selectedView = selected == null ? null : options.get(selected);
                        TextView rememberedView = secondary == null ? null : options.get(secondary);
                        for (TextView textView : textViews) {
                            setOptionSelected(textView, textView == selectedView);
                            setOptionRemembered(textView, textView == rememberedView);
                        }
                        break;
                    case PANEL_VISIBILITY:
                        Binding.togglePanelVisibility(manualMode, manualModeModel.isManualPanelVisible());
                        break;

                }
            }
        }

    }
}
