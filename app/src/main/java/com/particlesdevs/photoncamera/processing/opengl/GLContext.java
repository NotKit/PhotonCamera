package com.particlesdevs.photoncamera.processing.opengl;

import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;

import static android.opengl.EGL14.EGL_HEIGHT;
import static android.opengl.EGL14.EGL_OPENGL_ES2_BIT;
import static android.opengl.EGL14.EGL_PBUFFER_BIT;
import static android.opengl.EGL14.EGL_RENDERABLE_TYPE;
import static android.opengl.EGL14.EGL_SURFACE_TYPE;
import static android.opengl.EGL14.EGL_VENDOR;
import static android.opengl.EGL14.EGL_VERSION;
import static android.opengl.EGL14.eglGetError;
import static android.opengl.EGL14.eglQueryString;
import static android.opengl.EGL14.EGL_NONE;
import static android.opengl.EGL14.EGL_NO_CONTEXT;
import static android.opengl.EGL14.EGL_NO_SURFACE;
import static android.opengl.EGL14.EGL_WIDTH;
import static android.opengl.EGL14.eglChooseConfig;
import static android.opengl.EGL14.eglCreateContext;
import static android.opengl.EGL14.eglCreatePbufferSurface;
import static android.opengl.EGL14.eglDestroyContext;
import static android.opengl.EGL14.eglDestroySurface;
import static android.opengl.EGL14.eglGetDisplay;
import static android.opengl.EGL14.eglInitialize;
import static android.opengl.EGL14.eglMakeCurrent;
import static android.opengl.EGL14.eglTerminate;
import static android.opengl.GLES20.GL_COLOR_ATTACHMENT0;
import static android.opengl.GLES20.GL_RENDERBUFFER;
import static android.opengl.GLES20.glBindFramebuffer;
import static android.opengl.GLES20.glBindRenderbuffer;
import static android.opengl.GLES20.glFramebufferRenderbuffer;
import static android.opengl.GLES20.glGenFramebuffers;
import static android.opengl.GLES20.glGenRenderbuffers;
import static android.opengl.GLES20.glRenderbufferStorage;
import static android.opengl.GLES30.GL_DRAW_FRAMEBUFFER;
import static android.opengl.GLES30.GL_RGBA8;

public class GLContext implements AutoCloseable {
    private EGLDisplay mDisplay;
    private EGLContext mContext;
    private EGLSurface mSurface;
    public GLProg mProgram;
    public final int[] bindFB = new int[1];
    public final int[] bindRB = new int[1];

    public GLContext(int surfaceWidth, int surfaceHeight) {
        createContext(surfaceWidth,surfaceHeight);

    }
    public void createContext(int surfaceWidth, int surfaceHeight){
        int[] major = new int[2];
        int[] minor = new int[2];
        mDisplay = eglGetDisplay(GLDrawParams.EGLDisplay);
        boolean inited = eglInitialize(mDisplay, major, 0, minor, 0);
        int[] numConfig = new int[1];
        boolean chosen = eglChooseConfig(mDisplay, GLDrawParams.attribList, 0,
                new EGLConfig[0], 0, 0, numConfig, 0);
        if (!inited || !chosen || numConfig[0] == 0) {
            // "config count zero" alone names neither the display nor the
            // attribute that emptied the list, and the two failures it covers
            // -- a display that will not initialise and one that has no
            // matching config -- want opposite fixes.
            throw new RuntimeException("OpenGL config count zero"
                    + " (init=" + inited + " egl=" + major[0] + "." + minor[0]
                    + " vendor=" + eglQueryString(mDisplay, EGL_VENDOR)
                    + " version=" + eglQueryString(mDisplay, EGL_VERSION)
                    + " chosen=" + chosen + " n=" + numConfig[0]
                    + " err=0x" + Integer.toHexString(eglGetError())
                    + ")" + whichAttributeEmptiedIt());
        }
        int configSize = numConfig[0];
        EGLConfig[] configs = new EGLConfig[configSize];
        if (!eglChooseConfig(mDisplay, GLDrawParams.attribList, 0,
                configs, 0, configSize, numConfig, 0)) {
            throw new RuntimeException("OpenGL config loading failed");
        }
        if (configs[0] == null) {
            throw new RuntimeException("OpenGL config is null");
        }
        mContext = eglCreateContext(mDisplay, configs[0], EGL_NO_CONTEXT, GLDrawParams.contextAttributeList, 0);
        // Use 1x1 pbuffer; FBO holds full frame, avoids EGL max pbuffer limit
        mSurface = eglCreatePbufferSurface(mDisplay, configs[0], new int[]{
                EGL_WIDTH, 1,
                EGL_HEIGHT, 1,
                EGL_NONE
        }, 0);
        eglMakeCurrent(mDisplay, mSurface, mSurface, mContext);
        mProgram = new GLProg();
    }

    /**
     * Which attribute the display cannot satisfy, asked one drop at a time.
     * Only ever called on the failure path; the answer is the difference
     * between "this EGL has no offscreen at all" and "it has no
     * BIND_TO_TEXTURE_RGBA", which are not the same bring-up problem.
     */
    private String whichAttributeEmptiedIt() {
        int[][] relaxed = {
                {EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT, EGL_NONE},
                {EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_NONE},
                {EGL_NONE},
        };
        String[] names = {"pbuffer+es2", "pbuffer", "anything"};
        StringBuilder sb = new StringBuilder(" probes:");
        for (int i = 0; i < relaxed.length; i++) {
            int[] n = new int[1];
            boolean ok = eglChooseConfig(mDisplay, relaxed[i], 0, new EGLConfig[0], 0, 0, n, 0);
            sb.append(' ').append(names[i]).append('=').append(ok ? n[0] : -1);
        }
        return sb.toString();
    }

    @Override
    public void close() {
        if (mDisplay == null) return;
        try {
            if (mProgram != null) mProgram.close();
        } catch (Exception ignored) {}
        try {
            eglMakeCurrent(mDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        } catch (Exception ignored) {}
        try {
            if (mContext != null) eglDestroyContext(mDisplay, mContext);
        } catch (Exception ignored) {}
        try {
            if (mSurface != null) eglDestroySurface(mDisplay, mSurface);
        } catch (Exception ignored) {}
        try {
            eglTerminate(mDisplay);
        } catch (Exception ignored) {}
        mDisplay = null;
        mContext = null;
        mSurface = null;
    }
}
