package com.distressedelk.lumi;

import android.content.Context;
import android.content.SharedPreferences;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.SystemClock;
import android.view.View;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.Locale;

/** Code388 R105 approved layered-pyramid conversation core.
 * Listening=deep forest green, Thinking=crimson, Speaking=violet.
 * Every state-color change eases over six seconds. Idle motion is bounded X/Y drift, never flat Z spin.
 */
public final class LumiPyramid3DView extends GLSurfaceView {
    public enum VisualState { IDLE, PAUSED, READY, LISTENING, THINKING, SPEAKING }
    static final long COLOR_TRANSITION_MS = 6000L;

    private final PyramidRenderer renderer;
    private final SharedPreferences telemetryPrefs;
    private volatile boolean frameDriverRunning;
    private volatile boolean surfacePaused;
    private long frameDriverTicks;
    private long lastDriverTickMs;
    private long frameDriverRecoveries;
    private String lastDriverEvent="init";

    private final Runnable animationWatchdog=new Runnable(){
        @Override public void run(){
            if(!isAttachedToWindow()) return;
            boolean visible=getVisibility()==View.VISIBLE && getWindowVisibility()==View.VISIBLE;
            if(visible && !surfacePaused){
                long now=SystemClock.elapsedRealtime();
                long age=lastDriverTickMs==0?Long.MAX_VALUE:now-lastDriverTickMs;
                if(!frameDriverRunning || age>750L){ frameDriverRecoveries++; startFrameDriver("watchdog"); }
                else if(renderer.frameAgeMs()>750L){ lastDriverEvent="watchdog-render-nudge"; requestRender(); }
            }
            postDelayed(this,500L);
        }
    };

    private final Runnable frameDriver=new Runnable(){
        @Override public void run(){
            if(!frameDriverRunning || !isAttachedToWindow() || getVisibility()!=View.VISIBLE) return;
            frameDriverTicks++; lastDriverTickMs=SystemClock.elapsedRealtime(); requestRender();
            VisualState st=renderer.state;
            long delay=(st==VisualState.IDLE || st==VisualState.READY || st==VisualState.PAUSED)?33L:16L;
            postDelayed(this,delay);
        }
    };

    public LumiPyramid3DView(Context context){
        super(context);
        telemetryPrefs=context.getSharedPreferences("lumi",Context.MODE_PRIVATE);
        setEGLContextClientVersion(2);
        setPreserveEGLContextOnPause(false);
        renderer=new PyramidRenderer(context.getApplicationContext());
        setRenderer(renderer);
        setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        setFocusable(false); setClickable(false);
        markMount("constructed","view created");
    }

    private void markMount(String state,String detail){
        try{
            telemetryPrefs.edit()
                    .putString("pyramid_mount_state",state)
                    .putString("pyramid_mount_detail",detail==null?"":detail)
                    .putLong("pyramid_mount_event_at",System.currentTimeMillis())
                    .putBoolean("pyramid_mount_attached",isAttachedToWindow())
                    .putBoolean("pyramid_mount_visible",getVisibility()==View.VISIBLE && getWindowVisibility()==View.VISIBLE)
                    .apply();
        }catch(Throwable ignored){}
    }

    @Override protected void onAttachedToWindow(){ super.onAttachedToWindow(); surfacePaused=false; markMount("attached","home visual attached to window"); removeCallbacks(animationWatchdog); post(animationWatchdog); startFrameDriver("attached"); }
    @Override protected void onDetachedFromWindow(){ markMount("detached","visual removed from current screen"); removeCallbacks(animationWatchdog); stopFrameDriver("detached"); super.onDetachedFromWindow(); }
    @Override protected void onVisibilityChanged(View changedView,int visibility){ super.onVisibilityChanged(changedView,visibility); if(changedView==this){ markMount(visibility==View.VISIBLE?"visible":"hidden","view visibility="+visibility); if(visibility==View.VISIBLE && isAttachedToWindow() && !surfacePaused) startFrameDriver("view-visible"); else if(visibility!=View.VISIBLE) stopFrameDriver("view-hidden"); } }
    @Override protected void onWindowVisibilityChanged(int visibility){ super.onWindowVisibilityChanged(visibility); markMount(visibility==View.VISIBLE?"window-visible":"window-hidden","window visibility="+visibility); if(visibility==View.VISIBLE && isAttachedToWindow() && !surfacePaused) startFrameDriver("window-visible"); else if(visibility!=View.VISIBLE) stopFrameDriver("window-hidden"); }
    @Override public void onWindowFocusChanged(boolean focus){ super.onWindowFocusChanged(focus); if(focus && isAttachedToWindow() && getVisibility()==View.VISIBLE && !surfacePaused) startFrameDriver("window-focus"); }
    @Override public void onResume(){ super.onResume(); surfacePaused=false; markMount("resumed","GL surface resumed"); startFrameDriver("gl-resume"); requestRender(); }
    @Override public void onPause(){ surfacePaused=true; markMount("paused","GL surface paused"); stopFrameDriver("gl-pause"); super.onPause(); }

    private void startFrameDriver(String reason){ boolean was=frameDriverRunning; frameDriverRunning=true; lastDriverEvent=reason; markMount("running","frameDriver="+reason); removeCallbacks(frameDriver); post(frameDriver); if(!was) requestRender(); }
    private void stopFrameDriver(String reason){ frameDriverRunning=false; lastDriverEvent=reason; markMount("stopped","frameDriver="+reason); removeCallbacks(frameDriver); }
    public void forceMaintenanceRecovery(){ surfacePaused=false; markMount("recovered","forced visual recovery"); startFrameDriver("maintenance-repair"); requestRender(); }

    public void setVisualState(VisualState next){
        final VisualState state=next==null?VisualState.IDLE:next;
        queueEvent(()->renderer.setState(state));
        if(isAttachedToWindow() && getVisibility()==View.VISIBLE && getWindowVisibility()==View.VISIBLE && !surfacePaused) startFrameDriver("visual-state-"+state.name().toLowerCase(Locale.US));
    }

    public String diagnosticSnapshot(){
        long now=SystemClock.elapsedRealtime(); long age=lastDriverTickMs==0?-1:Math.max(0,now-lastDriverTickMs);
        return "renderer=approved-layered-pyramid-r105 • driver="+(frameDriverRunning?"RUNNING":"STOPPED")
                +" • driverTicks="+frameDriverTicks+" • driverAgeMs="+age+" • recoveries="+frameDriverRecoveries
                +" • lastDriverEvent="+lastDriverEvent+" • surfacePaused="+surfacePaused+" • frames="+renderer.frameCount
                +" • fps="+String.format(Locale.US,"%.1f",renderer.fps)+" • targetFps="+((renderer.state==VisualState.IDLE||renderer.state==VisualState.READY||renderer.state==VisualState.PAUSED)?30:60)+" • frameAgeMs="+renderer.frameAgeMs()
                +" • state="+renderer.state+" • transitionMs="+COLOR_TRANSITION_MS+" • wireframe="+renderer.wireframe+" • framing=portrait-safe-38deg-y0.50-z7.8 • contract=approved-layered-pyramid-v2";
    }

    private static final class PyramidRenderer implements GLSurfaceView.Renderer {
        private static final int FPV=9;
        private final android.content.SharedPreferences prefs;
        private final float[] projection=new float[16],view=new float[16],model=new float[16],mv=new float[16],mvp=new float[16],normalMatrix=new float[16];
        private FloatBuffer vertices; private ShortBuffer indices; private int indexCount; private int program;
        private long startNanos; private volatile VisualState state=VisualState.IDLE;
        private final float[] fromColor={.50f,.12f,1.00f}; private final float[] toColor={.50f,.12f,1.00f}; private final float[] renderedColor={.50f,.12f,1.00f};
        private long transitionStartMs=0L;
        private volatile long frameCount,lastFrameElapsedMs; private volatile float fps; private long fpsWindowStartMs,fpsWindowFrames; private volatile boolean wireframe;

        PyramidRenderer(Context context){ prefs=context.getSharedPreferences("lumi",Context.MODE_PRIVATE); }
        long frameAgeMs(){ long last=lastFrameElapsedMs; return last==0?-1:Math.max(0,SystemClock.elapsedRealtime()-last); }

        void setState(VisualState next){
            long now=SystemClock.elapsedRealtime(); updateColor(now);
            System.arraycopy(renderedColor,0,fromColor,0,3);
            float[] target=colorFor(next); System.arraycopy(target,0,toColor,0,3);
            transitionStartMs=now; state=next;
        }

        private float[] colorFor(VisualState s){
            if(s==VisualState.LISTENING) return new float[]{.015f,.30f,.115f}; // deep forest green
            if(s==VisualState.THINKING) return new float[]{.86f,.025f,.115f}; // crimson
            if(s==VisualState.SPEAKING) return new float[]{.52f,.12f,1.00f}; // approved violet
            return new float[]{.38f,.10f,.72f}; // dim violet idle
        }

        private void updateColor(long now){
            float p=transitionStartMs<=0?1f:Math.max(0f,Math.min(1f,(now-transitionStartMs)/(float)COLOR_TRANSITION_MS));
            float e=p*p*(3f-2f*p);
            for(int i=0;i<3;i++) renderedColor[i]=fromColor[i]+(toColor[i]-fromColor[i])*e;
        }

        @Override public void onSurfaceCreated(javax.microedition.khronos.opengles.GL10 gl,javax.microedition.khronos.egl.EGLConfig cfg){
            GLES20.glClearColor(.002f,.003f,.008f,1f); GLES20.glEnable(GLES20.GL_DEPTH_TEST); GLES20.glDisable(GLES20.GL_CULL_FACE);
            GLES20.glEnable(GLES20.GL_BLEND); GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA,GLES20.GL_ONE_MINUS_SRC_ALPHA);
            buildMesh(); program=buildProgram(VS,FS); startNanos=System.nanoTime(); fpsWindowStartMs=SystemClock.elapsedRealtime(); transitionStartMs=fpsWindowStartMs;
        }
        @Override public void onSurfaceChanged(javax.microedition.khronos.opengles.GL10 gl,int w,int h){
            GLES20.glViewport(0,0,w,h); float aspect=h==0?1f:(float)w/h; Matrix.perspectiveM(projection,0,38f,aspect,.1f,40f);
            // R96: compact three-quarter portrait view. More side-face depth is visible while
            // the crown and tip remain comfortably inside a phone portrait viewport.
            Matrix.setLookAtM(view,0,0f,.50f,7.80f,0f,-.06f,0f,0f,1f,0f);
            prefs.edit().putInt("pyramid_surface_width",w).putInt("pyramid_surface_height",h)
                    .putLong("pyramid_surface_changed_at",System.currentTimeMillis()).apply();
        }
        @Override public void onDrawFrame(javax.microedition.khronos.opengles.GL10 gl){
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT|GLES20.GL_DEPTH_BUFFER_BIT); if(program==0)return;
            float t=(System.nanoTime()-startNanos)/1_000_000_000f; long now=SystemClock.elapsedRealtime(); updateColor(now);
            frameCount++; fpsWindowFrames++; lastFrameElapsedMs=now; if(now-fpsWindowStartMs>=1000L){ fps=fpsWindowFrames*1000f/Math.max(1L,now-fpsWindowStartMs); fpsWindowFrames=0; fpsWindowStartMs=now; prefs.edit().putFloat("pyramid_last_fps",fps).putLong("pyramid_frame_count",frameCount).putLong("pyramid_last_frame_wall_at",System.currentTimeMillis()).apply(); }
            wireframe=prefs.getBoolean("pyramid_wireframe_mode",false);

            boolean idle=(state==VisualState.IDLE || state==VisualState.READY || state==VisualState.PAUSED);
            float xDeg=idle?(-9.0f+2.2f*(float)Math.sin(t*.15f)):(-8.0f+1.5f*(float)Math.sin(t*.22f));
            float yDeg=idle?(-18.0f+4.0f*(float)Math.sin(t*.12f)):(-17.0f+2.5f*(float)Math.sin(t*.19f));
            // Shape stays rigid. State animation lives in light/color, not geometry inflation.
            float pulse=0f;
            float brightness=idle?.78f:(state==VisualState.THINKING?1.14f:(state==VisualState.SPEAKING?1.10f:1.02f));

            Matrix.setIdentityM(model,0); Matrix.translateM(model,0,0f,.12f,0f);
            // Fixed uniform scale. Geometry never stretches with state or audio.
            Matrix.scaleM(model,0,.90f,.90f,.90f);
            Matrix.rotateM(model,0,xDeg,1f,0f,0f); Matrix.rotateM(model,0,yDeg,0f,1f,0f); // intentionally no Z-axis spin
            Matrix.multiplyMM(mv,0,view,0,model,0); Matrix.multiplyMM(mvp,0,projection,0,mv,0); System.arraycopy(model,0,normalMatrix,0,16);

            GLES20.glUseProgram(program);
            int ap=GLES20.glGetAttribLocation(program,"aPosition"), an=GLES20.glGetAttribLocation(program,"aNormal"), au=GLES20.glGetAttribLocation(program,"aU"), av=GLES20.glGetAttribLocation(program,"aV"), al=GLES20.glGetAttribLocation(program,"aLayer");
            vertices.position(0); GLES20.glVertexAttribPointer(ap,3,GLES20.GL_FLOAT,false,FPV*4,vertices); GLES20.glEnableVertexAttribArray(ap);
            vertices.position(3); GLES20.glVertexAttribPointer(an,3,GLES20.GL_FLOAT,false,FPV*4,vertices); GLES20.glEnableVertexAttribArray(an);
            vertices.position(6); GLES20.glVertexAttribPointer(au,1,GLES20.GL_FLOAT,false,FPV*4,vertices); GLES20.glEnableVertexAttribArray(au);
            vertices.position(7); GLES20.glVertexAttribPointer(av,1,GLES20.GL_FLOAT,false,FPV*4,vertices); GLES20.glEnableVertexAttribArray(av);
            vertices.position(8); GLES20.glVertexAttribPointer(al,1,GLES20.GL_FLOAT,false,FPV*4,vertices); GLES20.glEnableVertexAttribArray(al);
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program,"uMvp"),1,false,mvp,0); GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program,"uModel"),1,false,model,0); GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program,"uNormalMatrix"),1,false,normalMatrix,0);
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program,"uTime"),t); GLES20.glUniform1f(GLES20.glGetUniformLocation(program,"uBrightness"),brightness); GLES20.glUniform1f(GLES20.glGetUniformLocation(program,"uWireframe"),wireframe?1f:0f);
            GLES20.glUniform3f(GLES20.glGetUniformLocation(program,"uStateColor"),renderedColor[0],renderedColor[1],renderedColor[2]);
            indices.position(0); GLES20.glDrawElements(GLES20.GL_TRIANGLES,indexCount,GLES20.GL_UNSIGNED_SHORT,indices);
            GLES20.glDisableVertexAttribArray(ap); GLES20.glDisableVertexAttribArray(an); GLES20.glDisableVertexAttribArray(au); GLES20.glDisableVertexAttribArray(av); GLES20.glDisableVertexAttribArray(al);
        }

        private void buildMesh(){
            // Code388 R105 owner-approved Lumi geometry: a compact upright pyramid floats above
            // the larger inverted pyramid. Both use dark metallic ribs around luminous glass
            // panels. This replaces the older single inverted-pyramid/core approximation.
            final float[][] mainBase={
                    {-1.16f,.62f, 1.16f}, {1.16f,.62f, 1.16f},
                    { 1.16f,.62f,-1.16f}, {-1.16f,.62f,-1.16f}
            };
            final float[] mainTip={0f,-1.42f,0f};
            final int triCount=82;
            float[] data=new float[triCount*3*FPV]; short[] ix=new short[triCount*3]; int[] c={0,0,0};

            // Large inverted lower pyramid.
            for(int f=0;f<4;f++){
                float[] a=mainBase[f], b=mainBase[(f+1)%4];
                float[] pa=blend(a,b,mainTip,.72f,.08f,.20f);
                float[] pb=blend(a,b,mainTip,.08f,.72f,.20f);
                float[] pt=blend(a,b,mainTip,.115f,.115f,.77f);
                emitTri(data,ix,c,pa,pb,pt,1f);
                emitQuad(data,ix,c,a,b,pb,pa,0f);
                emitQuad(data,ix,c,a,pa,pt,mainTip,0f);
                emitQuad(data,ix,c,pb,b,mainTip,pt,0f);
            }

            // Layered metallic crown separating the two pyramid bodies.
            float[][] upperOuter={{-1.22f,.80f,1.22f},{1.22f,.80f,1.22f},{1.22f,.80f,-1.22f},{-1.22f,.80f,-1.22f}};
            float[][] innerUpper={{-.70f,.96f,.70f},{.70f,.96f,.70f},{.70f,.96f,-.70f},{-.70f,.96f,-.70f}};
            float[][] innerLower={{-.61f,.72f,.61f},{.61f,.72f,.61f},{.61f,.72f,-.61f},{-.61f,.72f,-.61f}};
            for(int i=0;i<4;i++){
                int n=(i+1)%4;
                emitQuad(data,ix,c,mainBase[i],mainBase[n],upperOuter[n],upperOuter[i],2f);
                emitQuad(data,ix,c,upperOuter[i],upperOuter[n],innerUpper[n],innerUpper[i],2f);
                emitQuad(data,ix,c,innerUpper[i],innerUpper[n],innerLower[n],innerLower[i],2f);
            }
            emitTri(data,ix,c,innerLower[0],innerLower[1],innerLower[2],3f);
            emitTri(data,ix,c,innerLower[0],innerLower[2],innerLower[3],3f);

            // Approved upright top pyramid. The small body is intentionally distinct from the
            // lower inverted body and floats just above the crown, matching the approved avatar.
            float[][] topBase={{-.54f,1.10f,.54f},{.54f,1.10f,.54f},{.54f,1.10f,-.54f},{-.54f,1.10f,-.54f}};
            float[] topTip={0f,2.04f,0f};
            for(int f=0;f<4;f++){
                float[] a=topBase[f], b=topBase[(f+1)%4];
                float[] pa=blend(a,b,topTip,.70f,.09f,.21f);
                float[] pb=blend(a,b,topTip,.09f,.70f,.21f);
                float[] pt=blend(a,b,topTip,.10f,.10f,.80f);
                emitTri(data,ix,c,pa,pb,pt,4f);
                emitQuad(data,ix,c,a,b,pb,pa,0f);
                emitQuad(data,ix,c,a,pa,pt,topTip,0f);
                emitQuad(data,ix,c,pb,b,topTip,pt,0f);
            }

            if(c[1]!=triCount*3) throw new IllegalStateException("R105 layered pyramid triangle contract mismatch: "+(c[1]/3));
            vertices=ByteBuffer.allocateDirect(data.length*4).order(ByteOrder.nativeOrder()).asFloatBuffer(); vertices.put(data).position(0);
            indices=ByteBuffer.allocateDirect(ix.length*2).order(ByteOrder.nativeOrder()).asShortBuffer(); indices.put(ix).position(0); indexCount=ix.length;
        }
        private static float[] blend(float[] a,float[] b,float[] t,float wa,float wb,float wt){
            return new float[]{a[0]*wa+b[0]*wb+t[0]*wt,a[1]*wa+b[1]*wb+t[1]*wt,a[2]*wa+b[2]*wb+t[2]*wt};
        }
        private static void emitQuad(float[] data,short[] ix,int[] c,float[] a,float[] b,float[] d,float[] e,float layer){
            emitTri(data,ix,c,a,b,d,layer); emitTri(data,ix,c,a,d,e,layer);
        }
        private static void emitTri(float[] data,short[] ix,int[] c,float[] a,float[] b,float[] d,float layer){
            float[] n=normal(a,b,d); float[][] pts={a,b,d}; float[][] uv={{0f,0f},{1f,0f},{0f,1f}};
            for(int j=0;j<3;j++){
                float[] q=pts[j]; data[c[0]++]=q[0];data[c[0]++]=q[1];data[c[0]++]=q[2];
                data[c[0]++]=n[0];data[c[0]++]=n[1];data[c[0]++]=n[2];
                data[c[0]++]=uv[j][0];data[c[0]++]=uv[j][1];data[c[0]++]=layer; ix[c[1]++]=(short)c[2]++;
            }
        }
        private static float[] normal(float[] a,float[] b,float[] c){ float ux=b[0]-a[0],uy=b[1]-a[1],uz=b[2]-a[2], vx=c[0]-a[0],vy=c[1]-a[1],vz=c[2]-a[2]; float nx=uy*vz-uz*vy,ny=uz*vx-ux*vz,nz=ux*vy-uy*vx; float d=(float)Math.sqrt(nx*nx+ny*ny+nz*nz); if(d<1e-6f)d=1f; return new float[]{nx/d,ny/d,nz/d}; }
        private static int buildProgram(String vs,String fs){ int v=compile(GLES20.GL_VERTEX_SHADER,vs),f=compile(GLES20.GL_FRAGMENT_SHADER,fs); if(v==0||f==0)return 0; int p=GLES20.glCreateProgram(); GLES20.glAttachShader(p,v);GLES20.glAttachShader(p,f);GLES20.glLinkProgram(p);int[]ok=new int[1];GLES20.glGetProgramiv(p,GLES20.GL_LINK_STATUS,ok,0);if(ok[0]==0){GLES20.glDeleteProgram(p);p=0;}GLES20.glDeleteShader(v);GLES20.glDeleteShader(f);return p; }
        private static int compile(int type,String src){ int s=GLES20.glCreateShader(type);GLES20.glShaderSource(s,src);GLES20.glCompileShader(s);int[]ok=new int[1];GLES20.glGetShaderiv(s,GLES20.GL_COMPILE_STATUS,ok,0);if(ok[0]==0){GLES20.glDeleteShader(s);return 0;}return s; }

        private static final String VS=
                "uniform mat4 uMvp; uniform mat4 uModel; uniform mat4 uNormalMatrix; uniform float uTime;"
              + "attribute vec3 aPosition; attribute vec3 aNormal; attribute float aU; attribute float aV; attribute float aLayer;"
              + "varying vec3 vNormal; varying vec3 vWorld; varying float vU; varying float vV; varying float vLayer; varying float vPulse;"
              + "void main(){ vec3 pos=aPosition; vec4 world=uModel*vec4(pos,1.0); vWorld=world.xyz; vNormal=normalize((uNormalMatrix*vec4(aNormal,0.0)).xyz); vU=aU;vV=aV;vLayer=aLayer;vPulse=.5+.5*sin(uTime*1.1+aLayer);gl_Position=uMvp*vec4(pos,1.0);}";
        private static final String FS=
                "precision mediump float; uniform vec3 uStateColor; uniform float uBrightness; uniform float uWireframe; uniform float uTime;"
              + "varying vec3 vNormal; varying vec3 vWorld; varying float vU; varying float vV; varying float vLayer; varying float vPulse;"
              + "void main(){ vec3 n=normalize(vNormal);vec3 light=normalize(vec3(-.32,.68,.66));float diff=.16+.84*abs(dot(n,light));vec3 viewDir=normalize(vec3(0.0,.62,8.9)-vWorld);float rim=pow(1.0-abs(dot(n,viewDir)),2.6);"
              + "float w=max(0.0,1.0-vU-vV);float edgeDist=min(vU,min(vV,w));float edge=1.0-smoothstep(.018,.075,edgeDist);"
              + "float sideGlass=step(.5,vLayer)*(1.0-step(1.5,vLayer));float topGlass=step(2.5,vLayer)*(1.0-step(3.5,vLayer));float core=step(3.5,vLayer);float glass=max(sideGlass,max(topGlass,core));"
              + "float cloud=.50+.50*sin(vU*5.1+sin(vV*3.7+uTime*.18)*1.25+uTime*.12);"
              + "float cloud2=.50+.50*sin(vV*4.3+sin(vU*3.2-uTime*.14)*1.10-uTime*.09);"
              + "float energy=pow(clamp(cloud*cloud2,0.0,1.0),2.2)*glass*(.18+.08*sin(uTime*.55));"
              + "vec3 metalLo=vec3(.012,.016,.026);vec3 metalHi=vec3(.24,.27,.34);vec3 metalColor=mix(metalLo,metalHi,.22+.58*diff);"
              + "vec3 glassColor=mix(vec3(.004,.006,.014),uStateColor,.30+.20*diff);vec3 color=mix(metalColor,glassColor,glass);"
              + "color+=uStateColor*edge*(.18+.72*glass);color+=uStateColor*rim*(.16+.70*glass);color+=uStateColor*energy*1.65;color+=uStateColor*core*(.55+.28*sin(uTime*1.05));"
              + "float shimmer=.975+.025*sin(uTime*.48+vU*7.0+vV*4.0);color*=uBrightness*shimmer;"
              + "float alpha=mix(.985,.76,glass);alpha=max(alpha,core*.94);alpha=mix(alpha,.035+edge*.94,uWireframe);gl_FragColor=vec4(color,clamp(alpha,0.0,1.0));}";
    }
}
