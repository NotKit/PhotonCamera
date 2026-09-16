package com.particlesdevs.photoncamera.ui.camera;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.CountDownTimer;

import com.particlesdevs.photoncamera.processing.parameters.IsoExpoSelector;
import com.particlesdevs.photoncamera.util.Log;

import androidx.lifecycle.Observer;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.api.CameraMode;
import com.particlesdevs.photoncamera.app.PhotonCamera;
import com.particlesdevs.photoncamera.capture.CaptureController;
import com.particlesdevs.photoncamera.composeui.state.CameraUiEvent;
import com.particlesdevs.photoncamera.composeui.state.SettingType;
import com.particlesdevs.photoncamera.control.CountdownTimer;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.ui.camera.model.TopBarSettingsData;

/**
 * Turns what the user did on the camera screen into camera actions.
 * <p>
 * The screen sends a {@link CameraUiEvent} that says what was pressed, where the View
 * tree could only say which id was clicked.
 */
final class CameraUIController implements CameraUIEventsListener,
        Observer<TopBarSettingsData<?, ?>> {
    private static final String TAG = "CameraUIController";
    private final CameraFragment cameraFragment;
    private CountDownTimer countdownTimer;

    public CameraUIController(CameraFragment cameraFragment) {
        this.cameraFragment = cameraFragment;
    }

    @Override
    public void onEvent(CameraUiEvent event) {
        if (event instanceof CameraUiEvent.Shutter) {
            onShutter();
        } else if (event instanceof CameraUiEvent.OpenSettings) {
            cameraFragment.launchSettings();
        } else if (event instanceof CameraUiEvent.OpenGallery) {
            cameraFragment.launchGallery();
        } else if (event instanceof CameraUiEvent.FlipCamera) {
            setID(cameraFragment.cycler(PreferenceKeys.getCameraID()));
            restartCamera();
        } else if (event instanceof CameraUiEvent.SelectAux) {
            onAuxSelected(((CameraUiEvent.SelectAux) event).getCameraId());
        } else if (event instanceof CameraUiEvent.SelectMode) {
            onCameraModeChanged(CameraMode.valueOf(((CameraUiEvent.SelectMode) event).getMode().ordinal()));
        } else if (event instanceof CameraUiEvent.ToggleHdrx) {
            applySetting(SettingType.HDRX, PreferenceKeys.isHdrXOn() ? 0 : 1);
            cameraFragment.showSnackBar(cameraFragment.getString(R.string.hdrx) + ':' + onOff(PreferenceKeys.isHdrXOn()));
        } else if (event instanceof CameraUiEvent.ToggleEis) {
            applySetting(SettingType.EIS, PreferenceKeys.isEisPhotoOn() ? 0 : 1);
            cameraFragment.showSnackBar(cameraFragment.getString(R.string.eis_toggle_text) + ':' + onOff(PreferenceKeys.isEisPhotoOn()));
        } else if (event instanceof CameraUiEvent.ToggleFps) {
            applySetting(SettingType.FPS_60, (PreferenceKeys.getFpsMode() + 1) % 4);
        } else if (event instanceof CameraUiEvent.ToggleQuad) {
            applySetting(SettingType.QUAD, PreferenceKeys.isQuadBayerOn() ? 0 : 1);
            cameraFragment.showSnackBar(cameraFragment.getString(R.string.quad_bayer_toggle_text) + ':' + onOff(PreferenceKeys.isQuadBayerOn()));
        } else if (event instanceof CameraUiEvent.ToggleGrid) {
            int count = cameraFragment.getResources().getStringArray(R.array.vf_grid_entryvalues).length;
            applySetting(SettingType.GRID, (PreferenceKeys.getGridValue() + 1) % count);
        } else if (event instanceof CameraUiEvent.ToggleFlash) {
            applySetting(SettingType.FLASH, (PreferenceKeys.getAeMode() + 1) % 2);
        } else if (event instanceof CameraUiEvent.ToggleTimer) {
            int count = cameraFragment.getResources().getIntArray(R.array.countdowntimer_entryvalues).length;
            applySetting(SettingType.TIMER, (PreferenceKeys.getCountdownTimerIndex() + 1) % count);
        } else if (event instanceof CameraUiEvent.SetSetting) {
            CameraUiEvent.SetSetting s = (CameraUiEvent.SetSetting) event;
            applySetting(s.getType(), s.getValue());
        } else if (event instanceof CameraUiEvent.SetSettingsBarVisible) {
            cameraFragment.getCameraFragmentViewModel()
                    .setSettingsBarVisible(((CameraUiEvent.SetSettingsBarVisible) event).getVisible());
        } else if (event instanceof CameraUiEvent.ToggleManualBar) {
            if (cameraFragment.getManualModeConsole().isPanelVisible()) cameraFragment.mSwipe.SwipeDown();
            else cameraFragment.mSwipe.SwipeUp();
        } else if (event instanceof CameraUiEvent.SwipeUp) {
            cameraFragment.mSwipe.SwipeUp();
        } else if (event instanceof CameraUiEvent.SwipeDown) {
            cameraFragment.mSwipe.SwipeDown();
        } else if (event instanceof CameraUiEvent.ViewfinderTap) {
            CameraUiEvent.ViewfinderTap t = (CameraUiEvent.ViewfinderTap) event;
            cameraFragment.mSwipe.onTap(t.getX(), t.getY());
        } else if (event instanceof CameraUiEvent.ViewfinderLongPress) {
            CameraUiEvent.ViewfinderLongPress t = (CameraUiEvent.ViewfinderLongPress) event;
            cameraFragment.mSwipe.onLongPress(t.getX(), t.getY());
        }
    }

    private void onShutter() {
        switch (PhotonCamera.getSettings().selectedMode) {
            case PHOTO:
            case MOTION:
            case NIGHT:
                if (countdownTimer != null) resetTimer();
                else startTimer();
                break;
            case UNLIMITED:
            case RAWVIDEO:
                if (!cameraFragment.captureController.onUnlimited) {
                    cameraFragment.captureController.callUnlimitedStart();
                    cameraFragment.cameraUiHost.setShutterRecording(true);
                } else {
                    cameraFragment.captureController.callUnlimitedEnd();
                    cameraFragment.cameraUiHost.setShutterRecording(false);
                }
                break;
            case VIDEO:
                if (!cameraFragment.captureController.mIsRecordingVideo) {
                    cameraFragment.captureController.VideoStart();
                    cameraFragment.cameraUiHost.setShutterRecording(true);
                } else {
                    cameraFragment.captureController.VideoEnd();
                    cameraFragment.cameraUiHost.setShutterRecording(false);
                }
                break;
        }
    }

    /**
     * One place where a setting is written and the camera told about it, whether the
     * value came from a top-bar tap or from a settings-bar button.
     */
    private void applySetting(SettingType type, int value) {
        switch (type) {
            case FLASH:
                PreferenceKeys.setAeMode(value);
                cameraFragment.captureController.setPreviewAEModeRebuild(value);
                break;
            case HDRX:
                PreferenceKeys.setHdrX(value == 1);
                CaptureController.setTargetFormat(value == 1
                        ? CaptureController.RAW_FORMAT : CaptureController.YUV_FORMAT);
                restartCamera();
                break;
            case QUAD:
                PreferenceKeys.setQuadBayer(value == 1);
                restartCamera();
                break;
            case GRID:
                PreferenceKeys.setGridValue(value);
                cameraFragment.invalidateSurfaceView();
                break;
            case FPS_60:
                PreferenceKeys.setFpsMode(value);
                cameraFragment.captureController.applyFpsRange();
                break;
            case TIMER:
                PreferenceKeys.setCountdownTimerIndex(value);
                break;
            case EIS:
                PreferenceKeys.setEisPhoto(value == 1);
                if (PhotonCamera.getSettings().selectedMode == CameraMode.VIDEO) {
                    cameraFragment.captureController.applyVideoStabilization();
                }
                break;
            case RAW:
                PreferenceKeys.setSaveRaw(value);
                break;
            case BATTERY_SAVER:
                PreferenceKeys.setBatterySaver(value == 1);
                break;
            case BRACKETING:
                PreferenceKeys.setBracketingMode(value);
                IsoExpoSelector.HDR = value > 0;
                break;
            case AE_METERING_STD:
                PreferenceKeys.setAeMeteringStd(value);
                cameraFragment.captureController.applyAeMetering();
                break;
        }
        cameraFragment.updateSettingsBar();
    }

    private int getTimerValue(Context context) {
        int[] timerValues = context.getResources().getIntArray(R.array.countdowntimer_entryvalues);
        return timerValues[PreferenceKeys.getCountdownTimerIndex()];
    }

    private void startTimer() {
        cameraFragment.cameraUiHost.setCounting(true);
        this.countdownTimer = new CountdownTimer(
                cameraFragment.cameraUiHost::setTimerCount,
                getTimerValue(cameraFragment.requireContext()) * 1000L, 1000,
                this::onTimerFinished).start();
    }

    private void resetTimer() {
        if (this.countdownTimer != null) this.countdownTimer.cancel();
        this.countdownTimer = null;
        if (cameraFragment.cameraUiHost != null) cameraFragment.cameraUiHost.setCounting(false);
    }

    @Override
    public void onCameraModeChanged(CameraMode cameraMode) {
        PreferenceKeys.setCameraModeOrdinal(cameraMode.ordinal());
        Log.d(TAG, "onCameraModeChanged() called with: cameraMode = [" + cameraMode + "]");
        cameraFragment.applyCameraMode(cameraMode);
        this.restartCamera();
    }

    @Override
    public void onPause() {
        this.resetTimer();
    }

    private void onAuxSelected(String id) {
        Log.d(TAG, "onAuxSelected() called with: id = [" + id + "]");
        if (id != null
                && com.particlesdevs.photoncamera.api.LogicalCameraResolver.isMemberId(id)
                && cameraFragment.captureController != null
                && cameraFragment.captureController.isVideoLogicalActive()) {
            // Logical member tap: seamless zoom on the open device, no reopen.
            cameraFragment.captureController.zoomToLogicalMember(id);
            return;
        }
        setID(id);
        restartCamera();
    }

    private void setID(String input) {
        PreferenceKeys.setCameraID(String.valueOf(input));
    }

    private void restartCamera() {
        this.resetTimer();
        cameraFragment.captureController.restartCamera();
    }

    private String onOff(boolean value) {
        return value ? "On" : "Off";
    }

    private void onTimerFinished() {
        this.countdownTimer = null;
        cameraFragment.cameraUiHost.setCounting(false);
        cameraFragment.cameraUiHost.activateShutterButton(false);
        cameraFragment.captureController.takePicture();
    }

    /**
     * Still fed by SettingsBarEntryProvider's LiveData. The screen sends SetSetting
     * directly, so this only carries values set from elsewhere.
     */
    @Override
    @SuppressLint("NonConstantResourceId")
    public void onChanged(TopBarSettingsData<?, ?> topBarSettingsData) {
        if (topBarSettingsData == null || topBarSettingsData.getType() == null
                || topBarSettingsData.getValue() == null) return;
        if (!(topBarSettingsData.getType() instanceof com.particlesdevs.photoncamera.settings.SettingType)) return;
        com.particlesdevs.photoncamera.settings.SettingType type =
                (com.particlesdevs.photoncamera.settings.SettingType) topBarSettingsData.getType();
        Object value = topBarSettingsData.getValue();
        applySetting(SettingType.valueOf(type.name()), value instanceof Integer ? (Integer) value : 0);
    }
}
