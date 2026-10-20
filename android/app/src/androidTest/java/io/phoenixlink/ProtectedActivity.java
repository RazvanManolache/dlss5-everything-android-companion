package io.phoenixlink;
import android.app.Activity;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.TextView;
public final class ProtectedActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        TextView text=new TextView(this);text.setText("Protected test window");text.setTextSize(24);setContentView(text);
    }
}
