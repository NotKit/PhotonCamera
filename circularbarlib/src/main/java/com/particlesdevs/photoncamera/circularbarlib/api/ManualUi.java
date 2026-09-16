package com.particlesdevs.photoncamera.circularbarlib.api;

import java.util.Observer;

/**
 * The platform's manual-mode surface: it observes the models and follows the
 * device orientation.  ViewObserver on Android; the Compose screen on the port.
 */
public interface ManualUi extends Observer {
    void onResume();

    void onPause();
}
