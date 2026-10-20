package io.phoenixlink;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.*;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.*;
import android.os.*;
import android.view.WindowManager;
import org.json.JSONObject;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.*;

public final class CaptureService extends Service {
    static final String START="io.phoenixlink.START",STOP="io.phoenixlink.STOP";
    static volatile boolean running;
    static final AtomicBoolean busy=new AtomicBoolean();
    static volatile String status="Disconnected";
    static final AtomicReference<LinkConfig.Tuning> tuning=new AtomicReference<>(LinkConfig.Tuning.defaults());
    private final Handler main=new Handler(Looper.getMainLooper());
    private final AtomicBoolean closing=new AtomicBoolean();
    private final AtomicReference<Frame> pending=new AtomicReference<>();
    private final AtomicReference<Rendered> pendingRender=new AtomicReference<>();
    private final AtomicBoolean renderPosted=new AtomicBoolean();
    private HandlerThread captureThread; private Handler captureHandler;
    private Thread networkThread;
    private volatile LanClient activeClient;
    private MediaProjection projection; private VirtualDisplay display; private ImageReader reader;
    private OverlayController overlay;
    private LinkConfig config; private long lastCaptured;
    private boolean ownsBusy,networkFinished;
    private MediaProjection.Callback projectionCallback;
    final FollowSourceState followSource=new FollowSourceState();
    private FollowAccessibilityService follower;

    final class LocalBinder extends Binder { CaptureService service() { return CaptureService.this; } }
    @Override public IBinder onBind(Intent intent) { return new LocalBinder(); }
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        if(intent==null || STOP.equals(intent.getAction())) { finish("Stopped"); return START_NOT_STICKY; }
        if(running || closing.get()) return START_NOT_STICKY;
        if(!busy.compareAndSet(false,true)) { stopSelf(); return START_NOT_STICKY; }
        ownsBusy=true;
        try {
            config=LinkConfig.from(new JSONObject(intent.getStringExtra("config"))); config.validate();
            startNotification(); // Must precede getMediaProjection on Android 14+.
            if(config.captureMode.equals("follow")) {
                follower=FollowAccessibilityService.connected();
                if(follower==null)throw new SecurityException("Enable Phoenix DLSS5 follow access in Android Accessibility settings first.");
                running=true;status="Waiting for an active app";
                overlay=new OverlayController(follower,config,() -> finish("Stopped"),value -> {tuning.set(value);status="Settings queued";},true);
                follower.start(this,config);
                networkThread=new Thread(this::stream,"PhoenixLAN");networkThread.start();
                return START_NOT_STICKY;
            }
            Intent consent=intent.getParcelableExtra("consent",Intent.class);
            if(consent==null) throw new SecurityException("Screen capture permission is missing.");
            running=true; status="Waiting for capture";
            captureThread=new HandlerThread("PhoenixCapture"); captureThread.start(); captureHandler=new Handler(captureThread.getLooper());
            projection=getSystemService(MediaProjectionManager.class).getMediaProjection(Activity.RESULT_OK,consent);
            projectionCallback=new MediaProjection.Callback() {
                @Override public void onStop() { main.post(() -> finish("Capture stopped")); }
                @Override public void onCapturedContentResize(int w,int h) { if(!closing.get() && w>0 && h>0) resize(w,h); }
                @Override public void onCapturedContentVisibilityChanged(boolean visible) { main.post(() -> { if(overlay!=null) overlay.sourceVisible(visible); }); }
            };
            projection.registerCallback(projectionCallback,captureHandler);
            overlay=new OverlayController(this,config,() -> finish("Stopped"),value -> { tuning.set(value); status="Settings queued"; });
            android.graphics.Rect bounds=getSystemService(WindowManager.class).getMaximumWindowMetrics().getBounds();
            captureHandler.post(() -> {
                try {
                    if(closing.get()) return;
                    int[] size=FrameGeometry.scaled(bounds.width(),bounds.height(),config.maxEdge); reader=createReader(size[0],size[1]);
                    display=projection.createVirtualDisplay("Phoenix Link",size[0],size[1],getResources().getConfiguration().densityDpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,reader.getSurface(),null,captureHandler);
                } catch(Exception e) { main.post(() -> finish("Capture could not start: "+safeMessage(e))); }
            });
            networkThread=new Thread(this::stream,"PhoenixLAN"); networkThread.start();
        } catch(Exception e) { finish("Could not start: "+safeMessage(e)); }
        return START_NOT_STICKY;
    }
    private void startNotification() {
        NotificationManager manager=getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("capture","Phoenix DLSS5 session",NotificationManager.IMPORTANCE_LOW));
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,CaptureService.class).setAction(STOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        PendingIntent open=PendingIntent.getActivity(this,2,new Intent(this,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification note=new Notification.Builder(this,"capture").setSmallIcon(android.R.drawable.ic_menu_view).setContentTitle("Phoenix Link")
            .setContentText("Screen sharing is active").setOngoing(true).setContentIntent(open).addAction(new Notification.Action.Builder(null,"Stop",stop).build()).build();
        startForeground(1,note,config.captureMode.equals("follow")?ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE:ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
    }
    void followTarget(int window,Rect bounds) {
        long before=followSource.get().generation;
        if(followSource.select(window,bounds.left,bounds.top,bounds.right,bounds.bottom).generation!=before)clearFollowPresentation("Switching to active app");
    }
    void followBlocked(String message,boolean clear) {
        if(followSource.get().window>=0)followSource.block(clear);
        clearFollowPresentation(message);
    }
    private void clearFollowPresentation(String message) {
        pending.set(null);pendingRender.set(null);if(overlay!=null){overlay.sourceVisible(false);overlay.status(message);}status=message;
    }
    void showControls() {if(overlay!=null)overlay.showControls();}
    boolean wantsFollowFrame() {return running && !closing.get() && pending.get()==null;}
    void followFrame(int w,int h,byte[] pixels,FollowSourceState.Snapshot source) {
        if(closing.get() || !followSource.permit(source.generation))return;
        pending.set(new Frame(w,h,pixels,source.generation,new Rect(source.left,source.top,source.right,source.bottom)));
    }
    void followPermissionLost() {finish("Follow permission disconnected; session stopped");}
    private ImageReader createReader(int w,int h) {
        ImageReader result=ImageReader.newInstance(w,h,PixelFormat.RGBA_8888,2);
        result.setOnImageAvailableListener(r -> {
            if(closing.get() || r!=reader) return;
            try(Image image=r.acquireLatestImage()) {
                if(image==null || closing.get()) return;
                long now=System.nanoTime(); if(now-lastCaptured<1000000000L/config.fps) return; lastCaptured=now;
                int width=image.getWidth(),height=image.getHeight(); byte[] pixels=new byte[LanClient.pixelBytes(width,height)];
                Image.Plane plane=image.getPlanes()[0]; ByteBuffer buffer=plane.getBuffer(); int stride=plane.getRowStride(),step=plane.getPixelStride();
                if(step==4) for(int y=0;y<height;++y) { buffer.position(y*stride); buffer.get(pixels,y*width*4,width*4); }
                else for(int y=0;y<height;++y) for(int x=0;x<width;++x) for(int c=0;c<4;++c) pixels[(y*width+x)*4+c]=buffer.get(y*stride+x*step+c);
                // Sampling is bounded before network submission; accepted frames are never queued or dropped.
                pending.set(new Frame(width,height,pixels));
            } catch(Exception e) { if(!closing.get()) main.post(() -> finish("Capture failed: "+safeMessage(e))); }
        },captureHandler); return result;
    }
    private void resize(int w,int h) {
        if(display==null || closing.get()) return;
        int[] size=FrameGeometry.scaled(w,h,config.maxEdge); if(reader!=null && reader.getWidth()==size[0] && reader.getHeight()==size[1]) return;
        try {
            pending.set(null); ImageReader previous=reader; reader=createReader(size[0],size[1]);
            display.resize(size[0],size[1],getResources().getConfiguration().densityDpi); display.setSurface(reader.getSurface());
            if(previous!=null) { previous.setOnImageAvailableListener(null,null); previous.close(); }
        } catch(Exception e) { main.post(() -> finish("Capture resize failed: "+safeMessage(e))); }
    }
    private void stream() {
        LanClient client=null; int width=0,height=0; long applied=-1,lastActivity=System.nanoTime(),frames=0,lastSource=-1;
        try {
            while(!closing.get()) {
                Frame frame=pending.getAndSet(null); LinkConfig.Tuning desired=tuning.get();
                if(frame==null) {
                    if(client!=null && desired.revision!=applied) { client.update(desired); applied=desired.revision; status="Settings applied"; }
                    if(client!=null && System.nanoTime()-lastActivity>10000000000L) { client.ping(); lastActivity=System.nanoTime(); }
                    Thread.sleep(10); continue;
                }
                if(config.captureMode.equals("follow") && !followSource.accepts(frame.generation))continue;
                if(client==null || width!=frame.w || height!=frame.h) {
                    if(client!=null) client.close(); activeClient=null;
                    width=frame.w; height=frame.h; status="Connecting ("+config.codec+")";
                    client=new LanClient(config,width,height,desired,value -> { activeClient=value; if(closing.get()) value.abort(); }); applied=desired.revision;
                }
                if(desired.revision!=applied) { client.update(desired); applied=desired.revision; }
                long started=System.nanoTime(); byte[] output=client.frame(frame.rgba,frames==0 || lastSource!=frame.generation);lastSource=frame.generation; lastActivity=System.nanoTime(); ++frames;
                String message=String.format(java.util.Locale.ROOT,"%s  %dx%d  #%d  %.0f ms  %s",config.codec,width,height,frames,(lastActivity-started)/1000000.0,desired.enabled?"Phoenix":"Original");
                if(!config.captureMode.equals("follow") || followSource.accepts(frame.generation)) {
                    status=message; postFrame(new Rendered(width,height,output,message,frame.generation,frame.bounds));
                }
            }
        } catch(Exception e) { if(!closing.get()) { String message="Session ended: "+safeMessage(e); main.post(() -> finish(message)); } }
        finally {
            if(client!=null) client.close(); activeClient=null;
            main.post(() -> { networkFinished=true; if(closing.get()) busy.set(false); else finish("Stopped"); });
        }
    }
    private void postFrame(Rendered frame) {
        pendingRender.set(frame);
        if(renderPosted.compareAndSet(false,true)) main.post(() -> {
            renderPosted.set(false); Rendered latest=pendingRender.getAndSet(null);
            if(!closing.get() && overlay!=null && latest!=null && (!config.captureMode.equals("follow") || followSource.accepts(latest.generation))) {
                if(config.captureMode.equals("follow"))overlay.sourceVisible(true);
                overlay.frame(latest.w,latest.h,latest.rgba,latest.message,latest.bounds);
            }
        });
    }
    private static String safeMessage(Exception e) {
        if(e instanceof javax.net.ssl.SSLException) return "TLS verification failed. Check the certificate fingerprint.";
        String message=e.getMessage(); return message==null ? "Operation failed" : message.substring(0,Math.min(200,message.length()));
    }
    private void finish(String message) {
        if(!closing.compareAndSet(false,true)) return;
        if(!ownsBusy) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return; }
        running=false; status=message; pending.set(null); pendingRender.set(null);
        if(follower!=null) {follower.stop(this);follower=null;}followSource.block(true);
        LanClient client=activeClient; if(client!=null) client.abort();
        if(networkThread!=null) networkThread.interrupt();
        if(networkThread==null || networkFinished) busy.set(false);
        if(overlay!=null) { overlay.close(); overlay=null; }
        if(captureHandler!=null) captureHandler.post(() -> {
            if(display!=null) { display.release(); display=null; }
            if(reader!=null) { reader.close(); reader=null; }
            if(projection!=null) { projection.unregisterCallback(projectionCallback); projection.stop(); projection=null; }
            captureThread.quitSafely();
        });
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
    }
    @Override public void onDestroy() { finish("Stopped"); super.onDestroy(); }
    private static final class Frame {
        final int w,h;final byte[] rgba;final long generation;final Rect bounds;
        Frame(int w,int h,byte[] pixels) {this(w,h,pixels,0,null);}
        Frame(int w,int h,byte[] pixels,long generation,Rect bounds) {this.w=w;this.h=h;rgba=pixels;this.generation=generation;this.bounds=bounds;}
    }
    private static final class Rendered {
        final int w,h;final byte[] rgba;final String message;final long generation;final Rect bounds;
        Rendered(int w,int h,byte[] rgba,String message,long generation,Rect bounds) {this.w=w;this.h=h;this.rgba=rgba;this.message=message;this.generation=generation;this.bounds=bounds;}
    }
}
