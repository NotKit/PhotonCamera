/*
 *
 *  PhotonCamera
 *  CameraLensData.java
 *  Copyright (C) 2020 - 2021  Vibhor
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <https://www.gnu.org/licenses/>.
 * /
 */

package com.particlesdevs.photoncamera.ui.camera.data;

import androidx.annotation.NonNull;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.annotations.SerializedName;

import java.util.Objects;

/**
 * Data class which stores basic data related to camera lens
 * Mainly for usage with multi-camera buttons.
 */
public class CameraLensData {
    @SerializedName("id")
    private final String cameraId;
    @SerializedName("face")
    private int facing;
    @SerializedName("fl")
    private float cameraFocalLength;
    @SerializedName("ap")
    private float cameraAperture;
    @SerializedName("fl35")
    private float camera35mmFocalLength;
    @SerializedName("zf")
    private float zoomFactor;
    @SerializedName("fs")
    private boolean flashSupported;

    public CameraLensData(String cameraId) {
        this.cameraId = cameraId;
    }

    public int getFacing() {
        return facing;
    }

    public void setFacing(int facing) {
        this.facing = facing;
    }

    public String getCameraId() {
        return cameraId;
    }

    public float getCameraFocalLength() {
        return cameraFocalLength;
    }

    public void setCameraFocalLength(float cameraFocalLength) {
        this.cameraFocalLength = cameraFocalLength;
    }

    public float getCamera35mmFocalLength() {
        return camera35mmFocalLength;
    }

    public void setCamera35mmFocalLength(float camera35mmFocalLength) {
        this.camera35mmFocalLength = camera35mmFocalLength;
    }

    public float getZoomFactor() {
        return zoomFactor;
    }

    public void setZoomFactor(float zoomFactor) {
        this.zoomFactor = zoomFactor;
    }

    public float getCameraAperture() {
        return cameraAperture;
    }

    public void setCameraAperture(float cameraAperture) {
        this.cameraAperture = cameraAperture;
    }

    public void setFlashSupported(boolean flashSupported) {
        this.flashSupported = flashSupported;
    }

    /**
     * This object as JSON, field by field.
     *
     * <p>Gson can do this by reflection from the {@link SerializedName}
     * annotations above, and did; a build without reflection cannot, so the
     * keys are written out here instead. They are the annotations' own, so a
     * preference file written by either version is read by both.
     *
     * @see #fromJson(String)
     */
    public String toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("id", cameraId);
        o.addProperty("face", facing);
        o.addProperty("fl", cameraFocalLength);
        o.addProperty("ap", cameraAperture);
        o.addProperty("fl35", camera35mmFocalLength);
        o.addProperty("zf", zoomFactor);
        o.addProperty("fs", flashSupported);
        return o.toString();
    }

    /** The inverse of {@link #toJson()}; null when the text is not one of these. */
    public static CameraLensData fromJson(String json) {
        if (json == null || json.isEmpty()) return null;
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        if (o == null || !o.has("id")) return null;
        CameraLensData data = new CameraLensData(o.get("id").getAsString());
        if (o.has("face")) data.facing = o.get("face").getAsInt();
        if (o.has("fl")) data.cameraFocalLength = o.get("fl").getAsFloat();
        if (o.has("ap")) data.cameraAperture = o.get("ap").getAsFloat();
        if (o.has("fl35")) data.camera35mmFocalLength = o.get("fl35").getAsFloat();
        if (o.has("zf")) data.zoomFactor = o.get("zf").getAsFloat();
        if (o.has("fs")) data.flashSupported = o.get("fs").getAsBoolean();
        return data;
    }

    @Override
    public boolean equals(Object other) {
        // No `this == other` fast path: it is only an optimisation, and
        // comparing the fields of an object with itself gives the same answer.
        if (other == null || getClass() != other.getClass()) return false;
        CameraLensData that = (CameraLensData) other;
        return facing == that.facing && Float.valueOf(that.cameraFocalLength).compareTo(cameraFocalLength) == 0 && Float.valueOf(that.cameraAperture).compareTo(cameraAperture) == 0 && flashSupported == that.flashSupported;
    }

    @Override
    public int hashCode() {
        return Objects.hash(facing, cameraFocalLength, cameraAperture, flashSupported);
    }

    @Override
    @NonNull
    public String toString() {
        return "CameraLensData{" +
                "cameraId='" + cameraId + '\'' +
                ", facing=" + facing +
                ", cameraFocalLength=" + cameraFocalLength +
                ", cameraAperture=" + cameraAperture +
                ", camera35mmFocalLength=" + camera35mmFocalLength +
                ", zoomFactor=" + zoomFactor +
                "}\n";
    }
}
