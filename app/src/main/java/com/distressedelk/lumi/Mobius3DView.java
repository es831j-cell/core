package com.distressedelk.lumi;

import android.content.Context;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.SystemClock;
import android.view.View;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

/**
 * Code312 seamless real-time Möbius hologram.
 *
 * The ribbon is generated as a parameterized surface with an explicit duplicate
 * closure ring at u=2π. The closure ring occupies the exact same 3D positions
 * as the u=0 ring with the required Möbius V reversal, but keeps continuous
 * shader-U values (near 1.0 instead of jumping directly to 0.0).
 *
 * Animation never pushes along the surface normal. A Möbius strip is
 * non-orientable, so a continuous global normal field cannot exist; normal
 * displacement therefore exposes the closure as a moving crack. Motion is
 * instead applied as smooth periodic whole-surface breathing/deformation.
 */
public final class Mobius3DView extends GLSurfaceView {
    public enum VisualState { PAUSED, READY, LISTENING, THINKING, SPEAKING }

    private final MobiusRenderer renderer;
    private volatile boolean frameDriverRunning;
    private volatile boolean surfacePaused;
    private long frameDriverTicks;
    private long lastDriverTickMs;
    private long frameDriverRecoveries;
    private String lastDriverEvent = "init";

    private final Runnable animationWatchdog = new Runnable() {
        @Override public void run() {
            if (!isAttachedToWindow()) return;
            boolean visible = getVisibility() == View.VISIBLE && getWindowVisibility() == View.VISIBLE;
            if (visible && !surfacePaused) {
                long now = SystemClock.elapsedRealtime();
                long age = lastDriverTickMs == 0 ? Long.MAX_VALUE : now - lastDriverTickMs;
                if (!frameDriverRunning || age > 750L) {
                    frameDriverRecoveries++;
                    startFrameDriver("watchdog");
                } else if (renderer.frameAgeMs() > 750L) {
                    // Driver is ticking but GL stopped producing frames. Nudge the render queue.
                    lastDriverEvent = "watchdog-render-nudge";
                    requestRender();
                }
            }
            postDelayed(this, 500L);
        }
    };

    private final Runnable frameDriver = new Runnable() {
        @Override public void run() {
            if (!frameDriverRunning || !isAttachedToWindow() || getVisibility() != View.VISIBLE) return;
            frameDriverTicks++;
            lastDriverTickMs = SystemClock.elapsedRealtime();
            requestRender();
            postOnAnimation(this);
        }
    };

    public Mobius3DView(Context context) {
        super(context);
        setEGLContextClientVersion(2);
        setPreserveEGLContextOnPause(true);
        renderer = new MobiusRenderer(context.getApplicationContext());
        setRenderer(renderer);
        // Code317: drive rendering from the UI display-vsync explicitly.
        // WHEN_DIRTY + postOnAnimation prevents vendor/GLSurface lifecycle stalls
        // from leaving a valid mesh frozen on screen.
        setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        setFocusable(false);
        setClickable(false);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        surfacePaused = false;
        removeCallbacks(animationWatchdog);
        post(animationWatchdog);
        startFrameDriver("attached");
    }

    @Override protected void onDetachedFromWindow() {
        removeCallbacks(animationWatchdog);
        stopFrameDriver("detached");
        super.onDetachedFromWindow();
    }

    @Override protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (changedView == this) {
            if (visibility == View.VISIBLE && isAttachedToWindow() && !surfacePaused) startFrameDriver("view-visible");
            else if (visibility != View.VISIBLE) stopFrameDriver("view-hidden");
        }
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == View.VISIBLE && isAttachedToWindow() && !surfacePaused) startFrameDriver("window-visible");
        else if (visibility != View.VISIBLE) stopFrameDriver("window-hidden");
    }

    @Override public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (hasWindowFocus && isAttachedToWindow() && getVisibility() == View.VISIBLE && !surfacePaused) {
            startFrameDriver("window-focus");
        }
    }

    @Override public void onResume() {
        super.onResume();
        surfacePaused = false;
        startFrameDriver("gl-resume");
        requestRender();
    }

    @Override public void onPause() {
        surfacePaused = true;
        stopFrameDriver("gl-pause");
        super.onPause();
    }

    private void startFrameDriver() { startFrameDriver("start"); }

    private void startFrameDriver(String reason) {
        boolean wasRunning = frameDriverRunning;
        frameDriverRunning = true;
        lastDriverEvent = reason;
        removeCallbacks(frameDriver);
        postOnAnimation(frameDriver);
        if (!wasRunning) requestRender();
    }

    private void stopFrameDriver() { stopFrameDriver("stop"); }

    private void stopFrameDriver(String reason) {
        frameDriverRunning = false;
        lastDriverEvent = reason;
        removeCallbacks(frameDriver);
    }

    public void forceMaintenanceRecovery() {
        surfacePaused = false;
        startFrameDriver("maintenance-repair");
        requestRender();
    }

    public String diagnosticSnapshot() {
        long now = SystemClock.elapsedRealtime();
        long driverAge = lastDriverTickMs == 0 ? -1 : Math.max(0, now - lastDriverTickMs);
        return "driver=" + (frameDriverRunning ? "RUNNING" : "STOPPED")
                + " • driverTicks=" + frameDriverTicks
                + " • driverAgeMs=" + driverAge
                + " • recoveries=" + frameDriverRecoveries
                + " • lastDriverEvent=" + lastDriverEvent
                + " • surfacePaused=" + surfacePaused
                + " • attached=" + isAttachedToWindow()
                + " • viewVisibility=" + getVisibility()
                + " • windowVisibility=" + getWindowVisibility()
                + " • frames=" + renderer.frameCount
                + " • fps=" + String.format(java.util.Locale.US, "%.1f", renderer.fps)
                + " • frameAgeMs=" + renderer.frameAgeMs()
                + " • phase=" + String.format(java.util.Locale.US, "%.2f", renderer.lastTimeSeconds)
                + " • state=" + renderer.state;
    }

    public void setVisualState(VisualState state) {
        final VisualState next = state == null ? VisualState.READY : state;
        queueEvent(() -> renderer.state = next);
        // Visual PAUSED means calm animation, not a stopped render loop.
        if (isAttachedToWindow() && getVisibility() == View.VISIBLE && getWindowVisibility() == View.VISIBLE && !surfacePaused) {
            startFrameDriver("visual-state-" + next.name().toLowerCase(java.util.Locale.US));
        }
    }

    private static final class MobiusRenderer implements GLSurfaceView.Renderer {
        private final android.content.SharedPreferences prefs;
        MobiusRenderer(Context context) { prefs=context.getSharedPreferences("lumi",Context.MODE_PRIVATE); }
        private static final int SEG_U = 160;
        private static final int SEG_V = 24;
        private static final float RADIUS = 1.22f;
        private static final float HALF_WIDTH = 0.34f;
        private static final int FPV = 8;

        private final float[] projection = new float[16];
        private final float[] view = new float[16];
        private final float[] model = new float[16];
        private final float[] mv = new float[16];
        private final float[] mvp = new float[16];
        private final float[] normalMatrix = new float[16];

        private FloatBuffer vertices;
        private ShortBuffer indices;
        private int indexCount;
        private int program;
        private long startNanos;
        private volatile VisualState state = VisualState.READY;
        private volatile long frameCount;
        private volatile long lastFrameElapsedMs;
        private volatile float fps;
        private volatile float lastTimeSeconds;
        private long fpsWindowStartMs;
        private long fpsWindowFrames;

        long frameAgeMs() {
            long last = lastFrameElapsedMs;
            return last == 0 ? -1 : Math.max(0, SystemClock.elapsedRealtime() - last);
        }

        @Override
        public void onSurfaceCreated(
                javax.microedition.khronos.opengles.GL10 gl,
                javax.microedition.khronos.egl.EGLConfig config) {
            GLES20.glClearColor(0.001f, 0.002f, 0.008f, 1f);
            GLES20.glEnable(GLES20.GL_DEPTH_TEST);
            GLES20.glDisable(GLES20.GL_CULL_FACE);
            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            buildMesh();
            program = buildProgram(VS, FS);
            startNanos = System.nanoTime();
            fpsWindowStartMs = SystemClock.elapsedRealtime();
        }

        @Override
        public void onSurfaceChanged(
                javax.microedition.khronos.opengles.GL10 gl,
                int w,
                int h) {
            GLES20.glViewport(0, 0, w, h);
            float aspect = h == 0 ? 1f : (float) w / h;
            Matrix.perspectiveM(projection, 0, 27f, aspect, 0.1f, 30f);
            Matrix.setLookAtM(view, 0,
                    0f, -0.02f, 7.45f,
                    0f, 0.26f, 0f,
                    0f, 1f, 0f);
        }

        @Override
        public void onDrawFrame(javax.microedition.khronos.opengles.GL10 gl) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
            if (program == 0) return;

            float t = (System.nanoTime() - startNanos) / 1_000_000_000f;
            lastTimeSeconds = t;
            frameCount++;
            fpsWindowFrames++;
            long frameNow = SystemClock.elapsedRealtime();
            lastFrameElapsedMs = frameNow;
            long fpsElapsed = frameNow - fpsWindowStartMs;
            if (fpsElapsed >= 1000L) {
                fps = fpsWindowFrames * 1000f / Math.max(1L, fpsElapsed);
                prefs.edit().putFloat("mobius_last_fps",fps).putLong("mobius_last_frame_age_ms",0L).putLong("mobius_frame_count",frameCount).apply();
                fpsWindowFrames = 0L;
                fpsWindowStartMs = frameNow;
            }
            float rotationSpeed;
            float motionAmp;
            float motionSpeed;
            float brightness;

            switch (state) {
                case PAUSED:
                    rotationSpeed = 1.2f;
                    motionAmp = .0015f;
                    motionSpeed = .22f;
                    brightness = .58f;
                    break;
                case LISTENING:
                    rotationSpeed = 4.2f;
                    motionAmp = .012f;
                    motionSpeed = .85f;
                    brightness = 1.00f;
                    break;
                case THINKING:
                    rotationSpeed = 6.6f;
                    motionAmp = .020f;
                    motionSpeed = 1.35f;
                    brightness = 1.12f;
                    break;
                case SPEAKING:
                    rotationSpeed = 5.2f;
                    motionAmp = .026f;
                    motionSpeed = 1.85f;
                    brightness = 1.16f;
                    break;
                default:
                    rotationSpeed = 2.5f;
                    motionAmp = .006f;
                    motionSpeed = .42f;
                    brightness = .82f;
                    break;
            }

            Matrix.setIdentityM(model, 0);
            Matrix.translateM(model, 0, 0f, .31f, 0f);
            Matrix.scaleM(model, 0, .70f, .70f, .70f);

            // Whole-object motion only. No per-segment transforms.
            Matrix.rotateM(model, 0, -7f, 1f, 0f, 0f);
            Matrix.rotateM(model, 0, -8f + t * rotationSpeed, 0f, 1f, 0f);
            Matrix.rotateM(model, 0,
                    2.2f * (float) Math.sin(t * .16f),
                    0f, 0f, 1f);

            Matrix.multiplyMM(mv, 0, view, 0, model, 0);
            Matrix.multiplyMM(mvp, 0, projection, 0, mv, 0);
            System.arraycopy(model, 0, normalMatrix, 0, 16);

            GLES20.glUseProgram(program);

            int ap = GLES20.glGetAttribLocation(program, "aPosition");
            int an = GLES20.glGetAttribLocation(program, "aNormal");
            int au = GLES20.glGetAttribLocation(program, "aU");
            int av = GLES20.glGetAttribLocation(program, "aV");

            vertices.position(0);
            GLES20.glVertexAttribPointer(ap, 3, GLES20.GL_FLOAT, false, FPV * 4, vertices);
            GLES20.glEnableVertexAttribArray(ap);

            vertices.position(3);
            GLES20.glVertexAttribPointer(an, 3, GLES20.GL_FLOAT, false, FPV * 4, vertices);
            GLES20.glEnableVertexAttribArray(an);

            vertices.position(6);
            GLES20.glVertexAttribPointer(au, 1, GLES20.GL_FLOAT, false, FPV * 4, vertices);
            GLES20.glEnableVertexAttribArray(au);

            vertices.position(7);
            GLES20.glVertexAttribPointer(av, 1, GLES20.GL_FLOAT, false, FPV * 4, vertices);
            GLES20.glEnableVertexAttribArray(av);

            GLES20.glUniformMatrix4fv(
                    GLES20.glGetUniformLocation(program, "uMvp"),
                    1, false, mvp, 0);
            GLES20.glUniformMatrix4fv(
                    GLES20.glGetUniformLocation(program, "uModel"),
                    1, false, model, 0);
            GLES20.glUniformMatrix4fv(
                    GLES20.glGetUniformLocation(program, "uNormalMatrix"),
                    1, false, normalMatrix, 0);
            GLES20.glUniform1f(
                    GLES20.glGetUniformLocation(program, "uTime"), t);
            GLES20.glUniform1f(
                    GLES20.glGetUniformLocation(program, "uMotionAmp"), motionAmp);
            GLES20.glUniform1f(
                    GLES20.glGetUniformLocation(program, "uMotionSpeed"), motionSpeed);
            GLES20.glUniform1f(
                    GLES20.glGetUniformLocation(program, "uBrightness"), brightness);

            indices.position(0);
            GLES20.glDrawElements(
                    GLES20.GL_TRIANGLES,
                    indexCount,
                    GLES20.GL_UNSIGNED_SHORT,
                    indices);

            GLES20.glDisableVertexAttribArray(ap);
            GLES20.glDisableVertexAttribArray(an);
            GLES20.glDisableVertexAttribArray(au);
            GLES20.glDisableVertexAttribArray(av);
        }

        /**
         * Code366 seam repair. Build one topological Möbius surface with no duplicate
         * u=2π ring. The final segment connects directly back to u=0 with v reversed,
         * which welds the geometry instead of stacking two coincident edge rings.
         */
        private void buildMesh() {
            final int rows = SEG_V + 1;
            final int cols = SEG_U;
            float[] data = new float[rows * cols * FPV];
            int p = 0;

            for (int i = 0; i < SEG_U; i++) {
                double u = Math.PI * 2.0 * i / SEG_U;
                for (int j = 0; j <= SEG_V; j++) {
                    double v = -HALF_WIDTH + (HALF_WIDTH * 2.0 * j) / SEG_V;
                    float[] pos = position(u, v);
                    float[] du = derivativeU(u, v);
                    float[] dv = derivativeV(u, v);

                    float nx = du[1] * dv[2] - du[2] * dv[1];
                    float ny = du[2] * dv[0] - du[0] * dv[2];
                    float nz = du[0] * dv[1] - du[1] * dv[0];
                    float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                    if (len < 1e-6f) len = 1f;

                    data[p++] = pos[0];
                    data[p++] = pos[1];
                    data[p++] = pos[2];
                    data[p++] = nx / len;
                    data[p++] = ny / len;
                    data[p++] = nz / len;
                    data[p++] = (float) i / SEG_U;
                    data[p++] = (float) j / SEG_V;
                }
            }

            short[] ix = new short[SEG_U * SEG_V * 6];
            int q = 0;
            for (int i = 0; i < SEG_U; i++) {
                boolean closure = (i == SEG_U - 1);
                int next = (i + 1) % SEG_U;
                for (int j = 0; j < SEG_V; j++) {
                    int a = i * rows + j;
                    int d = a + 1;
                    int b;
                    int c;
                    if (closure) {
                        b = next * rows + (SEG_V - j);
                        c = next * rows + (SEG_V - (j + 1));
                    } else {
                        b = next * rows + j;
                        c = b + 1;
                    }
                    ix[q++] = (short) a;
                    ix[q++] = (short) b;
                    ix[q++] = (short) c;
                    ix[q++] = (short) a;
                    ix[q++] = (short) c;
                    ix[q++] = (short) d;
                }
            }

            vertices = ByteBuffer.allocateDirect(data.length * 4)
                    .order(ByteOrder.nativeOrder())
                    .asFloatBuffer();
            vertices.put(data).position(0);

            indices = ByteBuffer.allocateDirect(ix.length * 2)
                    .order(ByteOrder.nativeOrder())
                    .asShortBuffer();
            indices.put(ix).position(0);
            indexCount = ix.length;
        }

        private static float[] position(double u, double v) {
            double c = Math.cos(u * .5);
            double s = Math.sin(u * .5);
            double ring = RADIUS + v * c;
            return new float[]{
                    (float) (ring * Math.cos(u)),
                    (float) (ring * Math.sin(u)),
                    (float) (v * s)
            };
        }

        private static float[] derivativeU(double u, double v) {
            double e = .0008;
            float[] a = position(u - e, v);
            float[] b = position(u + e, v);
            return new float[]{
                    (float) ((b[0] - a[0]) / (2 * e)),
                    (float) ((b[1] - a[1]) / (2 * e)),
                    (float) ((b[2] - a[2]) / (2 * e))
            };
        }

        private static float[] derivativeV(double u, double v) {
            double e = .0008;
            float[] a = position(u, v - e);
            float[] b = position(u, v + e);
            return new float[]{
                    (float) ((b[0] - a[0]) / (2 * e)),
                    (float) ((b[1] - a[1]) / (2 * e)),
                    (float) ((b[2] - a[2]) / (2 * e))
            };
        }

        private static int buildProgram(String vs, String fs) {
            int v = compile(GLES20.GL_VERTEX_SHADER, vs);
            int f = compile(GLES20.GL_FRAGMENT_SHADER, fs);
            if (v == 0 || f == 0) return 0;

            int p = GLES20.glCreateProgram();
            GLES20.glAttachShader(p, v);
            GLES20.glAttachShader(p, f);
            GLES20.glLinkProgram(p);

            int[] ok = new int[1];
            GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
            if (ok[0] == 0) {
                GLES20.glDeleteProgram(p);
                p = 0;
            }

            GLES20.glDeleteShader(v);
            GLES20.glDeleteShader(f);
            return p;
        }

        private static int compile(int type, String src) {
            int s = GLES20.glCreateShader(type);
            GLES20.glShaderSource(s, src);
            GLES20.glCompileShader(s);

            int[] ok = new int[1];
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
            if (ok[0] == 0) {
                GLES20.glDeleteShader(s);
                return 0;
            }
            return s;
        }

        /**
         * Seam-safe deformation:
         * - aU is normalized 0..1.
         * - all waves use integer cycles, so values at 0 and 1 match exactly.
         * - no normal displacement.
         * - deformation is applied to the whole parameterized surface.
         */
        private static final String VS =
                "uniform mat4 uMvp; uniform mat4 uModel; uniform mat4 uNormalMatrix;"
              + "uniform float uTime; uniform float uMotionAmp; uniform float uMotionSpeed;"
              + "attribute vec3 aPosition; attribute vec3 aNormal; attribute float aU; attribute float aV;"
              + "varying vec3 vNormal; varying vec3 vWorld; varying float vWave; varying float vU; varying float vV;"
              + "void main(){"
              + "float tau=6.28318530718;"
              + "float phase=aU*tau;"
              + "float wave=sin(phase*2.0-uTime*uMotionSpeed*2.0);"
              + "float wave2=sin(phase*3.0+uTime*uMotionSpeed*1.15);"
              + "float breathe=1.0+uMotionAmp*(0.55*wave+0.25*wave2);"
              + "vec3 pos=aPosition;"
              + "pos.xy*=breathe;"
              + "pos.z*=1.0+uMotionAmp*0.45*wave;"
              + "pos.z+=uMotionAmp*0.11*sin(phase*2.0-uTime*uMotionSpeed);"
              + "vec4 world=uModel*vec4(pos,1.0);"
              + "vWorld=world.xyz;"
              + "vNormal=normalize((uNormalMatrix*vec4(aNormal,0.0)).xyz);"
              + "vWave=.5+.5*wave;"
              + "vU=aU;"
              + "vV=aV;"
              + "gl_Position=uMvp*vec4(pos,1.0);"
              + "}";

        /**
         * Two-sided glass lighting is deliberate. A Möbius strip has no globally
         * consistent front normal, so abs(dot()) avoids a lighting flip at closure.
         */
        private static final String FS =
                "precision mediump float;"
              + "uniform float uBrightness;"
              + "varying vec3 vNormal; varying vec3 vWorld; varying float vWave; varying float vU; varying float vV;"
              + "void main(){"
              + "float tau=6.28318530718;"
              + "vec3 n=normalize(vNormal); vec3 light=normalize(vec3(-.35,.55,.82));"
              + "float diff=.30+.70*abs(dot(n,light));"
              + "vec3 viewDir=normalize(vec3(0.0,0.0,7.0)-vWorld); float rim=pow(1.0-abs(dot(n,viewDir)),2.2);"
              + "float seamPhase=cos(tau*vU); float seamPhase2=sin(tau*vU);"
              + "float hue=.5+.5*sin(tau*(2.0*vU)+.8);"
              + "vec3 violet=vec3(.50,.12,1.0); vec3 cyan=vec3(.08,.82,1.0); vec3 base=mix(violet,cyan,hue);"
              + "float u=fract(vU*18.0); float vv=fract((vV+.5*seamPhase)*6.0);"
              + "float lineU=1.0-smoothstep(.035,.09,abs(u-.5)); float lineV=1.0-smoothstep(.035,.09,abs(vv-.5));"
              + "float circuitry=max(lineU*.20,lineV*.16);"
              + "float cell=floor(vU*18.0); float selector=fract(sin((cell+3.0)*91.713)*43758.5453);"
              + "float gx=fract(vU*18.0)-.5; float gy=fract((vV+.5*seamPhase)*5.0)-.5;"
              + "float bar=1.0-smoothstep(.055,.115,abs(gx));"
              + "float diagA=1.0-smoothstep(.055,.12,abs(gx-gy*.72)); float diagB=1.0-smoothstep(.055,.12,abs(gx+gy*.72));"
              + "float cap=1.0-smoothstep(.055,.12,abs(gy-.24)); float dotRune=1.0-smoothstep(.08,.15,length(vec2(gx,gy+.22)));"
              + "float gate=step(.18,abs(gy))*step(abs(gy),.46);"
              + "float runeShape=mix(max(diagA,diagB),max(bar,max(cap,dotRune)),step(.50,selector));"
              + "float rune=gate*runeShape;"
              + "float spec=pow(max(0.0,abs(dot(reflect(-light,n),viewDir))),20.0);"
              + "vec3 glow=mix(vec3(.72,.30,1.0),vec3(.25,1.0,1.0),.5+.5*seamPhase2);"
              + "vec3 color=base*(.24+.64*diff)+base*rim*1.35+vec3(1.0)*spec*.72;"
              + "color+=base*circuitry*.38; color+=glow*rune*(2.05+.45*vWave); color*=uBrightness;"
              + "float alpha=.76+rim*.16+rune*.08; gl_FragColor=vec4(color,clamp(alpha,0.0,1.0));"
              + "}";
    }
}
