package com.particlesdevs.photoncamera.ui.camera.views.viewfinder;

import android.app.Activity;
import android.graphics.Point;
import android.graphics.SurfaceTexture;
import android.view.Display;
import android.view.TextureView;

import androidx.annotation.NonNull;

import com.particlesdevs.photoncamera.capture.PreviewSurface;

/**
 * {@link PreviewSurface} over the Android View hierarchy: the GL viewfinder and
 * the activity's display.
 *
 * <p>This is the whole of the View layer the capture path used to carry inside
 * {@link com.particlesdevs.photoncamera.capture.CaptureController}. The Compose
 * port supplies its own implementation and never sees this file.
 */
public class GLPreviewSurface implements PreviewSurface {
    private final GLPreview preview;
    private final Activity activity;

    public GLPreviewSurface(GLPreview preview, Activity activity) {
        this.preview = preview;
        this.activity = activity;
    }

    @Override
    public SurfaceTexture getSurfaceTexture() {
        return preview == null ? null : preview.getSurfaceTexture();
    }

    @Override
    public boolean isAvailable() {
        return preview != null && preview.isAvailable();
    }

    @Override
    public void setListener(Listener listener) {
        if (preview == null) return;
        if (listener == null) {
            preview.setSurfaceTextureListener(null);
            return;
        }
        preview.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(@NonNull SurfaceTexture texture, int width, int height) {
                listener.onPreviewSurfaceAvailable(texture, width, height);
            }

            @Override
            public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture texture, int width, int height) {
                listener.onPreviewSurfaceSizeChanged(texture, width, height);
            }

            @Override
            public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture texture) {
                return listener.onPreviewSurfaceDestroyed(texture);
            }

            @Override
            public void onSurfaceTextureUpdated(@NonNull SurfaceTexture texture) {
            }
        });
    }

    @Override
    public void setAspectRatio(int width, int height) {
        if (preview != null) preview.setAspectRatio(width, height);
    }

    @Override
    public void setCameraSize(Point size) {
        if (preview != null) preview.cameraSize = size;
    }

    @Override
    public void setOrientation(int degrees) {
        if (preview != null) preview.setOrientation(degrees);
    }

    @Override
    public void setMirror(boolean mirror) {
        if (preview != null) preview.setMirror(mirror);
    }

    @Override
    public Point getDisplaySize() {
        Point size = new Point();
        Display display = null;
        if (preview != null) display = preview.getDisplay();
        //noinspection deprecation
        if (display == null && activity != null) display = activity.getWindowManager().getDefaultDisplay();
        if (display != null) display.getRealSize(size);
        return size;
    }
}
