package com.particlesdevs.photoncamera.control;

import android.os.CountDownTimer;

import java.util.Locale;
import java.util.function.Consumer;

/**
 * The shutter countdown. It reports the seconds left as text; the screen decides
 * how to show them, where this used to drive a TextView's scale and alpha itself.
 */
public class CountdownTimer extends CountDownTimer {
    private final Consumer<String> onTick;
    private final TimerCallback callback;

    public CountdownTimer(Consumer<String> onTick, long millisInFuture, long countDownInterval,
                          TimerCallback callback) {
        super(millisInFuture, countDownInterval);
        this.callback = callback;
        this.onTick = onTick;
    }

    @Override
    public void onTick(long millisUntilFinished) {
        long seconds = millisUntilFinished / 1000L + 1;
        onTick.accept(String.format(Locale.ROOT, "%d", seconds));
    }

    @Override
    public void onFinish() {
        onTick.accept("");
        callback.onFinished();
    }

    public interface TimerCallback {
        void onFinished();
    }
}
