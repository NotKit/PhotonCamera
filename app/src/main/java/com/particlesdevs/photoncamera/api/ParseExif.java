package com.particlesdevs.photoncamera.api;

import android.graphics.Bitmap;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.os.Build;
import com.particlesdevs.photoncamera.util.Log;
import androidx.exifinterface.media.ExifInterface;
import com.particlesdevs.photoncamera.app.PhotonCamera;
import com.particlesdevs.photoncamera.processing.parameters.IsoExpoSelector;
import com.particlesdevs.photoncamera.processing.render.Parameters;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;


public class ParseExif {
    public static final SimpleDateFormat sFormatter;
    private static final String TAG = "ParseExif";

    static {
        sFormatter = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US);
        sFormatter.setTimeZone(TimeZone.getDefault());
    }

    public static String getTime(long exposureTime) {
        String out;
        long sec = 1000000000;
        double time = (double) (exposureTime) / sec;
        out = String.valueOf((time));
        return out;
    }

    public static String resultget(CaptureResult res, CaptureResult.Key<?> key) {
        Object out = res.get(key);
        if (out != null) return out.toString();
        else return "";
    }
    public static String requestget(CaptureRequest res, CaptureRequest.Key<?> key) {
        Object out = res.get(key);
        if (out != null) return out.toString();
        else return "";
    }

    public static ExifData parse(CaptureResult result, CaptureRequest request) {
        ExifData data = new ExifData();

        int rotation = PhotonCamera.getCaptureController().cameraRotation;
        String TAG = "ParseExif";
        Log.d(TAG, "Gravity rotation:" + PhotonCamera.getGravity().getRotation());
        Log.d(TAG, "Sensor rotation:" + PhotonCamera.getCaptureController().mSensorOrientation);
        int orientation = ExifInterface.ORIENTATION_NORMAL;
        switch (rotation) {
            case 90:
                orientation = ExifInterface.ORIENTATION_ROTATE_90;
                break;
            case 180:
                orientation = ExifInterface.ORIENTATION_ROTATE_180;
                break;
            case 270:
                orientation = ExifInterface.ORIENTATION_ROTATE_270;
                break;
        }
        Log.d(TAG, "rotation:" + rotation);
        Log.d(TAG, "orientation:" + orientation);

        Integer iso = result.get(CaptureResult.SENSOR_SENSITIVITY);
        int isonum = 100;
        if (iso != null) isonum = (int) (iso * IsoExpoSelector.getMPY());
        Log.d(TAG, "sensitivity:" + isonum);
        isonum = Math.min(65535,isonum);

        data.SENSITIVITY_TYPE = String.valueOf(ExifInterface.SENSITIVITY_TYPE_ISO_SPEED);
        data.PHOTOGRAPHIC_SENSITIVITY = String.valueOf(isonum);
        data.F_NUMBER = resultget(result, CaptureResult.LENS_APERTURE);
        String focal = resultget(result, CaptureResult.LENS_FOCAL_LENGTH);
        if (!focal.isEmpty()) {
            data.FOCAL_LENGTH = String.valueOf((int) (100 * Double.parseDouble(focal))) + "/100";
        }
        data.APERTURE_VALUE = String.valueOf(result.get(CaptureResult.LENS_APERTURE));
        String exposure = resultget(result, CaptureResult.SENSOR_EXPOSURE_TIME);
        if (!exposure.isEmpty()) {
            data.EXPOSURE_TIME = getTime(Long.parseLong(exposure));
        }
        Long frameDuration = result.get(CaptureResult.SENSOR_FRAME_DURATION);
        if (frameDuration != null) {
            data.FRAME_DURATION = getTime(frameDuration);
        }
        Integer awbMode = result.get(CaptureResult.CONTROL_AWB_MODE);
        if (awbMode != null) {
            data.WHITE_BALANCE = (awbMode == CaptureResult.CONTROL_AWB_MODE_AUTO) ? "0" : "1";
        }
        data.DATETIME = sFormatter.format(new Date(System.currentTimeMillis()));
        data.COMPRESSION = "97";
        data.COLOR_SPACE = "sRGB";
        data.EXIF_VERSION = "0231";
        /*
        //saving for later use
        float sensorWidth = CameraFragment.mCameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE).getWidth();
        String mm35 = String.valueOf((short) (36 * (result.get(CaptureResult.LENS_FOCAL_LENGTH) / sensorWidth)));
        inter.setAttribute(TAG_FOCAL_LENGTH_IN_35MM_FILM, mm35);
        Log.d(TAG, "Saving 35mm FocalLength = " + mm35);
        */
        return data;
    }

    public static void syncWithParameters(ExifData data, Parameters parameters) {
        data.PHOTOGRAPHIC_SENSITIVITY = String.valueOf(parameters.iso);
        data.FOCAL_LENGTH = String.valueOf((int) (100 * parameters.focalLength)) + "/100";
        data.F_NUMBER = String.valueOf(parameters.aperture);
        data.APERTURE_VALUE = String.valueOf(parameters.aperture);
        data.EXPOSURE_TIME = getTime((long) (parameters.exposureTime * 1000000000L));
        data.IMAGE_DESCRIPTION = parameters.toString();
    }

    public static ExifInterface setAllAttributes(File file, ExifData data) {
        ExifInterface inter = null;
        try {
            inter = new ExifInterface(file);
        } catch (IOException e) {
            e.printStackTrace();
            return inter;
        }
        inter.setAttribute(ExifInterface.TAG_SENSITIVITY_TYPE, data.SENSITIVITY_TYPE);
        inter.setAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, data.PHOTOGRAPHIC_SENSITIVITY);
        inter.setAttribute(ExifInterface.TAG_F_NUMBER, data.F_NUMBER);
        inter.setAttribute(ExifInterface.TAG_FOCAL_LENGTH, data.FOCAL_LENGTH);
        inter.setAttribute(ExifInterface.TAG_COPYRIGHT, data.COPYRIGHT);
        inter.setAttribute(ExifInterface.TAG_APERTURE_VALUE, data.APERTURE_VALUE);
        inter.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, data.EXPOSURE_TIME);
        inter.setAttribute(ExifInterface.TAG_DATETIME, data.DATETIME);
        inter.setAttribute(ExifInterface.TAG_MODEL, data.MODEL);
        inter.setAttribute(ExifInterface.TAG_MAKE, data.MAKE);
        inter.setAttribute(ExifInterface.TAG_COMPRESSION, data.COMPRESSION);
        inter.setAttribute(ExifInterface.TAG_COLOR_SPACE, data.COLOR_SPACE);
        inter.setAttribute(ExifInterface.TAG_EXIF_VERSION, data.EXIF_VERSION);
        inter.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, data.IMAGE_DESCRIPTION);
        if (data.WHITE_BALANCE != null) inter.setAttribute(ExifInterface.TAG_WHITE_BALANCE, data.WHITE_BALANCE);
        // Rendered still dimensions (set by encoders from the Bitmap; readers
        // such as gallery details rely on these, notably for HEIC where
        // structural dimension fallback is unavailable).
        if (data.IMAGE_WIDTH != null) {
            inter.setAttribute(ExifInterface.TAG_IMAGE_WIDTH, data.IMAGE_WIDTH);
            inter.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, data.IMAGE_WIDTH);
        }
        if (data.IMAGE_LENGTH != null) {
            inter.setAttribute(ExifInterface.TAG_IMAGE_LENGTH, data.IMAGE_LENGTH);
            inter.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, data.IMAGE_LENGTH);
        }
        return inter;
    }

    /**
     * Builds a standalone EXIF APP1 segment (marker+length+payload) carrying
     * exactly the tags {@link #setAllAttributes} writes, by stamping a tiny
     * stub JPEG: ~KBs of file I/O instead of roundtripping a full-size JPEG
     * through a temp file. Null on any failure (callers keep their file
     * path as fallback).
     */
    public static byte[] buildApp1Segment(ExifData data) {
        if (data == null) {
            return null;
        }
        File tmp = null;
        try {
            Bitmap stub = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
            stub.eraseColor(0xFF808080);
            ByteArrayOutputStream os = new ByteArrayOutputStream();
            stub.compress(Bitmap.CompressFormat.JPEG, 90, os);
            stub.recycle();
            tmp = File.createTempFile("exif_app1_", ".jpg");
            Files.write(tmp.toPath(), os.toByteArray());
            ExifInterface inter = setAllAttributes(tmp, data);
            if (inter != null) {
                inter.saveAttributes();
            }
            return extractApp1(Files.readAllBytes(tmp.toPath()));
        } catch (Exception e) {
            Log.e(TAG, "buildApp1Segment failed", e);
            return null;
        } finally {
            if (tmp != null) {
                // noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        }
    }

    /** Slices the first Exif APP1 segment (marker+length+payload), or null. */
    private static byte[] extractApp1(byte[] jpeg) {
        if (jpeg == null || jpeg.length < 4
                || jpeg[0] != (byte) 0xFF || jpeg[1] != (byte) 0xD8) {
            return null;
        }
        int pos = 2;
        while (pos + 4 <= jpeg.length) {
            while (pos < jpeg.length && jpeg[pos] != (byte) 0xFF) {
                return null;
            }
            while (pos + 1 < jpeg.length && jpeg[pos + 1] == (byte) 0xFF) {
                pos++; // fill bytes
            }
            if (pos + 1 >= jpeg.length) {
                return null;
            }
            int marker = (int) jpeg[pos + 1] & 0xFF;
            pos += 2;
            if (marker == 0xD8 || marker == 0x01
                    || (marker >= 0xD0 && marker <= 0xD7)) {
                continue; // standalone markers carry no length
            }
            if (marker == 0xDA) {
                return null; // scan data: APP1 must precede entropy coding
            }
            if (pos + 2 > jpeg.length) {
                return null;
            }
            int len = (((int) jpeg[pos] & 0xFF) << 8) | ((int) jpeg[pos + 1] & 0xFF);
            if (len < 2 || pos + len > jpeg.length) {
                return null;
            }
            if (marker == 0xE1 && len >= 8
                    && jpeg[pos + 2] == (byte) 'E' && jpeg[pos + 3] == (byte) 'x'
                    && jpeg[pos + 4] == (byte) 'i' && jpeg[pos + 5] == (byte) 'f'
                    && jpeg[pos + 6] == (byte) 0 && jpeg[pos + 7] == (byte) 0) {
                byte[] seg = new byte[2 + len];
                seg[0] = (byte) 0xFF;
                seg[1] = (byte) 0xE1;
                System.arraycopy(jpeg, pos, seg, 2, len);
                return seg;
            }
            pos += len;
        }
        return null;
    }

    public static int getOrientation(int cameraRotation) {
        Log.d(TAG, "Gravity rotation:" + PhotonCamera.getGravity().getRotation());
        Log.d(TAG, "Sensor rotation:" + PhotonCamera.getCaptureController().mSensorOrientation);
        int orientation = ExifInterface.ORIENTATION_NORMAL;
        switch (cameraRotation) {
            case 90:
                orientation = ExifInterface.ORIENTATION_ROTATE_90;
                break;
            case 180:
                orientation = ExifInterface.ORIENTATION_ROTATE_180;
                break;
            case 270:
                orientation = ExifInterface.ORIENTATION_ROTATE_270;
                break;
        }
        return orientation;
    }

    public static class ExifData {
        public final String MODEL = Build.MODEL;
        public final String MAKE = Build.BRAND;
        public final String COPYRIGHT = "PhotonCamera";
        public String SENSITIVITY_TYPE;
        public String APERTURE_VALUE;
        public String COMPRESSION;
        public String COLOR_SPACE;
        public String EXIF_VERSION;
        public String IMAGE_DESCRIPTION;
        public String DATETIME;
        public String EXPOSURE_TIME;
        public String F_NUMBER;
        public String FOCAL_LENGTH;
        public String PHOTOGRAPHIC_SENSITIVITY;
        public String WHITE_BALANCE;
        public String FRAME_DURATION;
        /** Rendered still dimensions in pixels; null = do not write. */
        public String IMAGE_WIDTH;
        public String IMAGE_LENGTH;
    }
}