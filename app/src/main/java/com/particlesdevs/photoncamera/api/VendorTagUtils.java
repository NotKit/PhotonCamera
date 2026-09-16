package com.particlesdevs.photoncamera.api;

import android.content.Context;
import android.annotation.SuppressLint;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;

import com.particlesdevs.photoncamera.util.Log;
import com.particlesdevs.photoncamera.app.PhotonCamera;
import com.particlesdevs.photoncamera.settings.TunableKeyManager;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class VendorTagUtils {
    private static final String TAG = "VendorTagUtils";
    private static final Set<String> verifiedOisSensors = ConcurrentHashMap.newKeySet();

    private static boolean isSupported(CaptureRequest.Builder builder,
                                       CaptureRequest.Key<?> key) {
        boolean supported = true;
        try {
            builder.get(key);
        }catch(IllegalArgumentException exception){
            supported = false;
            Log.w(TAG,"vendor tag " + key.getName() + " is not supported");
        }
        if ( supported ) {
            Log.d(TAG,"vendor tag " + key.getName() + " is supported");
        }
        return supported;
    }

    @SuppressLint("NewApi")
    private static <T> void setPhysical(CaptureRequest.Builder builder, CaptureRequest.Key<T> key, T value, String physicalId) {
        try {
            builder.setPhysicalCameraKey(key, value, physicalId);
        } catch (Exception e) {
            Log.w(TAG, "Error setting physical camera key: " + key.getName() + " for camera: " + physicalId, e);
        }
    }

    private static <T> void setKeyValue(CaptureRequest.Builder builder, CaptureRequest.Key<T> key, T value) {
        builder.set(key, value);
    }

    /**
     * A user-defined vendor tag that can be created per physical sensor in the
     * Sensor Configurations settings. Supports both {@link CaptureRequest} and
     * {@link CaptureResult} keys, though only request keys are applied to the
     * {@link CaptureRequest.Builder} during capture.
     */
    public static class TunableKey {
        /** "CaptureRequest" or "CaptureResult" */
        public String type = "CaptureRequest";
        /** Vendor tag string, e.g. "com.qti.sensorbps.mode_index" */
        public String name = "";
        /** "Integer", "Long", "Float", "Byte", "int[]", ... */
        public String valueType = "Integer";
        /** Value as a string (comma separated for arrays) */
        public String value = "0";
        /** Whether the key was tested against the camera and found supported */
        public boolean supported = false;
        /** Whether the key has been tested yet (only possible via VendorTagUtils at capture) */
        public boolean tested = false;

        public TunableKey() {}

        public TunableKey(String type, String name, Class<?> valueClass, Object value) {
            this.type = type;
            this.name = name;
            this.valueType = valueTypeForClass(valueClass);
            this.value = valueToString(valueClass, value);
        }

        public Object parseValue() {
            return parseValue(valueType, value);
        }

        /* The value's type is only known as a string here, so the key is the
         * untyped one and the value rides as an Object. */
        @SuppressWarnings("unchecked")
        public CaptureRequest.Key<Object> toCaptureRequestKey() {
            return new CaptureRequest.Key<Object>(name, (Class<Object>) classForValueType(valueType));
        }

        @SuppressWarnings("unchecked")
        public CaptureResult.Key<Object> toCaptureResultKey() {
            return new CaptureResult.Key<Object>(name, (Class<Object>) classForValueType(valueType));
        }

        public static String valueTypeForClass(Class<?> valueClass) {
            if (valueClass == null) return "Integer";
            if (valueClass == Integer.class) return "Integer";
            if (valueClass == Long.class) return "Long";
            if (valueClass == Float.class) return "Float";
            if (valueClass == Double.class) return "Double";
            if (valueClass == Byte.class) return "Byte";
            if (valueClass == Short.class) return "Short";
            if (valueClass == int[].class) return "int[]";
            if (valueClass == byte[].class) return "byte[]";
            if (valueClass == long[].class) return "long[]";
            if (valueClass == float[].class) return "float[]";
            if (valueClass == String.class) return "String";
            return "Integer";
        }

        public static Class<?> classForValueType(String valueType) {
            if (valueType == null) return Integer.class;
            switch (valueType) {
                case "Long": return Long.class;
                case "Float": return Float.class;
                case "Double": return Double.class;
                case "Byte": return Byte.class;
                case "Short": return Short.class;
                case "int[]": return int[].class;
                case "byte[]": return byte[].class;
                case "long[]": return long[].class;
                case "float[]": return float[].class;
                case "String": return String.class;
                case "Integer":
                default: return Integer.class;
            }
        }

        /** The four array shapes a tunable key can carry, spelled out rather
         * than walked with java.lang.reflect.Array. */
        public static String valueToString(Class<?> valueClass, Object value) {
            if (value == null) return "0";
            StringBuilder sb = new StringBuilder();
            if (value instanceof int[]) {
                int[] a = (int[]) value;
                for (int i = 0; i < a.length; i++) {
                    if (i > 0) sb.append(",");
                    sb.append(a[i]);
                }
                return sb.toString();
            }
            if (value instanceof byte[]) {
                byte[] a = (byte[]) value;
                for (int i = 0; i < a.length; i++) {
                    if (i > 0) sb.append(",");
                    sb.append(a[i]);
                }
                return sb.toString();
            }
            if (value instanceof long[]) {
                long[] a = (long[]) value;
                for (int i = 0; i < a.length; i++) {
                    if (i > 0) sb.append(",");
                    sb.append(a[i]);
                }
                return sb.toString();
            }
            if (value instanceof float[]) {
                float[] a = (float[]) value;
                for (int i = 0; i < a.length; i++) {
                    if (i > 0) sb.append(",");
                    sb.append(a[i]);
                }
                return sb.toString();
            }
            return String.valueOf(value);
        }

        public static Object parseValue(String valueType, String rawValue) {
            String value = rawValue == null ? "" : rawValue;
            String vt = valueType == null ? "Integer" : valueType;
            switch (vt) {
                case "Long": return Long.parseLong(value.trim());
                case "Float": return Float.parseFloat(value.trim());
                case "Double": return Double.parseDouble(value.trim());
                case "Byte": return Byte.parseByte(value.trim());
                case "Short": return Short.parseShort(value.trim());
                case "int[]": {
                    String[] parts = value.split(",");
                    int[] arr = new int[parts.length];
                    for (int i = 0; i < parts.length; i++) arr[i] = Integer.parseInt(parts[i].trim());
                    return arr;
                }
                case "byte[]": {
                    String[] parts = value.split(",");
                    byte[] arr = new byte[parts.length];
                    for (int i = 0; i < parts.length; i++) arr[i] = Byte.parseByte(parts[i].trim());
                    return arr;
                }
                case "long[]": {
                    String[] parts = value.split(",");
                    long[] arr = new long[parts.length];
                    for (int i = 0; i < parts.length; i++) arr[i] = Long.parseLong(parts[i].trim());
                    return arr;
                }
                case "float[]": {
                    String[] parts = value.split(",");
                    float[] arr = new float[parts.length];
                    for (int i = 0; i < parts.length; i++) arr[i] = Float.parseFloat(parts[i].trim());
                    return arr;
                }
                case "String": return value;
                case "Integer":
                default: return Integer.parseInt(value.trim());
            }
        }
    }

    /**
     * Apply a list of user-defined tunable keys to a request builder.
     * Tests each key for support (via {@code builder.get}) and only sets it when
     * supported. Updates the {@code tested}/{@code supported} flags in place so the
     * settings UI can show green/red feedback afterwards.
     */
    public static void applyTunableKeys(CaptureRequest.Builder builder, List<TunableKey> keys, String physicalId) {
        if (builder == null || keys == null) return;
        for (TunableKey tunableKey : keys) {
            if (tunableKey == null) continue;
            tunableKey.tested = true;
            if (!"CaptureRequest".equals(tunableKey.type)) {
                Log.w(TAG, "Tunable key " + tunableKey.name + " is a " + tunableKey.type + " key, cannot be applied to the request builder");
                tunableKey.supported = false;
                continue;
            }
            try {
                CaptureRequest.Key<Object> key = tunableKey.toCaptureRequestKey();
                boolean supported = isSupported(builder, key);
                tunableKey.supported = supported;
                if (supported) {
                    Object parsedValue = tunableKey.parseValue();
                    setKeyValue(builder, key, parsedValue);
                    if (physicalId != null && !physicalId.isEmpty()) {
                        setPhysical(builder, key, parsedValue, physicalId);
                    }
                    Log.d(TAG, "Applied tunable key " + tunableKey.name + " = " + tunableKey.value + " (" + tunableKey.valueType + ")");
                }
            } catch (Exception e) {
                tunableKey.supported = false;
                Log.w(TAG, "Error applying tunable key " + tunableKey.name, e);
            }
        }
    }

    @SuppressLint({"NewApi", "LocalSuppress"})
    public static void builderSessionApply(CaptureRequest.Builder builder, boolean burst, boolean useMaximumResolutionKey, String physicalId) {
        try {
            byte enable = 1;
             CaptureRequest.Key<String> clientName = new CaptureRequest.Key<>("com.xiaomi.sessionparams.clientName", String.class);
            if(isSupported(builder,clientName)) {
                Log.d(TAG, "com.xiaomi.sessionparams.clientName supported");
                builder.set(clientName, "com.android.camera");
                setPhysical(builder, clientName, "com.android.camera", physicalId);
            }
            if(burst) {
                CaptureRequest.Key<Byte> remosaicEnabled = new CaptureRequest.Key<>("xiaomi.remosaic.enabled", Byte.class);
                if (isSupported(builder, remosaicEnabled)) {
                    builder.set(remosaicEnabled, enable);
                    setPhysical(builder, remosaicEnabled, enable, physicalId);
                }
                CaptureRequest.Key<Byte> remosaicQuadEnabled = new CaptureRequest.Key<>("xiaomi.quadcfa.enabled", Byte.class);
                if (isSupported(builder, remosaicQuadEnabled)) {
                    builder.set(remosaicQuadEnabled, enable);
                    setPhysical(builder, remosaicQuadEnabled, enable, physicalId);
                }
                CaptureRequest.Key<int[]> remosaicEnabled2 = new CaptureRequest.Key<>("com.mediatek.control.capture.remosaicenable", int[].class);
                if (isSupported(builder, remosaicEnabled2)) {
                    builder.set(remosaicEnabled2, new int[]{1});
                    setPhysical(builder, remosaicEnabled2, new int[]{1}, physicalId);
                }
            }
        } catch (Exception e){
            Log.w(TAG, "Error applying vendor tags to CaptureRequest.Builder", e);
        }
        if(useMaximumResolutionKey) {
            builder.set(CaptureRequest.SENSOR_PIXEL_MODE, CaptureRequest.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION);
        }
        TunableKeyManager.applyTunableKeys(builder, physicalId);
    }

    /**
     * Inspects dynamic metadata from preview/capture result and caches confirmed hardware features
     * (such as active OIS mode) into system flags for the given physical sensor.
     * Uses an in-memory set to ensure inspection, logging, and disk writes happen only once per sensor.
     *
     * @param result     the CaptureResult from preview or capture frame
     * @param physicalId physical camera ID
     */
    public static void resultSessionApply(CaptureResult result, String physicalId) {
        if (result == null || physicalId == null || physicalId.isEmpty()) return;
        if (verifiedOisSensors.contains(physicalId)) return;

        try {
            Integer oisMode = result.get(CaptureResult.LENS_OPTICAL_STABILIZATION_MODE);
            if (oisMode != null) {
                if (oisMode == 1) {
                    Context context = PhotonCamera.getSettingsManagerStatic() != null
                            ? PhotonCamera.getSettingsManagerStatic().getContext() : null;
                    if (context != null && !TunableKeyManager.getSystemFlag(context, physicalId, "ois_supported", false)) {
                        TunableKeyManager.setSystemFlag(context, physicalId, "ois_supported", true);
                    }
                    Log.d(TAG, "Sensor " + physicalId + " OIS confirmed (mode = 1) -> cached to system flags");
                } else {
                    Log.d(TAG, "Sensor " + physicalId + " OIS is not active (mode = " + oisMode + ")");
                }
            }
            // Mark sensor verified once metadata is read (prevents repeat execution and log spam for all sensors)
            verifiedOisSensors.add(physicalId);
        } catch (Exception e) {
            Log.w(TAG, "Error inspecting CaptureResult for sensor " + physicalId, e);
        }
    }

    /**
     * Universal check whether hardware Optical Image Stabilization (OIS) is supported
     * for the specified physical camera sensor.
     * Checks static CameraCharacteristics (looking for LENS_OPTICAL_STABILIZATION_MODE_ON)
     * and falls back to persistent system flags verified from preview CaptureResult.
     *
     * @param context    application or activity context
     * @param chars      CameraCharacteristics (if already loaded in RAM, or null to query CameraManager)
     * @param physicalId physical camera ID
     * @return true if hardware OIS is confirmed available and working
     */
    public static boolean isOisSupported(Context context, CameraCharacteristics characteristics, String physicalId) {
        CameraCharacteristics chars = characteristics;
        if (chars == null && context != null && physicalId != null && !physicalId.isEmpty()) {
            try {
                CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
                if (manager != null) {
                    chars = manager.getCameraCharacteristics(physicalId);
                }
            } catch (Exception ignored) {}
        }
        if (chars != null) {
            int[] modes = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION);
            if (modes != null) {
                for (int mode : modes) {
                    if (mode == CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON) {
                        return true;
                    }
                }
            }
        }
        return TunableKeyManager.getSystemFlag(context, physicalId, "ois_supported", false);
    }    
}
