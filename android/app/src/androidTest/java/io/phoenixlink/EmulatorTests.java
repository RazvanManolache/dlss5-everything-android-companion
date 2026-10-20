package io.phoenixlink;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

@SuppressWarnings("deprecation")
public final class EmulatorTests extends Instrumentation {
    private Bundle arguments;
    @Override public void onCreate(Bundle args) { super.onCreate(args); arguments=args; start(); }
    @Override public void onStart() {
        Bundle result=new Bundle();
        try {
            String name=arguments.getString("case", "testRawRoundTripAndControls");
            getClass().getMethod(name).invoke(this);
            result.putString("stream", name+": PASS\n"); result.putBoolean("passed",true);
            finish(Activity.RESULT_OK,result);
        } catch(Throwable e) {
            Throwable cause=e instanceof java.lang.reflect.InvocationTargetException ? e.getCause() : e;
            result.putString("stream",android.util.Log.getStackTraceString(cause)); result.putBoolean("passed",false);
            finish(Activity.RESULT_CANCELED,result);
        }
    }
    private Instrumentation getInstrumentation() { return this; }
    private UiAutomation automation() {return getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);}
    private static void fail(String message) { throw new AssertionError(message); }
    private static void assertTrue(boolean value) { assertTrue("Expected true",value); }
    private static void assertTrue(String message,boolean value) { if(!value)fail(message); }
    private static void assertFalse(String message,boolean value) { assertTrue(message,!value); }
    private static void assertNotNull(Object value) { assertTrue("Expected non-null",value!=null); }
    private static void assertEquals(int expected,int actual) { assertTrue("Expected "+expected+", got "+actual,expected==actual); }
    private LinkConfig config() throws Exception {
        String text = new String(java.nio.file.Files.readAllBytes(new java.io.File(getInstrumentation().getTargetContext().getFilesDir(), "emulator-pairing.json").toPath()), StandardCharsets.UTF_8);
        return LinkConfig.from(new JSONObject(text));
    }
    private byte[] pattern(int w, int h) {
        byte[] p = new byte[w*h*4];
        for(int y=0;y<h;y++) for(int x=0;x<w;x++) { int i=4*(y*w+x); p[i]=(byte)(x*255/w);p[i+1]=(byte)(y*255/h);p[i+2]=80;p[i+3]=(byte)255; }
        return p;
    }
    public void testLocalPyrowave() throws Exception {
        for(int[] size:new int[][]{{257,255},{1280,720},{1220,2712}}) {
            byte[] input=pattern(size[0],size[1]);
            try(NativeCodec codec=new NativeCodec(size[0],size[1],100,15)) {
                for(int repeat=0;repeat<3;repeat++) {
                long start=System.nanoTime();byte[] encoded=codec.encode(input);byte[] decoded=codec.decode(encoded,input.length);
                long error=0;for(int i=0;i<input.length;i++)if(i%4!=3)error+=Math.abs((input[i]&255)-(decoded[i]&255));
                double mae=error/(size[0]*(double)size[1]*3);assertTrue("Codec color error: "+mae,mae<12);
                Bundle stats=new Bundle();stats.putInt("width",size[0]);stats.putInt("height",size[1]);stats.putInt("repeat",repeat);stats.putInt("encoded_bytes",encoded.length);stats.putDouble("mae",mae);stats.putDouble("roundtrip_ms",(System.nanoTime()-start)/1000000.);sendStatus(0,stats);
                }
                try {codec.decode(new byte[]{1,0,0,0,4,0,0,0,0,0,0,0},input.length);fail("Malformed codec packet accepted");}catch(java.io.IOException expected){}
            }
        }
    }
    public void testPhonePyrowaveAndSave() throws Exception {
        LinkConfig c=config();c.validate();c.codec="pyrowave";c.fps=15;c.bitrateMbps=100;
        for(int[] size:new int[][]{{257,255},{1280,720}}) {
            byte[] input=pattern(size[0],size[1]);
            try(LanClient client=new LanClient(c,size[0],size[1],new LinkConfig.Tuning(false,1,1,1,-1,false,0),v -> {})) {
                for(int repeat=0;repeat<3;repeat++) {
                    long started=System.nanoTime();byte[] output=client.frame(input,repeat==0);long error=0;
                    for(int i=0;i<input.length;i++)if(i%4!=3)error+=Math.abs((input[i]&255)-(output[i]&255));
                    double mae=error/(size[0]*(double)size[1]*3);assertTrue("Two-way codec error: "+mae,mae<12);
                    Bundle stats=new Bundle();stats.putInt("width",size[0]);stats.putInt("height",size[1]);stats.putInt("repeat",repeat);stats.putDouble("mae",mae);stats.putDouble("network_roundtrip_ms",(System.nanoTime()-started)/1000000.);sendStatus(0,stats);
                }
            }
        }
        android.graphics.Bitmap photo=android.graphics.BitmapFactory.decodeFile(new java.io.File(getTargetContext().getFilesDir(),"pyrowave-face.jpg").getPath());assertNotNull(photo);
        android.graphics.Bitmap face=android.graphics.Bitmap.createBitmap(1220,2712,android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas=new android.graphics.Canvas(face);canvas.drawColor(android.graphics.Color.rgb(23,26,29));
        canvas.drawBitmap(photo,new android.graphics.Rect(720,160,1340,780),new android.graphics.Rect(40,600,1180,1740),new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG));
        java.nio.ByteBuffer pixels=java.nio.ByteBuffer.allocate(1220*2712*4);face.copyPixelsToBuffer(pixels);photo.recycle();face.recycle();
        try(LanClient client=new LanClient(c,1220,2712,new LinkConfig.Tuning(false,1,1,1,-1,false,0),v -> {})) {
            byte[] original=client.frame(pixels.array(),true);client.update(LinkConfig.Tuning.defaults());
            long started=System.nanoTime();byte[] processed=client.frame(pixels.array(),true);
            double elapsedMs=(System.nanoTime()-started)/1000000.;
            long changed=0;for(int y=600;y<1740;y++)for(int x=40;x<1180;x++) {
                int index=4*(y*1220+x),delta=0;for(int k=0;k<3;k++)delta+=Math.abs((original[index+k]&255)-(processed[index+k]&255));
                if(delta>6)changed++;
            }
            assertTrue("No processed face over Pyrowave",changed>1140*1140/100);
            for(int pass=0;pass<2;pass++) {
                android.graphics.Bitmap image=android.graphics.Bitmap.createBitmap(1220,2712,android.graphics.Bitmap.Config.ARGB_8888);
                image.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(pass==0?original:processed));
                try(java.io.OutputStream output=new java.io.FileOutputStream(new java.io.File(getTargetContext().getExternalFilesDir(null),pass==0?"pyrowave-original.png":"pyrowave-phoenix.png"))) {assertTrue(image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output));}
                image.recycle();
            }
            Bundle proof=new Bundle();proof.putLong("changed_face_pixels",changed);proof.putDouble("phoenix_roundtrip_ms",elapsedMs);proof.putLong("pc_processing_us",client.processingUs);sendStatus(0,proof);
        }
        ConnectionStore.save(getTargetContext(),c);assertTrue(ConnectionStore.load(getTargetContext()).codec.equals("pyrowave"));
        new java.io.File(getTargetContext().getFilesDir(),"pyrowave-face.jpg").delete();
        new java.io.File(getTargetContext().getFilesDir(),"emulator-pairing.json").delete();
    }
    public void configurePhoneConnection() throws Exception {
        LinkConfig c=config(); c.validate();
        String transport="raw";
        try (NativeCodec codec=new NativeCodec(257,255,100,5)) {
            byte[] original=pattern(257,255), decoded=codec.decode(codec.encode(original),original.length);
            assertEquals(original.length,decoded.length);
            long error=0;for(int i=0;i<original.length;i++)if(i%4!=3)error+=Math.abs((original[i]&255)-(decoded[i]&255));
            assertTrue("Local codec pixel error",error/(257.*255*3)<12);
            transport="pyrowave";
        } catch(Exception unsupported) {
            Bundle notice=new Bundle();notice.putString("codec_notice",unsupported.getMessage());sendStatus(0,notice);
        }
        c.codec=transport;
        try (LanClient client=new LanClient(c,257,255,new LinkConfig.Tuning(false,1,1,1,-1,false,0),v -> {})) {
            byte[] original=pattern(257,255), returned=client.frame(original,true);
            assertEquals(original.length,returned.length);
            long error=0;for(int i=0;i<original.length;i++)if(i%4!=3)error+=Math.abs((original[i]&255)-(returned[i]&255));
            assertTrue("PC roundtrip pixel error",error/(257.*255*3)<12);
            client.ping();
        }
        ConnectionStore.save(getTargetContext(),c);
        LinkConfig saved=ConnectionStore.load(getTargetContext());
        assertTrue("Connection not saved",saved.token.equals(c.token)&&saved.codec.equals(c.codec)&&saved.captureMode.equals("follow"));
        assertTrue("Encrypted pairing file missing",new java.io.File(getTargetContext().getApplicationInfo().dataDir,"shared_prefs/connection.xml").isFile());
        new java.io.File(getTargetContext().getFilesDir(),"emulator-pairing.json").delete();
        Bundle stats=new Bundle();stats.putString("transport",transport);stats.putString("capture_mode",c.captureMode);stats.putInt("max_edge",c.maxEdge);sendStatus(0,stats);
    }
    public void verifySavedPhoneConnection() throws Exception {
        LinkConfig saved=ConnectionStore.load(getTargetContext());saved.validate();
        assertTrue("Transport not retained",saved.codec.equals(arguments.getString("transport","raw")));
        assertTrue("Follow mode not retained",saved.captureMode.equals("follow"));
        Activity activity=startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
        runOnMainSync(() -> {
            try {
                for(String name:new String[]{"host","pin","token"}) {
                    java.lang.reflect.Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);
                    String expected=name.equals("host")?saved.host:name.equals("pin")?saved.fingerprint:saved.token;
                    assertTrue("Actual app field not populated: "+name,((EditText)f.get(activity)).getText().toString().equals(expected));
                }
                java.lang.reflect.Field mode=MainActivity.class.getDeclaredField("captureMode");mode.setAccessible(true);
                assertEquals(1,((Spinner)mode.get(activity)).getSelectedItemPosition());
                java.lang.reflect.Field codec=MainActivity.class.getDeclaredField("codec");codec.setAccessible(true);
                assertEquals(saved.codec.equals("pyrowave")?0:1,((Spinner)codec.get(activity)).getSelectedItemPosition());
            } catch(ReflectiveOperationException e) {throw new AssertionError(e);}
        });
        Bundle stats=new Bundle();stats.putBoolean("persisted_after_restart",true);stats.putBoolean("visible_app_fields_verified",true);sendStatus(0,stats);
    }
    public void testRawRoundTripAndControls() throws Exception {
        LinkConfig c=config(); c.codec="raw"; byte[] input=pattern(257,255);
        try(LanClient client=new LanClient(c,257,255,LinkConfig.Tuning.defaults(),v -> {})) {
            for(int frame=0;frame<3;frame++) {
                byte[] output=client.frame(input,frame==0); assertEquals(input.length,output.length);
                for(int i=0;i<input.length;i++) assertEquals(i%4==3 ? input[i] : (byte)(255-input[i]),output[i]);
            }
            client.update(new LinkConfig.Tuning(false,2,1.5f,.5f,.2f,true,1));
            assertTrue(Arrays.equals(input,client.frame(input,true))); client.ping();
        }
        try(LanClient client=new LanClient(c,32,32,LinkConfig.Tuning.defaults(),v -> {})) { assertEquals(4096,client.frame(pattern(32,32),true).length); }
    }
    public void testPairingRejectionAndRecovery() throws Exception {
        LinkConfig c=config(); c.codec="raw"; c.fingerprint="0".repeat(64);
        try { new LanClient(c,32,32,LinkConfig.Tuning.defaults(),v -> {}); fail("Wrong pin accepted"); } catch(java.io.IOException expected) { }
        c=config(); c.codec="raw"; c.token="0".repeat(64);
        try { new LanClient(c,32,32,LinkConfig.Tuning.defaults(),v -> {}); fail("Wrong token accepted"); } catch(java.io.IOException expected) { }
        c=config(); c.codec="raw";
        try(LanClient client=new LanClient(c,32,32,LinkConfig.Tuning.defaults(),v -> {})) { client.ping(); }
    }
    public void testStopFromMainThread() throws Exception {
        LinkConfig c=config(); c.codec="raw";
        try(LanClient client=new LanClient(c,32,32,LinkConfig.Tuning.defaults(),v -> {})) {
            runOnMainSync(client::abort);
            try { client.frame(pattern(32,32),true); fail("Stopped connection accepted a frame"); } catch(java.io.IOException expected) { }
        }
    }
    public void testPyrowaveRoundTrip() throws Exception {
        LinkConfig c=config(); c.codec="pyrowave"; byte[] input=pattern(257,255);
        try(LanClient client=new LanClient(c,257,255,LinkConfig.Tuning.defaults(),v -> {})) {
            byte[] output=client.frame(input,true); long error=0;
            for(int i=0;i<input.length;i++) if(i%4!=3) error+=Math.abs((255-(input[i]&255))-(output[i]&255));
            assertTrue("Compressed roundtrip mean error: "+error/(257.*255*3),error/(257.*255*3)<12);
            client.update(new LinkConfig.Tuning(false,1,1,1,-1,false,1));
            assertEquals(input.length,client.frame(input,true).length);
        }
    }
    public void testPhoenixRoundTrip() throws Exception {
        LinkConfig c=config(); c.codec="raw"; byte[] input=pattern(257,255);
        try(LanClient client=new LanClient(c,257,255,new LinkConfig.Tuning(false,1,1,1,-1,false,0),v -> {})) {
            assertTrue("Disabled Phoenix DLSS5 must return original",Arrays.equals(input,client.frame(input,true)));
            client.update(LinkConfig.Tuning.defaults());
            byte[] output=client.frame(input,true); assertEquals(input.length,output.length);
            long changed=0; for(int i=0;i<input.length;i++) if(input[i]!=output[i]) changed++;
            assertTrue("Phoenix DLSS5 returned unchanged bytes",changed>100);
            Bundle stats=new Bundle(); stats.putLong("changed_bytes",changed); stats.putLong("processing_us",client.processingUs); sendStatus(0,stats);
        }
    }
    public void testOverlayPixelsVisibilityAndCleanup() throws Exception {
        LinkConfig c=config(); final OverlayController[] controller=new OverlayController[1];
        java.util.concurrent.atomic.AtomicBoolean stopped=new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicReference<LinkConfig.Tuning> applied=new java.util.concurrent.atomic.AtomicReference<>();
        try {
            runOnMainSync(() -> controller[0]=new OverlayController(getTargetContext(),c,() -> stopped.set(true),applied::set));
            Thread.sleep(500);
            java.lang.reflect.Field field=OverlayController.class.getDeclaredField("image"); field.setAccessible(true);
            View view=(View)field.get(controller[0]); byte[] rgba=pattern(257,255);
            runOnMainSync(() -> {
                controller[0].frame(257,255,rgba,"Test frame"); assertTrue(view.isAttachedToWindow()); assertEquals(View.VISIBLE,view.getVisibility());
                android.graphics.Bitmap rendered=android.graphics.Bitmap.createBitmap(view.getWidth(),view.getHeight(),android.graphics.Bitmap.Config.ARGB_8888);
                view.draw(new android.graphics.Canvas(rendered));
                android.graphics.Rect bounds=getTargetContext().getSystemService(WindowManager.class).getMaximumWindowMetrics().getBounds();
                int[] location=new int[2];view.getLocationOnScreen(location);
                int pixel=rendered.getPixel(bounds.centerX()-location[0],bounds.centerY()-location[1]);
                assertTrue("RGBA channels incorrectly rendered",Math.abs(android.graphics.Color.red(pixel)-127)<3 && Math.abs(android.graphics.Color.green(pixel)-127)<3 && android.graphics.Color.blue(pixel)==80);
                controller[0].frame(bounds.width(),bounds.height(),pattern(bounds.width(),bounds.height()),"Native coordinates");
                view.draw(new android.graphics.Canvas(rendered));
                int x=view.getWidth()/3,y=view.getHeight()/3;
                pixel=rendered.getPixel(x,y);
                assertTrue("Display capture shrank to overlay insets",Math.abs(android.graphics.Color.red(pixel)-(x+location[0]-bounds.left)*255/bounds.width())<=1 &&
                    Math.abs(android.graphics.Color.green(pixel)-(y+location[1]-bounds.top)*255/bounds.height())<=1);
                controller[0].sourceVisible(false); assertEquals(View.INVISIBLE,view.getVisibility());
                controller[0].sourceVisible(true); assertEquals(View.VISIBLE,view.getVisibility());
                try {
                    java.lang.reflect.Field controls=OverlayController.class.getDeclaredField("controls"); controls.setAccessible(true);
                    View toolbar=(View)controls.get(controller[0]); command(toolbar,"Phoenix DLSS5 controls").performClick();
                    toolbar=(View)controls.get(controller[0]);
                    command(toolbar,"Show original / Phoenix DLSS5").performClick(); assertEquals(View.INVISIBLE,view.getVisibility());
                    command(toolbar,"Show original / Phoenix DLSS5").performClick(); assertEquals(View.VISIBLE,view.getVisibility());
                    Button apply=find(toolbar,"Apply settings"); assertNotNull(apply); apply.performClick(); assertNotNull(applied.get());
                    command(toolbar,"Stop session").performClick(); assertTrue(stopped.get());
                } catch(ReflectiveOperationException e) { throw new RuntimeException(e); }
                controller[0].configure(true,1); controller[0].close(); controller[0].close();
            });
            waitForIdleSync(); Thread.sleep(200);
            assertFalse("Overlay remained attached",view.isAttachedToWindow());
        } finally { if(controller[0]!=null)runOnMainSync(() -> controller[0].close()); }
    }
    public void testOverlayImmediateStop() throws Exception {
        LinkConfig c=config(); final View[] view=new View[1];
        runOnMainSync(() -> {
            OverlayController overlay=new OverlayController(getTargetContext(),c,() -> {},v -> {});
            try {
                java.lang.reflect.Field field=OverlayController.class.getDeclaredField("image"); field.setAccessible(true); view[0]=(View)field.get(overlay);
            } catch(Exception e) { throw new RuntimeException(e); }
            overlay.close(); overlay.close();
        });
        waitForIdleSync(); Thread.sleep(300); assertFalse("Startup Stop leaked an overlay",view[0].isAttachedToWindow());
    }
    public void testConnectionStorage() throws Exception {
        LinkConfig c=config(); ConnectionStore.save(getTargetContext(),c);
        LinkConfig restored=ConnectionStore.load(getTargetContext());
        assertTrue(c.fingerprint.equals(restored.fingerprint) && c.token.equals(restored.token) && c.host.equals(restored.host));
        assertFalse("Pairing token stored in plaintext",getTargetContext().getSharedPreferences("connection",0).getString("encrypted", "").contains(c.token));
        ConnectionStore.forget(getTargetContext()); assertTrue(ConnectionStore.load(getTargetContext()).token.isEmpty());
    }
    public void testUiSnapshots() throws Exception {
        ConnectionStore.forget(getTargetContext());
        final Activity[] activity={startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))};
        runOnMainSync(() -> activity[0].getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE));
        try {
            Thread.sleep(700); snapshot("ui-portrait.png");
            ActivityMonitor monitor=addMonitor(MainActivity.class.getName(),null,false);
            automation().setRotation(UiAutomation.ROTATION_FREEZE_90);
            Activity rotated=monitor.waitForActivityWithTimeout(5000); removeMonitor(monitor);
            assertNotNull(rotated); activity[0]=rotated;
            runOnMainSync(() -> activity[0].getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE));
            Thread.sleep(700); snapshot("ui-landscape.png");
        } finally {
            runOnMainSync(() -> {activity[0].getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE); activity[0].finish();});
            automation().setRotation(UiAutomation.ROTATION_UNFREEZE);
        }
    }
    private void snapshot(String name) throws Exception {
        android.graphics.Bitmap image=automation().takeScreenshot(); assertNotNull(image);
        try(java.io.FileOutputStream file=new java.io.FileOutputStream(new java.io.File(getTargetContext().getExternalFilesDir(null),name))) {
            assertTrue(image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,file));
        } finally {image.recycle();}
    }
    public void testPrepareCaptureUi() throws Exception {
        LinkConfig c=config(); c.codec="raw"; ConnectionStore.save(getInstrumentation().getTargetContext(),c);
        getContext().startActivity(new Intent().setClassName(getContext(),PatternActivity.class.getName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        Thread.sleep(500);
        Intent intent=new Intent(getInstrumentation().getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Activity activity=getInstrumentation().startActivitySync(intent);
        getInstrumentation().runOnMainSync(() -> {
            Button start=find(activity.getWindow().getDecorView(),"Start session"); assertNotNull(start); start.performClick();
        });
        try {
            // The picker displays the installed test APK's manifest label.
            // Resolve it instead of coupling consent automation to old branding.
            String testAppLabel = getContext().getApplicationInfo().loadLabel(getContext().getPackageManager()).toString();
            clickSystemNode("CHOOSE APP"); clickSystemNode("Start"); clickSystemNode(testAppLabel);
            long end=System.currentTimeMillis()+20000;
            while(!CaptureService.status.startsWith("raw  ") && System.currentTimeMillis()<end)Thread.sleep(200);
            assertTrue("Capture did not deliver frames: "+CaptureService.status,CaptureService.status.startsWith("raw  "));
            Thread.sleep(3000);
            tuningForCapture();
        } finally { getTargetContext().startService(new Intent(getTargetContext(),CaptureService.class).setAction(CaptureService.STOP)); }
        for(int i=0;i<100 && CaptureService.busy.get();i++) Thread.sleep(100);
        assertFalse("Session cleanup is still busy",CaptureService.busy.get());
    }
    public void testFaceCaptureScreenshots() throws Exception {
        LinkConfig c=config(); c.codec="raw"; c.maxEdge=4096; ConnectionStore.save(getTargetContext(),c);
        CaptureService.tuning.set(LinkConfig.Tuning.defaults());
        getContext().startActivity(new Intent().setClassName(getContext(),FaceActivity.class.getName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
        Thread.sleep(1000); snapshot("face-original.png");
        Activity activity=startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
        runOnMainSync(() -> find(activity.getWindow().getDecorView(),"Start session").performClick());
        final CaptureService[] capture=new CaptureService[1];
        java.util.concurrent.CountDownLatch bound=new java.util.concurrent.CountDownLatch(1);
        ServiceConnection connection=new ServiceConnection() {
            public void onServiceConnected(ComponentName name,IBinder binder) {capture[0]=((CaptureService.LocalBinder)binder).service();bound.countDown();}
            public void onServiceDisconnected(ComponentName name) {capture[0]=null;}
        };
        boolean attached=false;
        try {
            clickSystemNode("CHOOSE APP");clickSystemNode("Start");clickSystemNode("Phoenix DLSS5 test pattern");
            attached=getTargetContext().bindService(new Intent(getTargetContext(),CaptureService.class),connection,0);
            assertTrue("Could not bind active capture",attached && bound.await(10,java.util.concurrent.TimeUnit.SECONDS));
            long end=System.currentTimeMillis()+60000;
            while(System.currentTimeMillis()<end) {
                java.util.regex.Matcher count=java.util.regex.Pattern.compile("#(\\d+)").matcher(CaptureService.status);
                if(count.find() && Integer.parseInt(count.group(1))>=4)break;
                Thread.sleep(200);
            }
            assertTrue("No real Phoenix DLSS5 face frames: "+CaptureService.status,CaptureService.status.startsWith("raw  "));
            java.lang.reflect.Field field=CaptureService.class.getDeclaredField("overlay");field.setAccessible(true);
            OverlayController overlay=(OverlayController)field.get(capture[0]);assertNotNull(overlay);
            runOnMainSync(() -> {
                try {
                    // Only this test's known face overlay is unprotected for evidence.
                    WindowManager wm=getTargetContext().getSystemService(WindowManager.class);
                    for(String name:new String[]{"image","controls"}) {
                        java.lang.reflect.Field viewField=OverlayController.class.getDeclaredField(name);viewField.setAccessible(true);
                        View view=(View)viewField.get(overlay);
                        WindowManager.LayoutParams p=(WindowManager.LayoutParams)view.getLayoutParams();
                        p.flags&=~WindowManager.LayoutParams.FLAG_SECURE;wm.updateViewLayout(view,p);
                        if(name.equals("image")) {
                            java.lang.reflect.Field bitmap=view.getClass().getDeclaredField("bitmap");bitmap.setAccessible(true);
                            android.graphics.Bitmap pixels=(android.graphics.Bitmap)bitmap.get(view);
                            try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(getTargetContext().getExternalFilesDir(null),"face-returned-frame.png"))) {
                                pixels.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);
                            }
                        }
                    }
                } catch(Exception e) {throw new RuntimeException(e);}
            });
            Thread.sleep(500);snapshot("face-phoenix.png");
            Bundle stats=new Bundle();stats.putString("capture_status",CaptureService.status);sendStatus(0,stats);
        } finally {
            if(capture[0]!=null)runOnMainSync(() -> {
                try {
                    java.lang.reflect.Field field=CaptureService.class.getDeclaredField("overlay");field.setAccessible(true);
                    OverlayController overlay=(OverlayController)field.get(capture[0]);
                    if(overlay!=null) for(String name:new String[]{"image","controls"}) {
                        java.lang.reflect.Field vf=OverlayController.class.getDeclaredField(name);vf.setAccessible(true);View view=(View)vf.get(overlay);
                        WindowManager.LayoutParams p=(WindowManager.LayoutParams)view.getLayoutParams();p.flags|=WindowManager.LayoutParams.FLAG_SECURE;
                        getTargetContext().getSystemService(WindowManager.class).updateViewLayout(view,p);
                    }
                } catch(Exception ignored) { }
            });
            getTargetContext().startService(new Intent(getTargetContext(),CaptureService.class).setAction(CaptureService.STOP));
            if(attached)getTargetContext().unbindService(connection);
        }
        for(int i=0;i<100 && CaptureService.busy.get();i++)Thread.sleep(100);
        assertFalse("Face session did not stop",CaptureService.busy.get());
    }
    private void tuningForCapture() throws Exception {
        CaptureService.tuning.set(new LinkConfig.Tuning(false,2,1.5f,.5f,.2f,true,10));
        Thread.sleep(1500); assertTrue("Capture stopped during Apply: "+CaptureService.status,CaptureService.running);
    }
    public void testFollowActiveApp() throws Exception {
        automation();
        // Starting instrumentation force-stops the target, which disables its service.
        // Re-enable only in the isolated emulator after instrumentation has started.
        if(Build.MODEL.contains("sdk_gphone") || Build.FINGERPRINT.contains("generic")) {
            String current=android.provider.Settings.Secure.getString(getTargetContext().getContentResolver(),"enabled_accessibility_services");
            String component="io.phoenixlink/io.phoenixlink.FollowAccessibilityService";
            String enabled=current==null || current.isEmpty()?component:current.contains(component)?current:current+":"+component;
            String other=current==null?"":java.util.Arrays.stream(current.split(":"))
                .filter(s -> !s.startsWith("io.phoenixlink/")).collect(java.util.stream.Collectors.joining(":"));
            try(android.os.ParcelFileDescriptor command=automation().executeShellCommand(other.isEmpty()?"settings delete secure enabled_accessibility_services":"settings put secure enabled_accessibility_services "+other);
                java.io.InputStream output=new android.os.ParcelFileDescriptor.AutoCloseInputStream(command)) {output.readAllBytes();}
            Thread.sleep(500);
            try(android.os.ParcelFileDescriptor command=automation().executeShellCommand("settings put secure enabled_accessibility_services "+enabled);
                java.io.InputStream output=new android.os.ParcelFileDescriptor.AutoCloseInputStream(command)) {output.readAllBytes();}
            try(android.os.ParcelFileDescriptor command=automation().executeShellCommand("settings put secure accessibility_enabled 1");
                java.io.InputStream output=new android.os.ParcelFileDescriptor.AutoCloseInputStream(command)) {output.readAllBytes();}
        }
        long deadline=System.currentTimeMillis()+15000;
        while(FollowAccessibilityService.connected()==null && System.currentTimeMillis()<deadline)Thread.sleep(200);
        assertNotNull(FollowAccessibilityService.connected());
        boolean face="face".equals(arguments.getString("source"));
        LinkConfig c=config();c.codec="raw";c.captureMode="follow";c.maxEdge=face?4096:720;c.opacity=1;ConnectionStore.save(getTargetContext(),c);
        CaptureService.tuning.set(LinkConfig.Tuning.defaults());
        launchTest(face?FaceActivity.class:PatternActivity.class);
        Activity activity=startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
        runOnMainSync(() -> find(activity.getWindow().getDecorView(),"Start session").performClick());
        clickSystemNode("START FOLLOW MODE");
        final CaptureService[] capture=new CaptureService[1];java.util.concurrent.CountDownLatch bound=new java.util.concurrent.CountDownLatch(1);
        ServiceConnection connection=new ServiceConnection() {
            public void onServiceConnected(ComponentName name,IBinder binder){capture[0]=((CaptureService.LocalBinder)binder).service();bound.countDown();}
            public void onServiceDisconnected(ComponentName name){capture[0]=null;}
        };
        boolean attached=getTargetContext().bindService(new Intent(getTargetContext(),CaptureService.class),connection,0);
        try {
            assertTrue("Follow service did not bind: "+CaptureService.status,attached && bound.await(10,java.util.concurrent.TimeUnit.SECONDS));
            View image=followImage(capture[0]);waitForFollowImage(capture[0],image,-1);
            int first=capture[0].followSource.get().window;
            WindowManager.LayoutParams params=(WindowManager.LayoutParams)image.getLayoutParams();
            assertEquals(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,params.type);assertTrue(params.alpha==1);
            java.lang.reflect.Field overlayField=CaptureService.class.getDeclaredField("overlay");overlayField.setAccessible(true);
            OverlayController overlay=(OverlayController)overlayField.get(capture[0]);
            runOnMainSync(() -> FollowAccessibilityService.connected().showControls());
            java.lang.reflect.Field controlsField=OverlayController.class.getDeclaredField("controls");controlsField.setAccessible(true);
            View controls=(View)controlsField.get(overlay);
            View original=findDescription(controls,"Show original / Phoenix DLSS5");assertNotNull(original);
            runOnMainSync(original::performClick);assertEquals(View.INVISIBLE,image.getVisibility());
            runOnMainSync(original::performClick);assertEquals(View.VISIBLE,image.getVisibility());
            Button apply=find(controls,"Apply settings");assertNotNull(apply);long revision=CaptureService.tuning.get().revision;
            runOnMainSync(apply::performClick);assertTrue("Floating Apply did not update settings",CaptureService.tuning.get().revision!=revision);
            assertNotNull(findDescription(controls,"Stop session"));
            if(face) {
                android.graphics.Bitmap enhanced=followSnapshot(overlay,image,"Phoenix","follow-phoenix.png");
                Switch enabled=findSwitch(controls,"Phoenix DLSS5 enabled");assertNotNull(enabled);
                runOnMainSync(() -> {enabled.setChecked(false);apply.performClick();});
                android.graphics.Bitmap originalFrame=followSnapshot(overlay,image,"Original","follow-original.png");
                assertEquals(enhanced.getWidth(),originalFrame.getWidth());assertEquals(enhanced.getHeight(),originalFrame.getHeight());
                long changed=0,total=0;
                for(int y=enhanced.getHeight()/10;y<enhanced.getHeight()*9/10;y++)for(int x=enhanced.getWidth()/10;x<enhanced.getWidth()*9/10;x++) {
                    int a=enhanced.getPixel(x,y),b=originalFrame.getPixel(x,y);
                    int delta=Math.abs(android.graphics.Color.red(a)-android.graphics.Color.red(b))+Math.abs(android.graphics.Color.green(a)-android.graphics.Color.green(b))+Math.abs(android.graphics.Color.blue(a)-android.graphics.Color.blue(b));
                    if(delta>6)changed++;total++;
                }
                enhanced.recycle();originalFrame.recycle();assertTrue("Follow output unchanged",changed>total/100);
                Bundle proof=new Bundle();proof.putLong("changed_interior_pixels",changed);proof.putLong("interior_pixels",total);sendStatus(0,proof);
                runOnMainSync(() -> {enabled.setChecked(true);apply.performClick();});
                followSnapshot(overlay,image,"Phoenix","follow-reenabled.png").recycle();
            }
            // This frame is under the effect but must still be an original source image.
            Thread.sleep(1000);assertTrue("Recursive capture stopped stream: "+CaptureService.status,CaptureService.running);
            getTargetContext().startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            waitForFollowImage(capture[0],image,first);int second=capture[0].followSource.get().window;assertTrue(second!=first);
            launchTest(ProtectedActivity.class);
            deadline=System.currentTimeMillis()+15000;
            while(!CaptureService.status.startsWith("Protected window") && System.currentTimeMillis()<deadline)Thread.sleep(100);
            assertTrue("Secure window was not skipped: "+CaptureService.status,CaptureService.status.startsWith("Protected window"));
            assertEquals(View.INVISIBLE,image.getVisibility());
            Thread.sleep(1200);assertEquals(View.INVISIBLE,image.getVisibility());
            launchTest(PatternActivity.class);waitForFollowImage(capture[0],image,-1);
            // Returning to pairing/settings must hide even an already-processing frame.
            getTargetContext().startActivity(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            deadline=System.currentTimeMillis()+5000;
            while(capture[0].followSource.get().window!=-1 && System.currentTimeMillis()<deadline)Thread.sleep(100);
            assertEquals(-1,capture[0].followSource.get().window);assertEquals(View.INVISIBLE,image.getVisibility());
        } finally {
            if(capture[0]!=null) {
                runOnMainSync(capture[0]::showControls);
                java.lang.reflect.Field overlayField=CaptureService.class.getDeclaredField("overlay");overlayField.setAccessible(true);
                Object overlay=overlayField.get(capture[0]);
                if(overlay!=null) {
                    java.lang.reflect.Field controlsField=OverlayController.class.getDeclaredField("controls");controlsField.setAccessible(true);
                    View stop=findDescription((View)controlsField.get(overlay),"Stop session");assertNotNull(stop);
                    runOnMainSync(stop::performClick);
                }
            }
            getTargetContext().startService(new Intent(getTargetContext(),CaptureService.class).setAction(CaptureService.STOP));
            if(attached)getTargetContext().unbindService(connection);
        }
        for(int i=0;i<100 && CaptureService.busy.get();i++)Thread.sleep(100);
        assertFalse("Follow cleanup still busy",CaptureService.busy.get());
    }
    private Switch findSwitch(View view,String text) {
        if(view instanceof Switch value && text.contentEquals(value.getText()))return value;
        if(view instanceof ViewGroup group)for(int i=0;i<group.getChildCount();i++){Switch found=findSwitch(group.getChildAt(i),text);if(found!=null)return found;}
        return null;
    }
    private android.graphics.Bitmap followSnapshot(OverlayController overlay,View image,String suffix,String file) throws Exception {
        java.lang.reflect.Field message=OverlayController.class.getDeclaredField("message");message.setAccessible(true);
        java.lang.reflect.Field bitmap=image.getClass().getDeclaredField("bitmap");bitmap.setAccessible(true);
        final android.graphics.Bitmap[] copy=new android.graphics.Bitmap[1];long end=System.currentTimeMillis()+60000;
        while(System.currentTimeMillis()<end && copy[0]==null) {
            runOnMainSync(() -> {
                try {if(((String)message.get(overlay)).endsWith(suffix) && image.getVisibility()==View.VISIBLE)
                    copy[0]=((android.graphics.Bitmap)bitmap.get(image)).copy(android.graphics.Bitmap.Config.ARGB_8888,false);
                } catch(IllegalAccessException e){throw new AssertionError(e);}
            });
            if(copy[0]==null)Thread.sleep(200);
        }
        assertNotNull(copy[0]);
        try(java.io.OutputStream out=new java.io.FileOutputStream(new java.io.File(getTargetContext().getExternalFilesDir(null),file))) {
            assertTrue(copy[0].compress(android.graphics.Bitmap.CompressFormat.PNG,100,out));
        }
        return copy[0];
    }
    private View findDescription(View view,String description) {
        if(description.contentEquals(view.getContentDescription()==null?"":view.getContentDescription()))return view;
        if(view instanceof ViewGroup group)for(int i=0;i<group.getChildCount();i++){View found=findDescription(group.getChildAt(i),description);if(found!=null)return found;}
        return null;
    }
    private void launchTest(Class<?> type) throws Exception {
        getContext().startActivity(new Intent().setClassName(getContext(),type.getName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));Thread.sleep(500);
    }
    private View followImage(CaptureService capture) throws Exception {
        java.lang.reflect.Field overlay=CaptureService.class.getDeclaredField("overlay");overlay.setAccessible(true);Object value=overlay.get(capture);assertNotNull(value);
        java.lang.reflect.Field image=OverlayController.class.getDeclaredField("image");image.setAccessible(true);return (View)image.get(value);
    }
    private void waitForFollowImage(CaptureService capture,View image,int previousWindow) throws Exception {
        long end=System.currentTimeMillis()+45000;
        while(System.currentTimeMillis()<end) {
            FollowSourceState.Snapshot s=capture.followSource.get();
            if(s.window>=0 && s.window!=previousWindow && s.allowed && image.getVisibility()==View.VISIBLE && CaptureService.status.startsWith("raw  "))return;
            if(!CaptureService.running)fail("Follow session ended: "+CaptureService.status);
            Thread.sleep(200);
        }
        fail("No enhanced active-window image: "+CaptureService.status);
    }
    private void clickSystemNode(String text) throws Exception {
        long end=System.currentTimeMillis()+15000;
        while(System.currentTimeMillis()<end) {
            android.view.accessibility.AccessibilityNodeInfo node=matchNode(automation().getRootInActiveWindow(),text);
            if(node!=null) {
                android.graphics.Rect rect=new android.graphics.Rect(); node.getBoundsInScreen(rect);
                long now=SystemClock.uptimeMillis();
                MotionEvent down=MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,rect.centerX(),rect.centerY(),0); down.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
                MotionEvent up=MotionEvent.obtain(now,now+30,MotionEvent.ACTION_UP,rect.centerX(),rect.centerY(),0); up.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
                try { automation().injectInputEvent(down,true); automation().injectInputEvent(up,true); } finally {down.recycle();up.recycle();}
                Thread.sleep(700); return;
            }
            Thread.sleep(200);
        }
        fail("Android consent control not found: "+text);
    }
    private android.view.accessibility.AccessibilityNodeInfo matchNode(android.view.accessibility.AccessibilityNodeInfo node,String text) {
        if(node==null)return null;
        if(text.contentEquals(node.getText()==null?"":node.getText()) || text.contentEquals(node.getContentDescription()==null?"":node.getContentDescription()))return node;
        for(int i=0;i<node.getChildCount();i++){android.view.accessibility.AccessibilityNodeInfo found=matchNode(node.getChild(i),text);if(found!=null)return found;}
        return null;
    }
    private Button find(View view,String text) {
        if(view instanceof Button && text.contentEquals(((Button)view).getText())) return (Button)view;
        if(view instanceof ViewGroup) for(int i=0;i<((ViewGroup)view).getChildCount();i++) { Button b=find(((ViewGroup)view).getChildAt(i),text); if(b!=null)return b; }
        return null;
    }
    private View command(View view,String description) {
        if(description.contentEquals(view.getContentDescription()==null?"":view.getContentDescription()))return view;
        if(view instanceof ViewGroup) for(int i=0;i<((ViewGroup)view).getChildCount();i++){View found=command(((ViewGroup)view).getChildAt(i),description);if(found!=null)return found;}
        return null;
    }
}
