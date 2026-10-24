package com.nightmare.sunshine.dlna;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

public final class DlnaVideoSource {
    private static final String TAG = "DlnaVideoSource";
    private static final int EGL_RECORDABLE_ANDROID = 0x3142;
    private static final String VERTEX_SHADER =
            "attribute vec4 position; attribute vec4 texturePosition; uniform mat4 textureMatrix;"
            + "varying vec2 textureCoordinate; void main() { gl_Position = position;"
            + "textureCoordinate = (textureMatrix * texturePosition).xy; }";
    private static final String FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\nprecision mediump float;"
            + "uniform samplerExternalOES frame; varying vec2 textureCoordinate;"
            + "void main() { gl_FragColor = texture2D(frame, textureCoordinate); }";

    private final Surface encoderSurface;
    private final int captureWidth;
    private final int captureHeight;
    private final int outputWidth;
    private final int outputHeight;
    private final long frameIntervalNanos;
    private final Consumer<RuntimeException> onError;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean frameAvailable = new AtomicBoolean();
    private final CountDownLatch initialized = new CountDownLatch(1);
    private final float[] textureMatrix = new float[16];
    private Thread thread;
    private volatile RuntimeException failure;
    private Surface captureSurface;
    private SurfaceTexture texture;
    private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private EGLContext context = EGL14.EGL_NO_CONTEXT;
    private EGLSurface window = EGL14.EGL_NO_SURFACE;
    private int textureId;
    private int program;
    private int positionLocation;
    private int textureLocation;
    private int matrixLocation;
    private FloatBuffer positions;
    private FloatBuffer coordinates;

    public DlnaVideoSource(Surface encoderSurface, int captureWidth, int captureHeight,
                           int outputWidth, int outputHeight, int fps) {
        this(encoderSurface, captureWidth, captureHeight, outputWidth, outputHeight, fps, ignored -> { });
    }

    public DlnaVideoSource(Surface encoderSurface, int captureWidth, int captureHeight,
                           int outputWidth, int outputHeight, int fps, Consumer<RuntimeException> onError) {
        if (encoderSurface == null || captureWidth <= 0 || captureHeight <= 0
                || outputWidth <= 0 || outputHeight <= 0 || fps <= 0) {
            throw new IllegalArgumentException("Invalid DLNA video source dimensions or rate");
        }
        this.encoderSurface = encoderSurface;
        double captureScale = Math.min(1.0, Math.min((double)outputWidth / captureWidth,
                (double)outputHeight / captureHeight));
        this.captureWidth = Math.max(1, (int)Math.round(captureWidth * captureScale));
        this.captureHeight = Math.max(1, (int)Math.round(captureHeight * captureScale));
        this.outputWidth = outputWidth;
        this.outputHeight = outputHeight;
        this.onError = onError;
        frameIntervalNanos = 1_000_000_000L / fps;
    }

    public int getCaptureWidth() {
        return captureWidth;
    }

    public int getCaptureHeight() {
        return captureHeight;
    }

    public Surface start() {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("DLNA video source already started");
        }
        thread = new Thread(this::render, "DlnaVideoSource");
        thread.setDaemon(true);
        thread.start();
        try {
            if (!initialized.await(3, TimeUnit.SECONDS)) {
                stop();
                throw new IllegalStateException("DLNA video source initialization timed out");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            stop();
            throw new IllegalStateException("DLNA video source initialization interrupted", error);
        }
        if (failure != null) { throw failure; }
        return captureSurface;
    }

    public void stop() {
        running.set(false);
        Thread worker = thread;
        if (worker != null && worker != Thread.currentThread()) {
            worker.interrupt();
            try { worker.join(1500); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            if (worker.isAlive()) { Log.w(TAG, "Encoder surface did not release promptly"); }
        }
    }

    private void render() {
        boolean ready = false;
        boolean unexpectedFailure = false;
        try {
            initialize();
            ready = true;
            initialized.countDown();
            boolean hasFrame = false;
            long nextFrameNanos = System.nanoTime();
            while (running.get()) {
                if (frameAvailable.getAndSet(false)) {
                    texture.updateTexImage();
                    texture.getTransformMatrix(textureMatrix);
                    hasFrame = true;
                }
                if (hasFrame) { draw(System.nanoTime()); }
                nextFrameNanos += frameIntervalNanos;
                long now = System.nanoTime();
                if (nextFrameNanos <= now) { nextFrameNanos = now; }
                LockSupport.parkNanos(nextFrameNanos - now);
            }
        } catch (RuntimeException error) {
            unexpectedFailure = ready && running.get();
            failure = error;
            Log.w(TAG, "Video source stopped: " + error.getMessage());
        } finally {
            running.set(false);
            release();
            initialized.countDown();
            if (unexpectedFailure && onError != null) { onError.accept(failure); }
        }
    }

    private void initialize() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        require(display != EGL14.EGL_NO_DISPLAY, "eglGetDisplay");
        int[] version = new int[2];
        require(EGL14.eglInitialize(display, version, 0, version, 1), "eglInitialize");
        int[] attributes = {EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE};
        EGLConfig[] configs = new EGLConfig[1];
        int[] count = new int[1];
        require(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0)
                && count[0] > 0, "eglChooseConfig");
        context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
        require(context != EGL14.EGL_NO_CONTEXT, "eglCreateContext");
        window = EGL14.eglCreateWindowSurface(display, configs[0], encoderSurface,
                new int[]{EGL14.EGL_NONE}, 0);
        require(window != EGL14.EGL_NO_SURFACE, "eglCreateWindowSurface");
        require(EGL14.eglMakeCurrent(display, window, window, context), "eglMakeCurrent");
        EGL14.eglSwapInterval(display, 0);
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        textureId = textures[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        texture = new SurfaceTexture(textureId);
        texture.setDefaultBufferSize(captureWidth, captureHeight);
        texture.setOnFrameAvailableListener(ignored -> frameAvailable.set(true), new Handler(Looper.getMainLooper()));
        captureSurface = new Surface(texture);
        int vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER);
        int fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
        program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vertexShader);
        GLES20.glAttachShader(program, fragmentShader);
        GLES20.glLinkProgram(program);
        GLES20.glDeleteShader(vertexShader);
        GLES20.glDeleteShader(fragmentShader);
        int[] linked = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0);
        if (linked[0] == 0) { throw new IllegalStateException(GLES20.glGetProgramInfoLog(program)); }
        positionLocation = GLES20.glGetAttribLocation(program, "position");
        textureLocation = GLES20.glGetAttribLocation(program, "texturePosition");
        matrixLocation = GLES20.glGetUniformLocation(program, "textureMatrix");
        float aspect = (float)captureWidth * outputHeight / ((float)captureHeight * outputWidth);
        float horizontal = Math.min(1f, aspect);
        float vertical = Math.min(1f, 1f / aspect);
        positions = buffer(new float[]{-horizontal, -vertical, horizontal, -vertical,
                -horizontal, vertical, horizontal, vertical});
        coordinates = buffer(new float[]{0, 0, 1, 0, 0, 1, 1, 1});
        GLES20.glUseProgram(program);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "frame"), 0);
        GLES20.glClearColor(0, 0, 0, 1);
    }

    private void draw(long timestampNanos) {
        GLES20.glViewport(0, 0, outputWidth, outputHeight);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glUseProgram(program);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
        GLES20.glEnableVertexAttribArray(positionLocation);
        GLES20.glVertexAttribPointer(positionLocation, 2, GLES20.GL_FLOAT, false, 0, positions);
        GLES20.glEnableVertexAttribArray(textureLocation);
        GLES20.glVertexAttribPointer(textureLocation, 2, GLES20.GL_FLOAT, false, 0, coordinates);
        GLES20.glUniformMatrix4fv(matrixLocation, 1, false, textureMatrix, 0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        require(EGLExt.eglPresentationTimeANDROID(display, window, timestampNanos), "eglPresentationTimeANDROID");
        require(EGL14.eglSwapBuffers(display, window), "eglSwapBuffers");
    }

    private static int compileShader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            String message = GLES20.glGetShaderInfoLog(shader);
            GLES20.glDeleteShader(shader);
            throw new IllegalStateException(message);
        }
        return shader;
    }

    private static FloatBuffer buffer(float[] values) {
        FloatBuffer buffer = ByteBuffer.allocateDirect(values.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        buffer.put(values).position(0);
        return buffer;
    }

    private static void require(boolean condition, String operation) {
        if (!condition) { throw new IllegalStateException(operation + " failed: " + EGL14.eglGetError()); }
    }

    private void release() {
        if (captureSurface != null) { captureSurface.release(); }
        if (texture != null) { texture.release(); }
        if (display != EGL14.EGL_NO_DISPLAY) {
            if (program != 0) { GLES20.glDeleteProgram(program); }
            if (textureId != 0) { GLES20.glDeleteTextures(1, new int[]{textureId}, 0); }
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (window != EGL14.EGL_NO_SURFACE) { EGL14.eglDestroySurface(display, window); }
            if (context != EGL14.EGL_NO_CONTEXT) { EGL14.eglDestroyContext(display, context); }
            EGL14.eglReleaseThread();
            EGL14.eglTerminate(display);
        }
    }
}
