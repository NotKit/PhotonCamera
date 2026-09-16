package com.particlesdevs.photoncamera.ui.camera.views;

import android.view.Display;
import android.view.HapticFeedbackConstants;
import android.view.View;

import com.particlesdevs.photoncamera.control.FocusIndicator;
import com.particlesdevs.photoncamera.ui.camera.views.viewfinder.GLPreview;

/**
 * {@link FocusIndicator} over the Android View hierarchy: the focus circle, the
 * spot-WB reticle and the GL viewfinder they sit on.
 *
 * <p>This is the whole of the View layer that tap-to-focus used to carry inside
 * {@link com.particlesdevs.photoncamera.control.TouchFocus}. The Compose port
 * supplies its own implementation and never sees this file.
 */
public class ViewFocusIndicator implements FocusIndicator {
    private final GLPreview preview;
    private final View focusCircle;
    private final View spotWb;

    public ViewFocusIndicator(GLPreview preview, View focusCircle, View spotWb) {
        this.preview = preview;
        this.focusCircle = focusCircle;
        this.spotWb = spotWb;
        if (focusCircle != null) {
            focusCircle.setClickable(false);
            focusCircle.setFocusable(false);
        }
        if (spotWb != null) {
            spotWb.setClickable(false);
            spotWb.setFocusable(false);
        }
    }

    @Override
    public int getPreviewWidth() {
        return preview == null ? 0 : preview.getWidth();
    }

    @Override
    public int getPreviewHeight() {
        return preview == null ? 0 : preview.getHeight();
    }

    @Override
    public int getDisplayRotation() {
        if (preview == null) return -1;
        try {
            Display display = preview.getDisplay();
            if (display != null) return display.getRotation() * 90 + 90;
        } catch (Exception ignored) {
        }
        return -1;
    }

    @Override
    public void hapticLongPress() {
        if (preview != null) preview.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
    }

    @Override
    public void post(Runnable action) {
        View host = focusCircle != null ? focusCircle : spotWb;
        if (host != null) host.post(action);
    }

    @Override
    public void postDelayed(Runnable action, long delayMs) {
        View host = focusCircle != null ? focusCircle : spotWb;
        if (host != null) host.postDelayed(action, delayMs);
    }

    @Override
    public void cancel(Runnable action) {
        if (focusCircle != null) focusCircle.removeCallbacks(action);
        if (spotWb != null) spotWb.removeCallbacks(action);
    }

    @Override
    public void showFocusCircle(float x, float y) {
        if (focusCircle == null) return;
        focusCircle.setX(x - focusCircle.getMeasuredWidth() / 2.0f);
        focusCircle.setY(y - focusCircle.getMeasuredHeight() / 2.0f);
        focusCircle.setVisibility(View.VISIBLE);
        focusCircle.animate().scaleY(1.2f).scaleX(1.2f).setDuration(250)
                .withEndAction(() -> focusCircle.animate().scaleY(1f).scaleX(1f).setDuration(250).start())
                .start();
    }

    @Override
    public void hideFocusCircle() {
        if (focusCircle == null || focusCircle.getVisibility() != View.VISIBLE) return;
        focusCircle.animate().alpha(0f).scaleY(1.8f).scaleX(1.8f).setDuration(100)
                .withEndAction(() -> {
                    focusCircle.setVisibility(View.GONE);
                    focusCircle.setX((float) getPreviewWidth() / 2.0f);
                    focusCircle.setY((float) getPreviewHeight() / 2.0f);
                    focusCircle.setScaleY(1f);
                    focusCircle.setScaleX(1f);
                    focusCircle.setAlpha(1f);
                })
                .start();
    }

    @Override
    public void setAfState(int afState) {
        if (focusCircle instanceof FocusCircleView) ((FocusCircleView) focusCircle).setAfState(afState);
    }

    @Override
    public void showSpotWb(float x, float y) {
        if (spotWb == null) return;
        if (spotWb instanceof SpotWbIndicatorView) ((SpotWbIndicatorView) spotWb).setMeasuringState();
        spotWb.setX(x - spotWb.getMeasuredWidth() / 2.0f);
        spotWb.setY(y - spotWb.getMeasuredHeight() / 2.0f);
        spotWb.setVisibility(View.VISIBLE);
        spotWb.animate().scaleX(1.25f).scaleY(1.25f).setDuration(150)
                .withEndAction(() -> spotWb.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start())
                .start();
    }

    @Override
    public void setSpotWbError(String reason) {
        if (spotWb instanceof SpotWbIndicatorView) ((SpotWbIndicatorView) spotWb).setErrorState(reason);
    }

    @Override
    public void hideSpotWb() {
        if (spotWb == null || spotWb.getVisibility() != View.VISIBLE) return;
        spotWb.animate().alpha(0f).scaleX(1.4f).scaleY(1.4f).setDuration(120)
                .withEndAction(() -> {
                    spotWb.setVisibility(View.GONE);
                    spotWb.setX((float) getPreviewWidth() / 2.0f);
                    spotWb.setY((float) getPreviewHeight() / 2.0f);
                    spotWb.setScaleX(1f);
                    spotWb.setScaleY(1f);
                    spotWb.setAlpha(1f);
                })
                .start();
    }

    @Override
    public void setOrientation(int orientation) {
        if (spotWb instanceof SpotWbIndicatorView) ((SpotWbIndicatorView) spotWb).setOrientation(orientation);
    }
}
