package io.phoenixlink;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.*;
import android.view.View;

public final class PatternActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(new View(this) {
            final Paint paint = new Paint();
            @Override protected void onDraw(Canvas canvas) {
                canvas.drawColor(Color.rgb(200, 40, 60));
                paint.setColor(Color.rgb(20, 180, 80)); canvas.drawRect(0, 0, getWidth()/2f, getHeight()/2f, paint);
                paint.setColor(Color.WHITE); paint.setTextSize(48); canvas.drawText("Phoenix DLSS5 emulator source", 40, getHeight()/2f+80, paint);
                paint.setColor(Color.rgb(30, 60, 210));
                canvas.drawCircle(80+(System.currentTimeMillis()/10 % Math.max(1,getWidth()-160)), getHeight()*.75f, 60, paint);
                postInvalidateDelayed(100);
            }
        });
    }
}
