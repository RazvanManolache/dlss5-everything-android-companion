package io.phoenixlink;

import android.content.Context;
import android.graphics.*;
import android.hardware.input.InputManager;
import android.view.*;
import android.widget.*;
import java.nio.ByteBuffer;

final class OverlayController {
    private final Context context;
    private final WindowManager wm;
    private final boolean trusted;
    private final FrameView image;
    private View controls;
    private WindowManager.LayoutParams imageParams, controlParams;
    private final Runnable stop;
    private final java.util.function.Consumer<LinkConfig.Tuning> apply;
    private boolean expanded, hidden, closed, imageAdded, controlsAdded, sourceVisible = true;
    private TextView status;
    private String message="Waiting for frames";
    OverlayController(Context c, LinkConfig config, Runnable stop, java.util.function.Consumer<LinkConfig.Tuning> apply) {
        this(c,config,stop,apply,false);
    }
    OverlayController(Context c, LinkConfig config, Runnable stop, java.util.function.Consumer<LinkConfig.Tuning> apply,boolean trusted) {
        this.trusted=trusted;
        context = c; wm = c.getSystemService(WindowManager.class); this.stop = stop; this.apply = apply;
        image = new FrameView(c); image.setVisibility(View.INVISIBLE);
        imageParams = new WindowManager.LayoutParams(-1,-1,trusted?WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY:WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.TRANSLUCENT);
        imageParams.gravity = Gravity.TOP | Gravity.LEFT;
        imageParams.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        try { configure(config.touchThrough, config.opacity); wm.addView(image,imageParams); imageAdded=true; buildControls(); }
        catch(RuntimeException e) { close(); throw e; }
    }
    void configure(boolean touchThrough, float opacity) {
        float limit = trusted?1f:context.getSystemService(InputManager.class).getMaximumObscuringOpacityForTouch();
        if (touchThrough) imageParams.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        else imageParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        imageParams.alpha = Math.max(.1f, Math.min(touchThrough ? limit : 1f, opacity));
        if (image.isAttachedToWindow()) wm.updateViewLayout(image,imageParams);
    }
    private ImageButton icon(int resource, String description, Runnable action) {
        ImageButton b = new ImageButton(context); b.setImageResource(resource); b.setColorFilter(Ui.TEXT);
        b.setBackgroundColor(Color.TRANSPARENT); b.setContentDescription(description); b.setTooltipText(description);
        b.setOnClickListener(v -> action.run()); return b;
    }
    private void buildControls() {
        if (controlsAdded) { wm.removeViewImmediate(controls); controlsAdded=false; }
        LinearLayout root = Ui.column(context); root.setPadding(Ui.dp(context,8),Ui.dp(context,6),Ui.dp(context,8),Ui.dp(context,6)); root.setBackground(Ui.panel(context));
        LinearLayout bar = new LinearLayout(context);
        ImageButton drag = icon(android.R.drawable.ic_menu_preferences, expanded ? "Collapse controls" : "Phoenix DLSS5 controls", () -> { expanded = !expanded; buildControls(); });
        bar.addView(drag,new LinearLayout.LayoutParams(Ui.dp(context,44),Ui.dp(context,44)));
        if (expanded) {
            status = Ui.text(context,message,12); status.setSingleLine(true); status.setEllipsize(android.text.TextUtils.TruncateAt.END); bar.addView(status,new LinearLayout.LayoutParams(0,Ui.dp(context,44),1));
            bar.addView(icon(android.R.drawable.ic_menu_view,"Show original / Phoenix DLSS5",() -> { hidden = !hidden; updateVisibility(); }),new LinearLayout.LayoutParams(Ui.dp(context,44),Ui.dp(context,44)));
        }
        bar.addView(icon(android.R.drawable.ic_menu_close_clear_cancel,"Stop session",stop),new LinearLayout.LayoutParams(Ui.dp(context,44),Ui.dp(context,44)));
        root.addView(bar);
        if (expanded) {
            ScrollView scroll = new ScrollView(context); scroll.addView(new TuningPanel(context,CaptureService.tuning.get(),apply));
            int height = Math.min(Ui.dp(context,430),wm.getCurrentWindowMetrics().getBounds().height()*2/3);
            root.addView(scroll,new LinearLayout.LayoutParams(-1,height));
        }
        if (controlParams == null) {
            controlParams = new WindowManager.LayoutParams(-2,-2,trusted?WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY:WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_SECURE,
                PixelFormat.TRANSLUCENT);
            controlParams.gravity = Gravity.TOP | Gravity.LEFT; controlParams.x = Ui.dp(context,12); controlParams.y = Ui.dp(context,64);
        }
        controlParams.width = expanded ? Math.min(Ui.dp(context,340),wm.getCurrentWindowMetrics().getBounds().width()-Ui.dp(context,24)) : -2;
        final float[] down = new float[2]; final int[] origin = new int[2]; final boolean[] moved = new boolean[1];
        drag.setOnTouchListener((v,event) -> {
            if(event.getAction() == MotionEvent.ACTION_DOWN) { down[0]=event.getRawX(); down[1]=event.getRawY(); origin[0]=controlParams.x; origin[1]=controlParams.y; moved[0]=false; return true; }
            if(event.getAction() == MotionEvent.ACTION_MOVE) {
                float dx=event.getRawX()-down[0],dy=event.getRawY()-down[1]; if(Math.abs(dx)+Math.abs(dy)>Ui.dp(context,8)) moved[0]=true;
                Rect bounds=wm.getCurrentWindowMetrics().getBounds();
                controlParams.x=Math.max(0,Math.min(bounds.width()-root.getWidth(),origin[0]+(int)dx));
                controlParams.y=Math.max(0,Math.min(bounds.height()-root.getHeight(),origin[1]+(int)dy)); wm.updateViewLayout(root,controlParams); return true;
            }
            if(event.getAction() == MotionEvent.ACTION_UP) { if(!moved[0]) v.performClick(); return true; } return true;
        });
        controls=root; wm.addView(controls,controlParams); controlsAdded=true;
    }
    void showControls() {if(!closed && !expanded){expanded=true;buildControls();}}
    void status(String value) {message=value;if(status!=null)status.setText(value);}
    void sourceVisible(boolean value) { sourceVisible=value; updateVisibility(); }
    void frame(int w,int h,byte[] pixels,String message) { if(closed)return; image.setFrame(w,h,pixels); status(message); updateVisibility(); }
    void frame(int w,int h,byte[] pixels,String message,Rect bounds) {
        if(closed)return; image.placement=bounds==null?null:new Rect(bounds); frame(w,h,pixels,message);
    }
    private void updateVisibility() { image.setVisibility(!hidden && sourceVisible && image.bitmap!=null ? View.VISIBLE : View.INVISIBLE); }
    void close() {
        if(closed)return; closed=true;
        // addView registers the window before isAttachedToWindow becomes true.
        // Stop during startup must remove those not-yet-attached windows too.
        if(controlsAdded) { wm.removeViewImmediate(controls); controlsAdded=false; } controls=null;
        if(imageAdded) { wm.removeViewImmediate(image); imageAdded=false; }
        image.bitmap=null;
    }
    private static final class FrameView extends View {
        Bitmap bitmap; Rect placement; final Paint paint=new Paint(Paint.FILTER_BITMAP_FLAG); final RectF target=new RectF();
        FrameView(Context c) { super(c); setBackgroundColor(Color.TRANSPARENT); }
        void setFrame(int w,int h,byte[] rgba) {
            if(bitmap==null || bitmap.getWidth()!=w || bitmap.getHeight()!=h) bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
            bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(rgba)); invalidate();
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas); if(bitmap==null) return;
            WindowMetrics metrics=getContext().getSystemService(WindowManager.class).getMaximumWindowMetrics();
            Insets insets=metrics.getWindowInsets().getInsetsIgnoringVisibility(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
            Rect bounds=metrics.getBounds(); int[] location=new int[2]; getLocationOnScreen(location);
            // Overlay windows may exclude the navigation bar even with LAYOUT_IN_SCREEN.
            // Map a display-sized capture to physical display coordinates, then let
            // the window clip it; fitting to this View would shrink and misalign it.
            RectF area=new RectF(bounds.left-location[0],bounds.top-location[1],bounds.right-location[0],bounds.bottom-location[1]);
            RectF content=new RectF(bounds.left+insets.left-location[0],bounds.top+insets.top-location[1],
                bounds.right-insets.right-location[0],bounds.bottom-insets.bottom-location[1]);
            float aspect=(float)bitmap.getWidth()/bitmap.getHeight();
            if(placement!=null) area=new RectF(placement.left-location[0],placement.top-location[1],placement.right-location[0],placement.bottom-location[1]);
            else if(content.width()>0 && content.height()>0 && Math.abs(content.width()/content.height()-aspect)<.01f &&
                Math.abs(content.width()/content.height()-aspect)<Math.abs(area.width()/area.height()-aspect)) area=content;
            float scale=Math.min(area.width()/bitmap.getWidth(),area.height()/bitmap.getHeight());
            float w=bitmap.getWidth()*scale,h=bitmap.getHeight()*scale;
            target.set(area.centerX()-w/2,area.centerY()-h/2,area.centerX()+w/2,area.centerY()+h/2); canvas.drawBitmap(bitmap,null,target,paint);
        }
    }
}
