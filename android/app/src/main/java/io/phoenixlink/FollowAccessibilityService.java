package io.phoenixlink;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityButtonController;
import android.app.KeyguardManager;
import android.content.Intent;
import android.graphics.*;
import android.hardware.HardwareBuffer;
import android.os.*;
import android.view.accessibility.*;
import java.nio.ByteBuffer;
import java.util.concurrent.*;

public final class FollowAccessibilityService extends AccessibilityService {
    private static volatile FollowAccessibilityService connected;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService pixels=Executors.newSingleThreadExecutor();
    private CaptureService owner;
    private LinkConfig config;
    private boolean capturePending;
    private long nextCapture;
    private int requestSerial;
    private final AccessibilityButtonController.AccessibilityButtonCallback shortcut=new AccessibilityButtonController.AccessibilityButtonCallback() {
        @Override public void onClicked(AccessibilityButtonController controller) {showControls();}
    };
    static FollowAccessibilityService connected() {return connected;}

    @Override protected void onServiceConnected() {
        connected=this;
        getAccessibilityButtonController().registerAccessibilityButtonCallback(shortcut,main);
    }
    void showControls() {
        if(owner!=null)owner.showControls();
        else startActivity(new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP));
    }
    void start(CaptureService session,LinkConfig settings) {
        if(owner!=null)throw new IllegalStateException("Follow capture is already active.");
        owner=session;config=settings;nextCapture=0;capturePending=false;requestSerial++;main.post(poll);
    }
    void stop(CaptureService session) {
        if(owner!=session)return;
        owner=null;config=null;requestSerial++;capturePending=false;main.removeCallbacks(poll);
    }
    private final Runnable poll=new Runnable() {
        @Override public void run() {
            if(owner==null)return;
            try {tick();} catch(RuntimeException e) {owner.followBlocked("Waiting for an accessible app window",true);}
            if(owner!=null)main.postDelayed(this,100);
        }
    };
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if(owner==null)return;
        refreshTarget();
        if(event.getEventType()==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && event.getWindowId()==owner.followSource.get().window)
            owner.followBlocked("Window changed; waiting for a fresh frame",false);
    }
    private void refreshTarget() {
        CaptureService session=owner;if(session==null)return;
        if(getSystemService(KeyguardManager.class).isKeyguardLocked() || !getSystemService(PowerManager.class).isInteractive()) {
            session.followBlocked("Paused while device is locked or asleep",true);return;
        }
        AccessibilityWindowInfo selected=null;
        for(AccessibilityWindowInfo window:getWindows()) {
            if(window.getType()==AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY)continue;
            if((window.isActive() || window.isFocused()) && (selected==null || window.getLayer()>selected.getLayer()))selected=window;
        }
        if(selected==null || selected.getType()!=AccessibilityWindowInfo.TYPE_APPLICATION) {
            session.followBlocked("Waiting for an app window",true);return;
        }
        AccessibilityNodeInfo root=selected.getRoot();
        String name=root==null || root.getPackageName()==null ? "" : root.getPackageName().toString();
        if(root!=null)root.recycle();
        if(name.isEmpty() || name.equals(getPackageName()) || name.equals("android") || name.equals("com.android.systemui") || name.endsWith(".permissioncontroller")) {
            session.followBlocked("Paused for app settings or system permission UI",true);return;
        }
        Rect bounds=new Rect();selected.getBoundsInScreen(bounds);
        if(bounds.width()<1 || bounds.height()<1) {session.followBlocked("Waiting for window bounds",true);return;}
        session.followTarget(selected.getId(),bounds);
    }
    private void tick() {
        refreshTarget();CaptureService session=owner;if(session==null)return;
        FollowSourceState.Snapshot target=session.followSource.get();long now=SystemClock.uptimeMillis();
        if(target.window<0 || capturePending || now<nextCapture || !session.wantsFollowFrame())return;
        capturePending=true;nextCapture=now+Math.max(250,1000/config.fps);
        final int serial=requestSerial;
        final int edge=config.maxEdge;
        takeScreenshotOfWindow(target.window,getMainExecutor(),new TakeScreenshotCallback() {
            @Override public void onSuccess(ScreenshotResult result) {
                HardwareBuffer buffer=result.getHardwareBuffer();
                if(owner!=session || serial!=requestSerial) {buffer.close();return;}
                try {pixels.execute(() -> copy(session,target,serial,edge,result,buffer));}
                catch(RejectedExecutionException e) {buffer.close();capturePending=false;}
            }
            @Override public void onFailure(int error) {
                if(owner!=session || serial!=requestSerial)return;capturePending=false;
                // An old window's failure must not block the newly selected window.
                if(session.followSource.get().generation!=target.generation)return;
                if(error==ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {nextCapture=SystemClock.uptimeMillis()+1000;return;}
                session.followBlocked(error==ERROR_TAKE_SCREENSHOT_SECURE_WINDOW ? "Protected window: enhancement paused" : "Window capture unavailable; retrying",false);
                nextCapture=SystemClock.uptimeMillis()+1000;
            }
        });
    }
    private void copy(CaptureService session,FollowSourceState.Snapshot target,int serial,int edge,ScreenshotResult result,HardwareBuffer buffer) {
        Bitmap hardware=null,software=null,scaled=null;
        try {
            hardware=Bitmap.wrapHardwareBuffer(buffer,result.getColorSpace());
            if(hardware==null)throw new IllegalStateException("No window pixels");
            // A software Canvas cannot draw a hardware-backed screenshot bitmap.
            software=hardware.copy(Bitmap.Config.ARGB_8888,false);
            if(software==null)throw new IllegalStateException("No readable window pixels");
            int[] size=FrameGeometry.scaled(hardware.getWidth(),hardware.getHeight(),edge);
            ByteBuffer rgba=ByteBuffer.allocate(LanClient.pixelBytes(size[0],size[1]));
            // Drawing into a default ARGB_8888 bitmap converts wide-gamut input to SDR sRGB.
            scaled=Bitmap.createBitmap(size[0],size[1],Bitmap.Config.ARGB_8888);
            new Canvas(scaled).drawBitmap(software,null,new Rect(0,0,size[0],size[1]),new Paint(Paint.FILTER_BITMAP_FLAG));
            scaled.copyPixelsToBuffer(rgba);
            main.post(() -> {
                if(owner!=session || serial!=requestSerial)return;
                capturePending=false;session.followFrame(size[0],size[1],rgba.array(),target);
            });
        } catch(Exception e) {
            main.post(() -> {
                if(owner==session && serial==requestSerial) {capturePending=false;session.followBlocked("Window pixels unavailable; retrying",false);nextCapture=SystemClock.uptimeMillis()+1000;}
            });
        } finally {if(scaled!=null)scaled.recycle();if(software!=null)software.recycle();if(hardware!=null)hardware.recycle();buffer.close();}
    }
    private void disconnect() {
        CaptureService session=owner;if(session!=null) {stop(session);session.followPermissionLost();}
        if(connected==this)connected=null;
    }
    @Override public void onInterrupt() {
        CaptureService session=owner;if(session!=null){stop(session);session.followPermissionLost();}
    }
    @Override public boolean onUnbind(Intent intent) {disconnect();return super.onUnbind(intent);}
    @Override public void onDestroy() {
        getAccessibilityButtonController().unregisterAccessibilityButtonCallback(shortcut);
        disconnect();pixels.shutdownNow();super.onDestroy();
    }
}
