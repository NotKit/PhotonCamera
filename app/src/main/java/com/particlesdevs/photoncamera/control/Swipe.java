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
    private ZoomGestureListener zoomGestureListener;

    /** Notified on every handled pinch-to-zoom movement (used to reveal the zoom slider). */
    public interface ZoomGestureListener {
        void onZoomGesture();
    }

    public void setZoomGestureListener(ZoomGestureListener listener) {
        this.zoomGestureListener = listener;
    }

    public Swipe(CameraFragment cameraFragment) {
        this.cameraFragment = cameraFragment;
        this.captureController = cameraFragment.getCaptureController();
    }

    public void init() {
        manualModeConsole = cameraFragment.getManualModeConsole();
        cameraFragmentViewModel = cameraFragment.getCameraFragmentViewModel();
        // A knob left open when the panel was closed programmatically (e.g. by a
        // previous resume) would stay visible under the hidden panel; retract it
        // so the selector state matches what is actually on screen.
        manualModeConsole.retractAllKnobs();
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

    /** One step of a pinch, as the ratio between this span and the last. */
    public void onPinch(float scaleFactor) {
        // Ignore pinch while a burst/processing is active or the manual
        // panel is open, to avoid fighting the manual controls.
        if (manualModeConsole == null || manualModeConsole.isPanelVisible() || CaptureController.isProcessing) {
            return;
        }
        float current = captureController.getZoomRatio();
        float newZoom = current * scaleFactor;
        // The crop is always centered (the HAL's CONTROL_ZOOM_RATIO, and
        // therefore the preview, zooms to sensor center), so pass the
        // centered focal point to keep the saved JPEG/RAW matching the
        // viewfinder exactly.
        captureController.setZoom(newZoom, 0.5f, 0.5f);
        cameraFragmentViewModel.setZoomRatio(captureController.getZoomRatio());
        cameraFragmentViewModel.setZoomOffNative(
                !captureController.isZoomOnNative(captureController.getZoomRatio()));
        if (zoomGestureListener != null) zoomGestureListener.onZoomGesture();
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
            // Capture before the resets below clear the values: if no knob was
            // touched, there is nothing to re-apply and the preview session can
            // be left alone, making close as cheap as open.
            boolean hadManualChanges = manualModeConsole.getManualParamModel().isManualMode();
            manualModeConsole.retractAllKnobs();
            manualModeConsole.setPanelVisibility(false);
            if (hadManualChanges) {
                captureController.reset3Aparams();
            }
            cameraFragment.setManualBarExpanded(false);
        } else if (cameraFragmentViewModel != null) {
            cameraFragmentViewModel.setSettingsBarVisible(true);
        }
    }
}
