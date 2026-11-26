package tv.retwitch.mod;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Long press on the player's compressor button (feedback #42): the compressor and the audio filters right over the
 * player, changed while the stream plays. The same settings as Player -> Audio; every change re-creates the effect
 * through {@link AudioCompressor#setFilter} / {@code refresh}.
 */
final class AudioPanel {

    private AudioPanel() {
    }

    static void show(Context context) {
        if (Build.VERSION.SDK_INT < 28) {
            Toast.makeText(context, Strings.compressorUnsupported(), Toast.LENGTH_SHORT).show();
            return;
        }
        int pad = dp(context, 20);
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, dp(context, 8), pad, dp(context, 8));

        Switch compressor = new Switch(context);
        compressor.setText(Strings.compressorTalkback());
        compressor.setChecked(AudioCompressor.isActive());
        compressor.setEnabled(Toggles.on(Toggles.COMPRESSOR_BUTTON));
        compressor.setOnCheckedChangeListener((b, on) -> AudioCompressor.setActive(context, on));
        box.addView(compressor, row(context));

        Switch filters = new Switch(context);
        filters.setText(Toggles.AUDIO_FILTERS.label());
        filters.setChecked(Toggles.on(Toggles.AUDIO_FILTERS));
        box.addView(filters, row(context));

        List<View> sliders = new ArrayList<>();
        slider(context, box, sliders, Strings.audioBoostLabel(), AudioCompressor.BOOST, false);
        slider(context, box, sliders, Strings.audioBassLabel(), AudioCompressor.BASS, false);
        slider(context, box, sliders, Strings.audioTrebleLabel(), AudioCompressor.TREBLE, false);
        slider(context, box, sliders, Strings.audioVoiceLabel(), AudioCompressor.VOICE, true);
        slider(context, box, sliders, Strings.audioLimitLabel(), AudioCompressor.LIMIT, true);
        CompoundButton.OnCheckedChangeListener filtersChanged = (b, on) -> {
            Toggles.set(context, Toggles.AUDIO_FILTERS, on);
            AudioCompressor.refresh();
            for (View v : sliders) {
                v.setEnabled(on);
                v.setAlpha(on ? 1f : 0.4f);
            }
        };
        filters.setOnCheckedChangeListener(filtersChanged);
        filtersChanged.onCheckedChanged(filters, filters.isChecked());

        ScrollView scroll = new ScrollView(context);
        scroll.addView(box);
        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(Strings.pick("Звук", "Audio"))
                .setView(scroll)
                .setPositiveButton(android.R.string.ok, null)
                .create();
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setDimAmount(0.15f); // the video stays visible while the sound changes
        }
    }

    /** Drag the slider or tap its label to enter a level. */
    private static void slider(Context context, LinearLayout box, List<View> sliders, String name, String key,
            boolean percent) {
        int min = AudioCompressor.min(key), max = AudioCompressor.max(key);
        TextView label = new TextView(context);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(context, 10);
        box.addView(label, lp);
        SeekBar bar = new SeekBar(context);
        int level = AudioCompressor.filter(key);
        bar.setMax(max - min);
        bar.setProgress(level - min);
        label.setText(name + ": " + Strings.audioLevelLabel(level, percent));
        label.setOnClickListener(v -> SettingsScreen.editLevel(context, name, AudioCompressor.filter(key), min, max, value -> {
            AudioCompressor.setFilter(context, key, value);
            bar.setProgress(value - min);
        }));
        bar.setOnSeekBarChangeListener(SettingsScreen.sliderChanges(true,
                i -> label.setText(name + ": " + Strings.audioLevelLabel(min + i, percent)),
                i -> AudioCompressor.setFilter(context, key, min + i)));
        box.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 36)));
        sliders.add(label);
        sliders.add(bar);
    }

    private static LinearLayout.LayoutParams row(Context context) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 48));
        lp.gravity = Gravity.CENTER_VERTICAL;
        return lp;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
