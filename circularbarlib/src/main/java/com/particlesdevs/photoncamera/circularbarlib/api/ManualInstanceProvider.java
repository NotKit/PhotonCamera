package com.particlesdevs.photoncamera.circularbarlib.api;

import com.particlesdevs.photoncamera.circularbarlib.console.ManualModeConsoleImpl;
import com.particlesdevs.photoncamera.circularbarlib.ui.ViewObserver;

/**
 * Hands out consoles wired to the Android manual bar.  This is the one place
 * that knows the View layer, which is why it is not part of the port.
 */
public class ManualInstanceProvider {
    /**
     * @return Singleton Instance
     */
    public static ManualModeConsole getManualModeConsole() {
        return withUi(ManualModeConsoleImpl.getInstance());
    }

    /**
     * @return new Instance
     */
    public static ManualModeConsole getNewManualModeConsole() {
        return withUi(ManualModeConsoleImpl.newInstance());
    }

    private static ManualModeConsole withUi(ManualModeConsoleImpl console) {
        console.setUiFactory(ViewObserver::new);
        return console;
    }
}
