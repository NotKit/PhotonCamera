package com.particlesdevs.photoncamera.capture;

import android.graphics.Point;
import android.graphics.SurfaceTexture;

/**
 * The viewfinder, as the capture path needs it.
 *
 * <p>{@link CaptureController} drives the camera; everything it wants from the
 * screen is here: the {@link SurfaceTexture} the preview stream is drawn into,
 * whether that texture exists yet, the geometry it should be shown at, and the
 * display it is on. The Android build satisfies this with a GL view
 * (ui/camera/views/viewfinder/GLPreviewSurface); the Compose port with whatever
 * draws the scene.
 */
public interface PreviewSurface {
    /** Told when the texture appears, changes size, or goes away. */
    interface Listener {
        void onPreviewSurfaceAvailable(SurfaceTexture texture, int width, int height);

        void onPreviewSurfaceSizeChanged(SurfaceTexture texture, int width, int height);

        /** true when the caller releases the texture itself. */
        boolean onPreviewSurfaceDestroyed(SurfaceTexture texture);
    }

    /** null while the surface has not come up yet. */
    SurfaceTexture getSurfaceTexture();

    boolean isAvailable();

    void setListener(Listener listener);

    /** The shape the preview is laid out at, in preview-buffer pixels. */
    void setAspectRatio(int width, int height);

    void setCameraSize(Point size);

    /** Sensor orientation plus the device's, in degrees. */
    void setOrientation(int degrees);

    void setMirror(boolean mirror);

    /** The display's real size in pixels; (0, 0) when there is no display yet. */
    Point getDisplaySize();
}
