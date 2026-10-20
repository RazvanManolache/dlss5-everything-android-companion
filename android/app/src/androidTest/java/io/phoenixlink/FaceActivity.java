package io.phoenixlink;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.*;
import android.view.View;

public final class FaceActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Bitmap face=BitmapFactory.decodeFile(new java.io.File(getFilesDir(),"face.jpg").getPath());
        if(face==null)throw new IllegalStateException("The local face test image is missing.");
        setContentView(new View(this) {
            final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
            final Rect crop=new Rect(Math.round(face.getWidth()*.29f),Math.round(face.getHeight()*.04f),Math.round(face.getWidth()*.51f),Math.round(face.getHeight()*.73f));
            @Override protected void onDraw(Canvas canvas) {
                canvas.drawColor(Color.rgb(23,26,29));
                float width=getWidth()*.92f,height=width*crop.height()/crop.width();
                if(height>getHeight()*.85f){height=getHeight()*.85f;width=height*crop.width()/crop.height();}
                RectF target=new RectF((getWidth()-width)/2,(getHeight()-height)/2,(getWidth()+width)/2,(getHeight()+height)/2);
                canvas.drawBitmap(face,crop,target,paint);
                // A small live marker keeps MediaProjection delivering fresh frames.
                paint.setColor((System.currentTimeMillis()/500%2)==0?Color.WHITE:Color.GRAY);
                canvas.drawCircle(getWidth()-30,getHeight()-30,5,paint);postInvalidateDelayed(200);
            }
        });
    }
}
