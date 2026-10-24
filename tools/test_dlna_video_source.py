"""Check static-frame pacing and GL ownership using a recording Android boundary."""
from pathlib import Path
import os
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
STUBS = {
    "android/util/Log.java": "package android.util; public class Log { public static int i(String tag,String text){return 0;} public static int w(String tag,String text){return 0;} }",
    "android/os/Looper.java": "package android.os; public class Looper { public static Looper getMainLooper(){return new Looper();} }",
    "android/os/Handler.java": "package android.os; public class Handler { public Handler(Looper looper){} }",
    "android/view/Surface.java": "package android.view; public class Surface { public boolean released; public Surface(){} public Surface(android.graphics.SurfaceTexture texture){} public void release(){released=true;} }",
    "android/graphics/SurfaceTexture.java": """package android.graphics;
public class SurfaceTexture {
    public static volatile SurfaceTexture latest;
    public volatile boolean released;
    public volatile int updates;
    public int width;
    public int height;
    private OnFrameAvailableListener listener;
    public interface OnFrameAvailableListener { void onFrameAvailable(SurfaceTexture texture); }
    public SurfaceTexture(int texture){latest=this;}
    public void setDefaultBufferSize(int width,int height){this.width=width;this.height=height;}
    public void setOnFrameAvailableListener(OnFrameAvailableListener listener,android.os.Handler handler){this.listener=listener;}
    public void fire(){listener.onFrameAvailable(this);}
    public void updateTexImage(){updates++;}
    public void getTransformMatrix(float[] matrix){for(int index=0;index<16;index++)matrix[index]=index%5==0?1:0;}
    public void release(){released=true;}
}""",
    "android/opengl/EGLDisplay.java": "package android.opengl; public class EGLDisplay {}",
    "android/opengl/EGLContext.java": "package android.opengl; public class EGLContext {}",
    "android/opengl/EGLSurface.java": "package android.opengl; public class EGLSurface {}",
    "android/opengl/EGLConfig.java": "package android.opengl; public class EGLConfig {}",
    "android/opengl/GLES11Ext.java": "package android.opengl; public class GLES11Ext {public static final int GL_TEXTURE_EXTERNAL_OES=36197;}",
    "android/opengl/EGLExt.java": """package android.opengl;
public class EGLExt {
    public static final java.util.List<Long> timestamps=java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    public static boolean eglPresentationTimeANDROID(EGLDisplay display,EGLSurface surface,long timestamp){timestamps.add(timestamp);return true;}
}""",
    "android/opengl/EGL14.java": """package android.opengl;
public class EGL14 {
    public static final EGLDisplay EGL_NO_DISPLAY=new EGLDisplay();
    public static final EGLContext EGL_NO_CONTEXT=new EGLContext();
    public static final EGLSurface EGL_NO_SURFACE=new EGLSurface();
    public static final int EGL_DEFAULT_DISPLAY=0,EGL_RED_SIZE=1,EGL_GREEN_SIZE=2,EGL_BLUE_SIZE=3,EGL_ALPHA_SIZE=4,EGL_RENDERABLE_TYPE=5,EGL_OPENGL_ES2_BIT=6,EGL_NONE=7,EGL_CONTEXT_CLIENT_VERSION=8;
    public static volatile boolean failConfig;
    public static volatile boolean failSwap;
    public static volatile long swapDelayMillis;
    public static volatile int terminated;
    public static EGLDisplay eglGetDisplay(int display){return new EGLDisplay();}
    public static boolean eglInitialize(EGLDisplay display,int[] major,int majorOffset,int[] minor,int minorOffset){return true;}
    public static boolean eglChooseConfig(EGLDisplay display,int[] attributes,int offset,EGLConfig[] configs,int configsOffset,int count,int[] found,int foundOffset){configs[0]=new EGLConfig();found[0]=1;return !failConfig;}
    public static EGLContext eglCreateContext(EGLDisplay display,EGLConfig config,EGLContext shared,int[] attributes,int offset){return new EGLContext();}
    public static EGLSurface eglCreateWindowSurface(EGLDisplay display,EGLConfig config,Object window,int[] attributes,int offset){return new EGLSurface();}
    public static boolean eglMakeCurrent(EGLDisplay display,EGLSurface draw,EGLSurface read,EGLContext context){return true;}
    public static boolean eglSwapInterval(EGLDisplay display,int interval){return true;}
    public static boolean eglSwapBuffers(EGLDisplay display,EGLSurface surface){try{Thread.sleep(swapDelayMillis);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}return !failSwap;}
    public static boolean eglDestroySurface(EGLDisplay display,EGLSurface surface){return true;}
    public static boolean eglDestroyContext(EGLDisplay display,EGLContext context){return true;}
    public static boolean eglReleaseThread(){return true;}
    public static boolean eglTerminate(EGLDisplay display){terminated++;return true;}
    public static int eglGetError(){return 0;}
}""",
    "android/opengl/GLES20.java": """package android.opengl;
public class GLES20 {
    public static final int GL_VERTEX_SHADER=1,GL_FRAGMENT_SHADER=2,GL_COMPILE_STATUS=3,GL_LINK_STATUS=4,GL_TEXTURE_MIN_FILTER=5,GL_TEXTURE_MAG_FILTER=6,GL_LINEAR=7,GL_TEXTURE_WRAP_S=8,GL_TEXTURE_WRAP_T=9,GL_CLAMP_TO_EDGE=10,GL_FLOAT=11,GL_TRIANGLE_STRIP=12,GL_COLOR_BUFFER_BIT=13,GL_TEXTURE0=14;
    public static void glGenTextures(int count,int[] textures,int offset){textures[0]=1;}
    public static void glBindTexture(int target,int texture){}
    public static void glTexParameteri(int target,int parameter,int value){}
    public static int glCreateShader(int type){return type;}
    public static void glShaderSource(int shader,String source){}
    public static void glCompileShader(int shader){}
    public static void glGetShaderiv(int shader,int key,int[] value,int offset){value[offset]=1;}
    public static String glGetShaderInfoLog(int shader){return "";}
    public static void glDeleteShader(int shader){}
    public static int glCreateProgram(){return 1;}
    public static void glAttachShader(int program,int shader){}
    public static void glLinkProgram(int program){}
    public static void glGetProgramiv(int program,int key,int[] value,int offset){value[offset]=1;}
    public static String glGetProgramInfoLog(int program){return "";}
    public static void glUseProgram(int program){}
    public static int glGetAttribLocation(int program,String name){return 1;}
    public static int glGetUniformLocation(int program,String name){return 1;}
    public static void glEnableVertexAttribArray(int location){}
    public static void glVertexAttribPointer(int location,int size,int type,boolean normalized,int stride,java.nio.Buffer data){}
    public static void glUniformMatrix4fv(int location,int count,boolean transpose,float[] values,int offset){}
    public static void glUniform1i(int location,int value){}
    public static void glActiveTexture(int texture){}
    public static void glViewport(int left,int top,int width,int height){}
    public static void glClearColor(float red,float green,float blue,float alpha){}
    public static void glClear(int mask){}
    public static void glDrawArrays(int mode,int first,int count){}
    public static void glDeleteProgram(int program){}
    public static void glDeleteTextures(int count,int[] textures,int offset){}
}""",
}

JAVA = r'''
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLExt;
import android.view.Surface;
import java.lang.reflect.*;
import java.util.*;

public class VideoSourceRegression {
    static Object create(Surface output) throws Exception {
        Class<?> type;
        try { type = Class.forName("com.nightmare.sunshine.dlna.DlnaVideoSource"); }
        catch (ClassNotFoundException missing) {
            throw new AssertionError("static source pixels have no continuous encoder-input cadence");
        }
        return type.getConstructor(Surface.class, int.class, int.class, int.class, int.class, int.class)
                .newInstance(output, 1080, 1920, 1280, 720, 25);
    }
    static Object call(Object source, String method) throws Exception {
        return source.getClass().getMethod(method).invoke(source);
    }
    public static void main(String[] arguments) throws Exception {
        Surface output = new Surface();
        Object source = create(output);
        Surface capture = (Surface)call(source, "start");
        try {
            if (SurfaceTexture.latest.width != 405 || SurfaceTexture.latest.height != 720) {
                throw new AssertionError("capture allocates pixels discarded by the encoder aspect-fit: "
                        + SurfaceTexture.latest.width + "x" + SurfaceTexture.latest.height);
            }
            if (!call(source, "getCaptureWidth").equals(405) || !call(source, "getCaptureHeight").equals(720)) {
                throw new AssertionError("VirtualDisplay cannot use the source's fitted capture size");
            }
            Thread.sleep(80);
            if (!EGLExt.timestamps.isEmpty()) throw new AssertionError("invented frames before first capture");
            SurfaceTexture.latest.fire();
            long deadline = System.nanoTime() + 1_500_000_000L;
            while (EGLExt.timestamps.size() < 12 && System.nanoTime() < deadline) Thread.sleep(5);
            if (EGLExt.timestamps.size() < 12) throw new AssertionError("one captured frame stopped repeating");
            List<Long> timestamps = new ArrayList<>(EGLExt.timestamps);
            long span = timestamps.get(11) - timestamps.get(0);
            if (span < 350_000_000L || span > 900_000_000L) throw new AssertionError("not paced near 25 FPS: " + span);
            for (int index = 1; index < timestamps.size(); index++) {
                if (timestamps.get(index) <= timestamps.get(index - 1)) throw new AssertionError("encoder input PTS regressed");
            }
            if (SurfaceTexture.latest.updates != 1) throw new AssertionError("source texture consumed without a new frame");
            SurfaceTexture.latest.fire();
            deadline = System.nanoTime() + 500_000_000L;
            while (SurfaceTexture.latest.updates < 2 && System.nanoTime() < deadline) Thread.sleep(5);
            if (SurfaceTexture.latest.updates != 2) throw new AssertionError("new capture not consumed");
            System.out.println("PASS one static texture is re-encoded continuously with advancing timestamps");
        } finally { call(source, "stop"); }
        int count = EGLExt.timestamps.size();
        Thread.sleep(100);
        if (EGLExt.timestamps.size() != count || !capture.released || !SurfaceTexture.latest.released
                || output.released || EGL14.terminated != 1) {
            throw new AssertionError("source shutdown leaked GL work or released encoder-owned Surface");
        }
        call(source, "stop");
        System.out.println("PASS shutdown is idempotent and releases only source-owned resources");
        EGL14.failConfig = true;
        Object broken = create(new Surface());
        try {
            call(broken, "start");
            throw new AssertionError("EGL configuration error was hidden");
        } catch (InvocationTargetException expected) {
            if (!(expected.getCause() instanceof IllegalStateException)) throw expected;
        } finally { call(broken, "stop"); }
        if (EGL14.terminated != 2) throw new AssertionError("partial EGL setup leaked");
        System.out.println("PASS setup failure reaches caller and cleans partial GL state");
        EGL14.failConfig = false;
        EGL14.swapDelayMillis = 45;
        EGLExt.timestamps.clear();
        Object slow = create(new Surface());
        call(slow, "start");
        try {
            SurfaceTexture.latest.fire();
            long deadline = System.nanoTime() + 2_000_000_000L;
            while (EGLExt.timestamps.size() < 10 && System.nanoTime() < deadline) Thread.sleep(5);
            if (EGLExt.timestamps.size() < 10) throw new AssertionError("slow swap stalled permanently");
            long span = EGLExt.timestamps.get(9) - EGLExt.timestamps.get(0);
            if (span > 650_000_000L) throw new AssertionError("overrun added a redundant frame wait: " + span);
            System.out.println("PASS an overdue render does not add another whole frame of idle time");
        } finally { call(slow, "stop"); }
        EGL14.swapDelayMillis = 0;
        EGLExt.timestamps.clear();
        java.util.concurrent.atomic.AtomicReference<RuntimeException> reported = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.function.Consumer<RuntimeException> onError = reported::set;
        Object failing;
        try {
            failing = Class.forName("com.nightmare.sunshine.dlna.DlnaVideoSource")
                    .getConstructor(Surface.class, int.class, int.class, int.class, int.class, int.class,
                            java.util.function.Consumer.class)
                    .newInstance(new Surface(), 1080, 1920, 1280, 720, 25, onError);
        } catch (NoSuchMethodException missing) {
            throw new AssertionError("post-start source failure has no owner callback");
        }
        Surface failedCapture = (Surface)call(failing, "start");
        try {
            SurfaceTexture.latest.fire();
            long deadline = System.nanoTime() + 1_000_000_000L;
            while (EGLExt.timestamps.size() < 3 && System.nanoTime() < deadline) Thread.sleep(5);
            EGL14.failSwap = true;
            while (reported.get() == null && System.nanoTime() < deadline) Thread.sleep(5);
            if (reported.get() == null || !failedCapture.released || !SurfaceTexture.latest.released) {
                throw new AssertionError("failed render did not notify owner after cleanup");
            }
            System.out.println("PASS post-start rendering failure notifies owner and releases the source");
        } finally { call(failing, "stop"); }
    }
}
'''

with tempfile.TemporaryDirectory(prefix="dlna-video-source-") as directory:
    temporary = Path(directory)
    files = []
    for relative, text in STUBS.items():
        destination = temporary / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text(text, encoding="utf-8")
        files.append(str(destination))
    harness = temporary / "VideoSourceRegression.java"
    harness.write_text(JAVA, encoding="utf-8")
    files.append(str(harness))
    implementation = ROOT / "android/app/src/main/java/com/nightmare/sunshine/dlna/DlnaVideoSource.java"
    if implementation.exists():
        files.append(str(implementation))
    jdk = Path(os.environ.get("JAVA_HOME", r"C:\Program Files\Java\jdk-17")) / "bin"
    subprocess.run([str(jdk / "javac"), "-encoding", "UTF-8", "-d", str(temporary), *files], check=True)
    subprocess.run([str(jdk / "java"), "-cp", str(temporary), "VideoSourceRegression"], check=True)
