package com.particlesdevs.photoncamera.api;

import androidx.annotation.StringRes;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.app.PhotonCamera;

public enum CameraMode {
    UNLIMITED(R.string.mode_unlimited),
    RAWVIDEO(R.string.mode_rawvideo),
    MOTION(R.string.mode_motion),
    PHOTO(R.string.mode_photo),
    NIGHT(R.string.mode_night),
    VIDEO(R.string.mode_video);

    int stringId;

    CameraMode(@StringRes int stringId) {
        this.stringId = stringId;
    }

    public static CameraMode valueOf(int modeOrdinal) {
        for (CameraMode mode : values()) {
            if (modeOrdinal == mode.ordinal()) {
                return mode;
            }
        }
        return PHOTO;
    }

    public static Integer[] nameIds() {
        CameraMode[] modes = values();
        Integer[] ids = new Integer[modes.length];
        for (int i = 0; i < modes.length; i++) {
            ids[i] = modes[i].stringId;
        }
        return ids;
    }

}
