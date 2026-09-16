package com.particlesdevs.photoncamera.control;

import com.particlesdevs.photoncamera.util.Log;

import com.particlesdevs.photoncamera.circularbarlib.api.ManualModeConsole;
import com.particlesdevs.photoncamera.circularbarlib.control.ManualParamModel;
import com.particlesdevs.photoncamera.capture.CaptureController;
import com.particlesdevs.photoncamera.ui.camera.CameraFragment;
import com.particlesdevs.photoncamera.ui.camera.viewmodel.CameraFragmentViewModel;

/**
 * The gestures over the viewfinder.
 * <p>
 * The Compose screen detects them now, so what is left here is what each one means.
 * Coordinates arrive relative to the preview box, which is what TouchFocus wants,
 * so there is no longer a rectangle to hit-test against.
 */
public class Swipe {
    private static final String TAG = "Swipe";
    private final CameraFragment cameraFragment;
    private final CaptureController captureController;
    private ManualModeConsole manualModeConsole;
    private CameraFragmentViewModel cameraFragmentViewModel;

    public Swipe(CameraFragment cameraFragment) {
        this.cameraFragment = cameraFragment;
        this.captureController = cameraFragment.getCaptureController();
    }

    public void init() {
        manualModeConsole = cameraFragment.getManualModeConsole();
        cameraFragmentViewModel = cameraFragment.getCameraFragmentViewModel();
        manualModeConsole.setPanelVisibility(false);
    }

    /** A tap puts the settings bar away and focuses where it landed. */
    public void onTap(float x, float y) {
        if (cameraFragmentViewModel != null) cameraFragmentViewModel.setSettingsBarVisible(false);
        if (manualModeConsole == null || cameraFragment.getTouchFocus() == null) return;
        if (manualModeConsole.getManualParamModel().getCurrentFocusValue() == ManualParamModel.FOCUS_AUTO)
            cameraFragment.getTouchFocus().processTouchToFocus(x, y);
    }

    /** A long press measures spot white balance there. */
    public void onLongPress(float x, float y) {
        if (cameraFragment.getTouchFocus() == null) return;
        cameraFragment.getTouchFocus().processSpotWb(x, y);
    }

    public void SwipeUp() {
        if (cameraFragmentViewModel != null && cameraFragmentViewModel.isSettingsBarVisible()) {
            cameraFragmentViewModel.setSettingsBarVisible(false);
            return;
        }
        Log.d(TAG, "SwipeUp: manual panel");
        manualModeConsole.setPanelVisibility(true);
        cameraFragment.setManualBarExpanded(true);
        cameraFragment.getTouchFocus().resetFocusCircle();
    }

    public void SwipeDown() {
        if (manualModeConsole.isPanelVisible()) {
            cameraFragment.getTouchFocus().resetFocusCircle();
            captureController.reset3Aparams();
            manualModeConsole.setPanelVisibility(false);
            manualModeConsole.retractAllKnobs();
            cameraFragment.setManualBarExpanded(false);
        } else if (cameraFragmentViewModel != null) {
            cameraFragmentViewModel.setSettingsBarVisible(true);
        }
    }
}
