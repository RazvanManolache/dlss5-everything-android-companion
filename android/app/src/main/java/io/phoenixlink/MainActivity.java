package io.phoenixlink;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.text.InputType;
import android.view.*;
import android.widget.*;

public final class MainActivity extends Activity {
    private EditText host,port,pin,token;
    private Spinner codec,bitrate,fps,resolution,presentation,captureMode;
    private TextView status;
    private Button start,stop;
    private LinkConfig config;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final Runnable refresh=new Runnable() { public void run() {
        String message=CaptureService.status;
        if(!CaptureService.busy.get() && (message.equals("Disconnected") || message.equals("Stopped")))
            message=config.host.isEmpty()?"Not paired":config.captureMode.equals("follow") && FollowAccessibilityService.connected()==null?"Paired - Accessibility access required":"Ready - "+config.host;
        status.setText(message);start.setEnabled(!CaptureService.busy.get());stop.setEnabled(CaptureService.running);ui.postDelayed(this,500);
    } };
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved); getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE); config=ConnectionStore.load(this);
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true);
        LinearLayout root=Ui.column(this); int pad=Ui.dp(this,20); root.setPadding(pad,pad,pad,pad); scroll.addView(root); setContentView(scroll);
        root.setOnApplyWindowInsetsListener((view,insets) -> {
            android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()); view.setPadding(pad+bars.left,pad+bars.top,pad+bars.right,pad+bars.bottom); return insets;
        });
        root.addView(Ui.heading(this,"Phoenix Link")); status=Ui.text(this,CaptureService.status,14); status.setTextColor(Ui.ACCENT); root.addView(status);
        start=Ui.button(this,"Start session");stop=Ui.button(this,"Stop");start.setOnClickListener(v -> begin());Ui.primary(start);
        stop.setOnClickListener(v -> startService(new Intent(this,CaptureService.class).setAction(CaptureService.STOP)));Ui.actions(root,start,stop);
        Ui.section(root,"Connection");
        host=Ui.field(root,"PC address",config.host,InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
        port=Ui.field(root,"Port",Integer.toString(config.port),InputType.TYPE_CLASS_NUMBER);
        pin=Ui.field(root,"Certificate SHA-256",config.fingerprint,InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS); pin.setTextSize(13);
        token=Ui.field(root,"Pairing token",config.token,InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD); token.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        Button permission=Ui.button(this,"Overlay permission"); permission.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+getPackageName())))); root.addView(permission);
        Button forget=Ui.button(this,"Forget connection"); forget.setOnClickListener(v -> { ConnectionStore.forget(this); host.setText("");pin.setText("");token.setText(""); }); root.addView(forget);
        Ui.section(root,"Stream");
        captureMode=Ui.choice(root,"Capture mode","Single app","Follow active app");captureMode.setSelection(config.captureMode.equals("follow")?1:0);
        Button followPermission=Ui.button(this,"Accessibility permission");followPermission.setOnClickListener(v -> followDisclosure(true));root.addView(followPermission);
        codec=Ui.choice(root,"Transport","Pyrowave","Raw RGBA"); codec.setSelection(config.codec.equals("raw")?1:0);
        bitrate=Ui.choice(root,"Pyrowave bitrate per direction","50 Mbit/s","100 Mbit/s","200 Mbit/s","300 Mbit/s","500 Mbit/s"); bitrate.setSelection(index(new int[]{50,100,200,300,500},config.bitrateMbps,1));
        fps=Ui.choice(root,"Maximum frames per second","5","10","15","30","60"); fps.setSelection(index(new int[]{5,10,15,30,60},config.fps,2));
        resolution=Ui.choice(root,"Capture size","720 px long edge","1280 px long edge","1920 px long edge","2560 px long edge","Native (4096 px cap)"); resolution.setSelection(index(new int[]{720,1280,1920,2560,4096},config.maxEdge,4));
        int opacityLimit=Math.round(getSystemService(android.hardware.input.InputManager.class).getMaximumObscuringOpacityForTouch()*100);
        presentation=Ui.choice(root,"Presentation","Touch-through ("+opacityLimit+"% max)","View only (blocks touches)"); presentation.setSelection(config.touchThrough?0:1);
        captureMode.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            public void onItemSelected(android.widget.AdapterView<?> parent,View view,int position,long id) {
                int selected=presentation.getSelectedItemPosition();
                presentation.setAdapter(new ArrayAdapter<>(MainActivity.this,android.R.layout.simple_spinner_dropdown_item,new String[]{position==1?"Touch-through (100%)":"Touch-through ("+opacityLimit+"% max)","View only (blocks touches)"}));
                presentation.setSelection(Math.max(0,selected));followPermission.setEnabled(position==1);
            }
            public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        codec.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            public void onItemSelected(android.widget.AdapterView<?> p,View v,int pos,long id) { bitrate.setEnabled(pos==0); }
            public void onNothingSelected(android.widget.AdapterView<?> p) { }
        });
        Ui.section(root,"Phoenix DLSS5 settings"); root.addView(new TuningPanel(this,CaptureService.tuning.get(),value -> { CaptureService.tuning.set(value); CaptureService.status=CaptureService.running?"Settings queued":"Settings saved for next session"; }));
    }
    private int index(int[] values,int value,int fallback) { for(int i=0;i<values.length;i++) if(values[i]==value) return i; return fallback; }
    private void begin() {
        try {
            LinkConfig next=new LinkConfig(); next.host=host.getText().toString().trim(); next.port=Integer.parseInt(port.getText().toString());
            next.fingerprint=pin.getText().toString(); next.token=token.getText().toString(); next.codec=codec.getSelectedItemPosition()==0?"pyrowave":"raw";
            next.bitrateMbps=new int[]{50,100,200,300,500}[bitrate.getSelectedItemPosition()]; next.fps=new int[]{5,10,15,30,60}[fps.getSelectedItemPosition()];
            next.maxEdge=new int[]{720,1280,1920,2560,4096}[resolution.getSelectedItemPosition()]; next.touchThrough=presentation.getSelectedItemPosition()==0; next.opacity=next.touchThrough?.8f:1f;
            next.captureMode=captureMode.getSelectedItemPosition()==1?"follow":"single";if(next.captureMode.equals("follow"))next.opacity=1f;
            next.validate(); if(next.codec.equals("pyrowave") && !NativeCodec.PACKAGED) throw new java.io.IOException("Pyrowave library is missing in this APK. Select Raw.");
            ConnectionStore.save(this,next); config=next;
            if(next.captureMode.equals("follow")) {followDisclosure(FollowAccessibilityService.connected()==null);return;}
            if(!Settings.canDrawOverlays(this)) { startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+getPackageName()))); return; }
            new AlertDialog.Builder(this).setTitle("Share a single app")
                .setMessage("In the Android picker, select one app, not the entire screen. Whole-screen sharing cannot exclude this app's enhanced overlay. Protected content cannot be captured.")
                .setNegativeButton("Cancel",null).setPositiveButton("Choose app",(dialog,which) -> {
                    if(checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},2);
                    else requestCapture();
                }).show();
        } catch(Exception e) { CaptureService.status=e.getMessage()==null?"Check connection settings.":e.getMessage(); status.setTextColor(Ui.ERROR); status.setText(CaptureService.status); }
    }
    private void followDisclosure(boolean permission) {
        new AlertDialog.Builder(this).setTitle("Follow active app")
            .setMessage("While this session is on, visible app-window images are sent to your paired PC for Phoenix DLSS5 processing as you switch apps. Protected windows, the lock screen and this settings screen are excluded. No UI text or typing is read and no input is controlled. Stop ends capture. Android Accessibility access is optional and can be revoked in Settings.")
            .setNegativeButton("Cancel",null).setPositiveButton(permission?"Open accessibility settings":"Start follow mode",(dialog,which) -> {
                if(permission)startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                else if(checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},2);
                else startFollow();
            }).show();
    }
    private void startFollow() {
        if(CaptureService.busy.get())return;
        if(FollowAccessibilityService.connected()==null) {CaptureService.status="Enable Phoenix DLSS5 follow access first.";return;}
        startForegroundService(new Intent(this,CaptureService.class).setAction(CaptureService.START).putExtra("config",config.json().toString()));moveTaskToBack(true);
    }
    private void requestCapture() { if(!CaptureService.busy.get()) startActivityForResult(getSystemService(MediaProjectionManager.class).createScreenCaptureIntent(),1); }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results) {
        super.onRequestPermissionsResult(request,permissions,results); if(request==2) {if(config.captureMode.equals("follow"))startFollow();else requestCapture();}
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request==1 && result==RESULT_OK && data!=null) {
            startForegroundService(new Intent(this,CaptureService.class).setAction(CaptureService.START).putExtra("config",config.json().toString()).putExtra("consent",data));
            moveTaskToBack(true);
        }
    }
    @Override protected void onResume() { super.onResume(); ui.post(refresh); }
    @Override protected void onPause() { ui.removeCallbacks(refresh); super.onPause(); }
}
