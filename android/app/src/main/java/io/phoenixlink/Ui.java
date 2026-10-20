package io.phoenixlink;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.*;

final class Ui {
    static final int BG = Color.rgb(23,26,29), SURFACE = Color.rgb(34,39,43), TEXT = Color.rgb(237,241,242), MUTED = Color.rgb(168,183,190), ACCENT = Color.rgb(99,217,194), ERROR = Color.rgb(255,155,155);
    static int dp(Context c, float n) { return Math.round(n*c.getResources().getDisplayMetrics().density); }
    static LinearLayout column(Context c) { LinearLayout v = new LinearLayout(c); v.setOrientation(LinearLayout.VERTICAL); v.setBackgroundColor(BG); return v; }
    static TextView text(Context c, String value, int size) { TextView v = new TextView(c); v.setText(value); v.setTextSize(size); v.setTextColor(TEXT); v.setPadding(0, dp(c,6), 0, dp(c,6)); return v; }
    static TextView heading(Context c, String value) { TextView v = text(c,value,18); v.setTypeface(null, Typeface.BOLD); return v; }
    static void section(LinearLayout parent, String title) { TextView v = heading(parent.getContext(),title); v.setPadding(0, dp(parent.getContext(),20),0,dp(parent.getContext(),8)); parent.addView(v); }
    static Button button(Context c, String caption) {
        Button b = new Button(c); b.setText(caption); b.setAllCaps(false); b.setMaxLines(2); b.setTextSize(16);
        b.setTextColor(new android.content.res.ColorStateList(new int[][]{{-android.R.attr.state_enabled},{}},new int[]{MUTED,TEXT})); b.setMinHeight(dp(c,48)); return b;
    }
    static void primary(Button button) {
        int[][] states={{-android.R.attr.state_enabled},{}};
        button.setBackgroundTintList(new android.content.res.ColorStateList(states,new int[]{SURFACE,ACCENT}));
        button.setTextColor(new android.content.res.ColorStateList(states,new int[]{MUTED,BG}));
    }
    static EditText field(LinearLayout parent, String label, String value, int inputType) {
        Context c = parent.getContext(); TextView caption = text(c,label,13); caption.setTextColor(MUTED); parent.addView(caption);
        EditText edit = new EditText(c); edit.setSingleLine(true); edit.setTextColor(TEXT); edit.setTextSize(16); edit.setInputType(inputType);
        edit.setText(value); edit.setSelectAllOnFocus(false); edit.setMinHeight(dp(c,48)); parent.addView(edit,new LinearLayout.LayoutParams(-1,-2)); return edit;
    }
    static Spinner choice(LinearLayout parent, String label, String... values) {
        Context c = parent.getContext(); TextView caption = text(c,label,13); caption.setTextColor(MUTED); parent.addView(caption);
        Spinner s = new Spinner(c); s.setAdapter(new ArrayAdapter<>(c, android.R.layout.simple_spinner_dropdown_item, values));
        parent.addView(s,new LinearLayout.LayoutParams(-1,dp(c,48))); return s;
    }
    static GradientDrawable panel(Context c) { GradientDrawable d = new GradientDrawable(); d.setColor(SURFACE); d.setCornerRadius(dp(c,8)); d.setStroke(dp(c,1),Color.rgb(65,77,83)); return d; }
    interface ValueChanged { void changed(float value); }
    static SeekBar slider(LinearLayout parent, String label, float min, float max, float value, ValueChanged changed) {
        Context c = parent.getContext(); TextView caption = text(c, label + ": " + String.format(java.util.Locale.ROOT,"%.2f",value), 14); parent.addView(caption);
        SeekBar bar = new SeekBar(c); bar.setMax(200); bar.setProgress(Math.round((value-min)/(max-min)*200));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar b,int p,boolean user) { float v = min+(max-min)*p/200; caption.setText(label+": "+String.format(java.util.Locale.ROOT,"%.2f",v)); if(user) changed.changed(v); }
            public void onStartTrackingTouch(SeekBar b) { } public void onStopTrackingTouch(SeekBar b) { }
        }); parent.addView(bar,new LinearLayout.LayoutParams(-1,dp(c,40))); return bar;
    }
    static void actions(LinearLayout parent, View... views) {
        LinearLayout row = new LinearLayout(parent.getContext()); row.setOrientation(LinearLayout.HORIZONTAL);
        for (View view:views) row.addView(view,new LinearLayout.LayoutParams(0,-2,1)); parent.addView(row,new LinearLayout.LayoutParams(-1,-2));
    }
}
