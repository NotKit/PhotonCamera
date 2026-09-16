package com.particlesdevs.photoncamera.ui.camera;

import com.particlesdevs.photoncamera.api.CameraMode;
import com.particlesdevs.photoncamera.composeui.state.CameraUiEvent;

interface CameraUIEventsListener {
    void onEvent(CameraUiEvent event);

    void onCameraModeChanged(CameraMode cameraMode);

    void onPause();
}
