package io.phoenixlink;

import android.content.Context;
import android.widget.*;
import java.util.function.Consumer;

final class TuningPanel extends LinearLayout {
    private final Switch enabled, mask;
    private float tone, structure, skin;
    private int iterations;
    TuningPanel(Context context, LinkConfig.Tuning original, Consumer<LinkConfig.Tuning> apply) {
        super(context); setOrientation(VERTICAL);
        tone = original.tone; structure = original.structure; skin = original.skin; iterations = original.iterations;
        enabled = new Switch(context); enabled.setText("Phoenix DLSS5 enabled"); enabled.setChecked(original.enabled); enabled.setTextColor(Ui.TEXT); addView(enabled,new LayoutParams(-1,Ui.dp(context,48)));
        Ui.slider(this,"Tone",0,2,tone,v -> tone=v); Ui.slider(this,"Structure",0,2,structure,v -> structure=v);
        Ui.slider(this,"Skin (-1 = default)",-1,2,skin,v -> skin=v);
        TextView count = Ui.text(context,"Iterations: " + iterations,14); addView(count);
        SeekBar passes = new SeekBar(context); passes.setMax(9); passes.setProgress(iterations-1);
        passes.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar b,int p,boolean user) { iterations=p+1; count.setText("Iterations: "+iterations); }
            public void onStartTrackingTouch(SeekBar b) { } public void onStopTrackingTouch(SeekBar b) { }
        }); addView(passes,new LayoutParams(-1,Ui.dp(context,40)));
        mask = new Switch(context); mask.setText("Automatic skin mask"); mask.setTextColor(Ui.TEXT); mask.setChecked(original.autoMask); addView(mask,new LayoutParams(-1,Ui.dp(context,48)));
        Button button = Ui.button(context,"Apply settings"); button.setOnClickListener(v -> apply.accept(new LinkConfig.Tuning(enabled.isChecked(),iterations,tone,structure,skin,mask.isChecked(),System.nanoTime())));
        Ui.primary(button);
        addView(button,new LayoutParams(-1,-2));
    }
}
