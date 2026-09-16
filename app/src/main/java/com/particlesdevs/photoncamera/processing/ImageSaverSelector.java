package com.particlesdevs.photoncamera.processing;

import android.graphics.ImageFormat;
import com.particlesdevs.photoncamera.util.Log;

public class ImageSaverSelector {
    private static JPEGSaver jpegSaver;
    private static YUVSaver yuvSaver;
    private static RAW16Saver raw16Saver;

    private static final String TAG = "ImageSaverSelector";

    public static void init(SaverImplementation saverImplementation) {
        jpegSaver = new JPEGSaver(saverImplementation.processingEventsListener);
        yuvSaver = new YUVSaver(saverImplementation.processingEventsListener);
        raw16Saver = new RAW16Saver(saverImplementation.processingEventsListener);
    }

    public static SaverImplementation getImageSaver(int format, SaverImplementation fallback) {
        SaverImplementation saverImplementation = fallback;
        switch (format) {
            case ImageFormat.JPEG:
                saverImplementation = jpegSaver;
                //saverImplementation = new JPEGSaver(saverImplementation.processingEventsListener);
                break;

            case ImageFormat.YUV_420_888:
                saverImplementation = yuvSaver;
                //saverImplementation = new YUVSaver(saverImplementation.processingEventsListener);
                break;

            case ImageFormat.RAW10:
            case ImageFormat.RAW_SENSOR:
                Log.d(TAG, "Selected RAW16Saver for format: " + format);
                saverImplementation = raw16Saver;
                //saverImplementation = new RAW16Saver(saverImplementation.processingEventsListener);
                break;

            default:
                Log.e(TAG, "Cannot save image, unexpected image format:" + format);
                break;
        }
        return saverImplementation;
    }
}
