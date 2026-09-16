package com.particlesdevs.photoncamera.api;

import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.params.BlackLevelPattern;
import android.hardware.camera2.params.InputConfiguration;
import android.hardware.camera2.params.OutputConfiguration;
import android.media.Image;
import android.os.Handler;

import com.particlesdevs.photoncamera.util.Log;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * The camera2 corners AOSP does not open up.
 *
 * <p>This used to reach them with java.lang.reflect: CameraMetadataNative's
 * package-private writers, CameraMetadata.getKeys, createCustomCaptureSession.
 * Everything with a public equivalent now uses it; the rest is a no-op that says
 * so once, because a silent failure here looks exactly like a camera reporting
 * odd values.
 *
 * <p>What the writers did and no longer do: the per-device corrections in
 * {@link Camera2ApiAutoFix} (black level, white level, exposure and sensitivity
 * ranges, tonemap curve points) no longer overwrite what the HAL reported, so
 * the HAL's own numbers are what the pipeline sees.
 */
public class CameraReflectionApi {
    private static final String TAG = "CameraAPI";
    private static boolean overrideReported = false;

    /** Says once per run that a metadata override was dropped. */
    private static void overrideUnavailable(String what) {
        if (overrideReported)
            return;
        overrideReported = true;
        Log.w(TAG, "metadata overrides are unavailable on this build (first: " + what
                + "); the HAL's own values are used");
    }

    /**
     * The characteristics keys this camera reports. AOSP's hidden getKeys overload
     * could also list synthetic keys and filter by tag; the public one lists what
     * the camera has, and every caller here passes a null filter.
     */
    public static ArrayList<Object> getCameraCharacteristicsKeys(
            CameraCharacteristics cameraCharacteristics,
            int[] filterTags,
            boolean includeSynthetic) {
        if (cameraCharacteristics == null)
            return null;
        return new ArrayList<Object>(cameraCharacteristics.getKeys());
    }

    public static ArrayList<Object> getCaptureRequestKeys(
            CaptureRequest captureRequest,
            int[] filterTags,
            boolean includeSynthetic) {
        if (captureRequest == null)
            return null;
        return new ArrayList<Object>(captureRequest.getKeys());
    }

    public static <T> void set(CameraCharacteristics characteristics, CameraCharacteristics.Key<T> key, T value) {
        overrideUnavailable(key == null ? "null" : key.getName());
    }

    public static <T> void set(CaptureResult.Key<T> key, T value) {
        overrideUnavailable(key == null ? "null" : key.getName());
    }

    public static <T> void set(CaptureResult.Key<T> key, T value, CaptureResult res) {
        overrideUnavailable(key == null ? "null" : key.getName());
    }

    public static <T> void set(CaptureRequest request, CaptureRequest.Key<T> key, T value) {
        overrideUnavailable(key == null ? "null" : key.getName());
    }

    /** The black level the HAL reported stands: the offset array behind it is private. */
    public static void PatchBL(BlackLevelPattern pattern, int[] bl) {
        overrideUnavailable("android.sensor.blackLevelPattern");
    }

    /**
     * Swapping an Image.Plane's buffer was a way to hand the pipeline a frame of
     * its own in place of the sensor's. The plane's buffer is not writable
     * through the public API, and nothing calls this.
     */
    public static ByteBuffer replaceImageBuffer(Image.Plane plane, ByteBuffer buffer) {
        return null;
    }

    /**
     * AOSP's createCustomCaptureSession takes an operating mode a public session
     * cannot ask for (the vendor high-speed and ZSL modes). The public
     * output-configuration session is the same thing in the default mode.
     */
    public static void createCustomCaptureSession(CameraDevice cameraDevice,
                                                  InputConfiguration inputConfig,
                                                  List<OutputConfiguration> outputs,
                                                  int operatingMode,
                                                  CameraCaptureSession.StateCallback callback,
                                                  Handler handler) throws CameraAccessException {
        cameraDevice.createCaptureSessionByOutputConfigurations(outputs, callback, handler);
    }
}
