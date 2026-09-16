package com.particlesdevs.photoncamera.control;

/**
 * What a tap needs from the viewfinder.
 *
 * <p>{@link TouchFocus} runs the 3A protocol; everything it wants from the screen
 * is here: the preview's geometry, the display rotation, a thread to post work
 * on, and the two indicators it draws (the focus circle and the spot-WB
 * reticle). The Android build satisfies this with Views
 * (ui/camera/views/ViewFocusIndicator); the Compose port with whatever draws
 * the scene.
 */
public interface FocusIndicator {
    /** The preview box in pixels; a tap's coordinates are relative to it. */
    int getPreviewWidth();

    int getPreviewHeight();

    /** Display rotation in degrees, or -1 when the viewfinder is not attached. */
    int getDisplayRotation();

    void hapticLongPress();

    /* the indicators live on one thread; this is how work reaches it */
    void post(Runnable action);

    void postDelayed(Runnable action, long delayMs);

    void cancel(Runnable action);

    void showFocusCircle(float x, float y);

    void hideFocusCircle();

    /** CameraMetadata.CONTROL_AF_STATE_*, for the circle's colour. */
    void setAfState(int afState);

    void showSpotWb(float x, float y);

    /** The measurement failed; the reticle says why before it goes. */
    void setSpotWbError(String reason);

    void hideSpotWb();

    /** Device orientation in degrees, so the reticle's label stays upright. */
    void setOrientation(int orientation);
}
