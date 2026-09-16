package com.particlesdevs.photoncamera.composeui.state

/**
 * What the camera screen can ask the host to do. Replaces the View-id switch in
 * CameraUIController.onClick with something that carries its own meaning.
 */
sealed interface CameraUiEvent {
    object Shutter : CameraUiEvent
    object FlipCamera : CameraUiEvent
    object OpenGallery : CameraUiEvent
    object OpenSettings : CameraUiEvent
    object ToggleFlash : CameraUiEvent
    object ToggleTimer : CameraUiEvent
    object ToggleGrid : CameraUiEvent
    object ToggleQuad : CameraUiEvent
    object ToggleEis : CameraUiEvent
    object ToggleFps : CameraUiEvent
    object ToggleHdrx : CameraUiEvent
    object ToggleManualBar : CameraUiEvent
    data class SelectMode(val mode: CameraMode) : CameraUiEvent
    data class SelectAux(val cameraId: String) : CameraUiEvent
    data class SetSettingsBarVisible(val visible: Boolean) : CameraUiEvent
    data class SetSetting(val type: SettingType, val value: Int) : CameraUiEvent

    /** Swipe.java's gestures over the preview. Coordinates are relative to the preview box. */
    data class ViewfinderTap(val x: Float, val y: Float) : CameraUiEvent
    data class ViewfinderLongPress(val x: Float, val y: Float) : CameraUiEvent
    object SwipeUp : CameraUiEvent
    object SwipeDown : CameraUiEvent
}
