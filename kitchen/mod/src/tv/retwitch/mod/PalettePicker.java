package tv.retwitch.mod;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;

import java.util.Locale;

final class PalettePicker {
    private static final String[] PRESETS = {"#3861A7", "#00796B", "#AA5727", "#B14860", "#76549A"};

    private static String[] labels() {
        return new String[]{Strings.pick("Цвета обоев", "Wallpaper colours"), Strings.pick("Синий", "Blue"),
                Strings.pick("Бирюзовый", "Teal"), Strings.pick("Янтарный", "Amber"),
                Strings.pick("Розовый", "Rose"), Strings.pick("Сиреневый", "Lilac"),
                Strings.pick("Свой цвет", "Custom colour")};
    }

    private static int selection(String seed) {
        if (seed.isEmpty()) return 0;
        for (int i = 0; i < PRESETS.length; i++) if (seed.equals(PRESETS[i])) return i + 1;
        return PRESETS.length + 1;
    }

    static void row(SettingsScreen screen) {
        String seed = SystemPalette.savedSeed(screen.context);
        String[] labels = labels();
        int selected = selection(seed);
        String title = Strings.pick("Цветовая палитра", "Colour palette");
        screen.markNew("0.5.50-beta").action(title, selected == labels.length - 1 ? seed : labels[selected],
                () -> show(screen, title, labels, seed, selected));
    }

    private static void show(SettingsScreen screen, String title, String[] labels, String saved, int selected) {
        Context c = screen.context;
        int pad = Math.round(16 * c.getResources().getDisplayMetrics().density);
        int custom = labels.length - 1;
        ScrollView scroll = new ScrollView(c);
        LinearLayout content = new LinearLayout(c);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(pad, pad / 2, pad, pad / 2);
        scroll.addView(content);
        RadioGroup group = new RadioGroup(c);
        content.addView(group);
        for (int i = 0; i < labels.length; i++) {
            RadioButton radio = new RadioButton(c);
            radio.setId(View.generateViewId());
            radio.setText(labels[i]);
            radio.setMinHeight(pad * 3);
            if (i != custom) {
                int colour = i == 0 ? c.getColor(android.R.color.system_accent1_500) : PaletteTones.parse(PRESETS[i - 1]);
                GradientDrawable dot = new GradientDrawable();
                dot.setShape(GradientDrawable.OVAL);
                dot.setColor(colour);
                dot.setSize(pad * 3 / 2, pad * 3 / 2);
                radio.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, dot, null);
            }
            group.addView(radio);
        }
        EditText hex = new EditText(c);
        hex.setSingleLine();
        hex.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        hex.setHint("#RRGGBB");
        if (selected == custom) hex.setText(saved);
        content.addView(hex);
        content.setFocusableInTouchMode(true);
        group.setOnCheckedChangeListener((g, id) ->
                hex.setVisibility(id == group.getChildAt(custom).getId() ? View.VISIBLE : View.GONE));
        group.check(group.getChildAt(selected).getId());
        AlertDialog dialog = new AlertDialog.Builder(c).setTitle(title).setView(scroll)
                .setNegativeButton(Strings.cancel(), null).setPositiveButton(Strings.save(), null).create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            int index = group.indexOfChild(group.findViewById(group.getCheckedRadioButtonId()));
            String seed = index == 0 ? "" : index == custom ? hex.getText().toString().trim().toUpperCase(Locale.ROOT)
                    : PRESETS[index - 1];
            if (index == custom && PaletteTones.parse(seed) == 0) {
                hex.setError(Strings.pick("Введите цвет в формате #RRGGBB", "Enter a colour in #RRGGBB format"));
                hex.requestFocus();
                return;
            }
            if (!seed.equals(saved)) {
                if (seed.isEmpty()) Toggles.prefs(c).edit().remove(SystemPalette.SEED).apply();
                else Toggles.prefs(c).edit().putString(SystemPalette.SEED, seed).apply();
                screen.track(SystemPalette.SEED, true, saved, seed);
                screen.render(true);
            }
            dialog.dismiss();
        });
    }
}
