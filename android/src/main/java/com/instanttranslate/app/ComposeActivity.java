package com.instanttranslate.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.common.model.RemoteModelManager;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.TranslateRemoteModel;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;

/** An English-to-Chinese composer that keeps the draft ready between shopping apps. */
public final class ComposeActivity extends Activity {
    private static final int INK = 0xff222b40;
    private static final int MUTED = 0xff697287;
    private static final int ACCENT = 0xff5b4ade;
    private static final String DRAFT_PREFS = "shopping_composer";
    private Translator translator;
    private SharedPreferences drafts;
    private EditText input;
    private TextView output;
    private TextView status;
    private TextView translate;
    private TextView copy;
    private TextView clear;
    private LinearLayout starters;
    private ScrollView contentScroll;
    private ProgressBar progress;
    private String translated = "";
    private String translatedSource = "";
    private String pendingSource = "";
    private boolean working;
    private boolean destroyed;
    private boolean modelDownloaded;
    private boolean taskReturned;
    private int requestGeneration;

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private GradientDrawable shape(int fill, int radius, int border) {
        GradientDrawable result = new GradientDrawable();
        result.setColor(fill);
        result.setCornerRadius(dp(radius));
        result.setStroke(dp(1), border);
        return result;
    }

    private TextView label(String value, int size, int color, boolean bold) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        text.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        text.setIncludeFontPadding(false);
        return text;
    }

    private TextView button(String text, int background, int foreground, View.OnClickListener listener) {
        TextView view = label(text, 15, foreground, true);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(12), dp(10), dp(12), dp(10));
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(0x225b4ade),
            shape(background, 14, background), null));
        view.setOnClickListener(listener);
        return view;
    }

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(0xfff7f8fc);
        getWindow().setNavigationBarColor(0xfff7f8fc);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        drafts = getSharedPreferences(DRAFT_PREFS, MODE_PRIVATE);
        translator = Translation.getClient(new TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.CHINESE)
            .build());

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setFocusableInTouchMode(true);
        page.setBackgroundColor(0xfff7f8fc);
        setContentView(page);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(16), dp(6), dp(20), dp(4));
        TextView close = label("‹", 32, ACCENT, false);
        close.setGravity(Gravity.CENTER);
        close.setContentDescription("Return to previous app");
        close.setOnClickListener(v -> finish());
        header.addView(close, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout title = new LinearLayout(this);
        title.setOrientation(LinearLayout.VERTICAL);
        title.addView(label("Write in Chinese", 21, INK, true));
        TextView subtitle = label("Instant Translate · " + getString(R.string.app_name).replace("Instant Translate ", ""), 11, MUTED, false);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(-1, -2);
        subtitleParams.topMargin = dp(4);
        title.addView(subtitle, subtitleParams);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        page.addView(header);

        contentScroll = new ScrollView(this);
        contentScroll.setFillViewport(true);
        contentScroll.setClipToPadding(false);
        page.addView(contentScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(12), dp(20), dp(16));
        contentScroll.addView(content);

        LinearLayout inputHeading = new LinearLayout(this);
        inputHeading.setGravity(Gravity.CENTER_VERTICAL);
        inputHeading.addView(label("ENGLISH", 11, ACCENT, true), new LinearLayout.LayoutParams(0, -2, 1));
        clear = label("Clear", 13, MUTED, true);
        clear.setGravity(Gravity.CENTER);
        clear.setPadding(dp(12), 0, dp(4), 0);
        clear.setOnClickListener(v -> {
            if (working) return;
            translated = "";
            translatedSource = "";
            input.setText("");
            input.setHint("Ask a seller a question, or enter product keywords…");
            output.setText("Your Chinese translation appears here.");
            output.setTextColor(MUTED);
            status.setText("Write a message or choose a quick start.");
            saveDraft();
        });
        inputHeading.addView(clear, new LinearLayout.LayoutParams(-2, dp(40)));
        content.addView(inputHeading);
        input = new EditText(this);
        input.setId(R.id.composer_input);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
            | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setTextSize(17);
        input.setTextColor(INK);
        input.setHintTextColor(0xff9ba2b1);
        input.setHint("Ask a seller a question, or enter product keywords…");
        input.setGravity(Gravity.TOP);
        input.setMinLines(3);
        input.setMaxLines(6);
        input.setPadding(dp(16), dp(14), dp(16), dp(14));
        input.setBackground(shape(Color.WHITE, 16, 0xffdfe3ed));
        content.addView(input, new LinearLayout.LayoutParams(-1, -2));

        starters = new LinearLayout(this);
        starters.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams starterParams = new LinearLayout.LayoutParams(-1, -2);
        starterParams.topMargin = dp(12);
        content.addView(starters, starterParams);
        starters.addView(label("QUICK STARTS", 10, MUTED, true));
        addStarterRow("In stock?", "Is this item in stock?", "Dimensions?", "What are the dimensions?");
        addStarterRow("Ship to Singapore?", "Can you ship to Singapore?", "Real photo?", "Can you send a real photo?");
        TextView productSearch = button("Search for a product…", Color.WHITE, ACCENT, v -> {
            if (working || !input.getText().toString().trim().isEmpty()) return;
            input.setHint("Product keywords, e.g. waterproof desk mat");
            input.requestFocus();
            ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
        });
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(-1, -2);
        searchParams.topMargin = dp(8);
        starters.addView(productSearch, searchParams);

        TextView outputLabel = label("CHINESE", 11, ACCENT, true);
        LinearLayout.LayoutParams outputLabelParams = new LinearLayout.LayoutParams(-1, -2);
        outputLabelParams.topMargin = dp(22);
        content.addView(outputLabel, outputLabelParams);
        output = label("Your Chinese translation appears here.", 19, MUTED, false);
        output.setTextIsSelectable(true);
        output.setMinHeight(dp(88));
        output.setPadding(dp(16), dp(14), dp(16), dp(14));
        output.setBackground(shape(Color.WHITE, 16, 0xffdfe3ed));
        LinearLayout.LayoutParams outputParams = new LinearLayout.LayoutParams(-1, -2);
        outputParams.topMargin = dp(10);
        content.addView(output, outputParams);
        ImageView credit = new ImageView(this);
        credit.setImageResource(R.drawable.google_translate_badge_short);
        credit.setContentDescription("Translation powered by Google Translate");
        credit.setScaleType(ImageView.ScaleType.FIT_START);
        LinearLayout.LayoutParams creditParams = new LinearLayout.LayoutParams(dp(100), dp(22));
        creditParams.topMargin = dp(7);
        content.addView(credit, creditParams);

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleSmall);
        progress.setIndeterminateTintList(ColorStateList.valueOf(ACCENT));
        progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(dp(18), dp(18));
        progressParams.rightMargin = dp(8);
        statusRow.addView(progress, progressParams);
        status = label("Write a message or choose a quick start.", 12, MUTED, false);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        status.setLineSpacing(dp(2), 1);
        statusRow.addView(status, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, -2);
        statusParams.topMargin = dp(12);
        content.addView(statusRow, statusParams);

        // Keep actions visible while the editor scrolls or the keyboard is open.
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);
        actions.setPadding(dp(20), dp(10), dp(20), dp(12));
        actions.setBackgroundColor(0xfff7f8fc);
        copy = button("Copy & return", ACCENT, Color.WHITE, v -> copy());
        actions.addView(copy, new LinearLayout.LayoutParams(-1, dp(48)));
        translate = button("Translate with Google", ACCENT, Color.WHITE, v -> translate());
        actions.addView(translate, new LinearLayout.LayoutParams(-1, dp(48)));
        TextView note = label("On-device translation · First use needs internet", 11, MUTED, false);
        note.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams noteParams = new LinearLayout.LayoutParams(-1, -2);
        noteParams.topMargin = dp(8);
        actions.addView(note, noteParams);
        page.addView(actions, new LinearLayout.LayoutParams(-1, -2));

        String draft = savedInstanceState == null ? drafts.getString("input", "") : savedInstanceState.getString("input", "");
        translated = savedInstanceState == null ? drafts.getString("translated", "") : savedInstanceState.getString("translated", "");
        translatedSource = savedInstanceState == null ? drafts.getString("translatedSource", "") : savedInstanceState.getString("translatedSource", "");
        input.setText(draft);
        input.setSelection(input.length());
        if (!translated.isEmpty()) {
            output.setText(translated);
            output.setTextColor(matchesCurrentDraft() ? INK : MUTED);
            status.setText(matchesCurrentDraft() ? "Ready to copy. Paste into Taobao after returning." : "Message edited. Translate again to update the Chinese text.");
        }
        updateActions();
        if (savedInstanceState == null) page.requestFocus();
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                if (working && !currentSource().equals(pendingSource)) {
                    status.setText("Message edited. Translate again when this translation finishes.");
                } else if (!working) {
                    if (!translated.isEmpty()) {
                        output.setTextColor(matchesCurrentDraft() ? INK : MUTED);
                        status.setText(matchesCurrentDraft() ? "Ready to copy. Paste into Taobao after returning." : "Message edited. Translate again to update the Chinese text.");
                    } else {
                        status.setText(currentSource().isEmpty() ? "Write a message or choose a quick start." : "Ready to translate on your device.");
                    }
                }
                updateActions();
            }
            @Override public void afterTextChanged(Editable text) { }
        });

        RemoteModelManager.getInstance().isModelDownloaded(new TranslateRemoteModel.Builder(TranslateLanguage.CHINESE).build())
            .addOnSuccessListener(downloaded -> { if (!destroyed) modelDownloaded = modelDownloaded || downloaded; });
        if (savedInstanceState != null && savedInstanceState.getBoolean("working")) {
            String source = savedInstanceState.getString("pendingSource", "");
            if (!source.isEmpty()) translateSource(source);
        }
    }

    private void addStarterRow(String firstLabel, String firstSource, String secondLabel, String secondSource) {
        LinearLayout row = new LinearLayout(this);
        TextView first = starter(firstLabel, firstSource);
        TextView second = starter(secondLabel, secondSource);
        LinearLayout.LayoutParams firstParams = new LinearLayout.LayoutParams(0, -2, 1);
        firstParams.rightMargin = dp(8);
        row.addView(first, firstParams);
        row.addView(second, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
        rowParams.topMargin = dp(8);
        starters.addView(row, rowParams);
    }

    private TextView starter(String label, String source) {
        TextView view = button(label, 0xffece9ff, ACCENT, v -> {
            if (working || !currentSource().isEmpty()) return;
            input.setText(source);
            input.setSelection(input.length());
            translate();
        });
        view.setTextSize(13);
        view.setMinHeight(dp(44));
        return view;
    }

    private String currentSource() { return input.getText().toString().trim(); }

    private boolean matchesCurrentDraft() {
        return !translated.isEmpty() && currentSource().equals(translatedSource);
    }

    private void updateActions() {
        boolean ready = !working && matchesCurrentDraft();
        copy.setVisibility(ready ? View.VISIBLE : View.GONE);
        copy.setEnabled(ready);
        translate.setVisibility(ready ? View.GONE : View.VISIBLE);
        translate.setEnabled(!working && !currentSource().isEmpty());
        translate.setAlpha(translate.isEnabled() ? 1f : .55f);
        translate.setText(working ? "Translating with Google…" : "Translate with Google");
        clear.setEnabled(!working && (!currentSource().isEmpty() || !translated.isEmpty()));
        clear.setAlpha(clear.isEnabled() ? 1f : .4f);
        progress.setVisibility(working ? View.VISIBLE : View.GONE);
        starters.setVisibility(!working && currentSource().isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void translate() {
        String source = currentSource();
        if (source.isEmpty() || working) return;
        ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
            .hideSoftInputFromWindow(input.getWindowToken(), 0);
        input.clearFocus();
        translateSource(source);
    }

    private void translateSource(String source) {
        if (destroyed || working) return;
        pendingSource = source;
        working = true;
        int generation = ++requestGeneration;
        updateActions();
        String shoppingPhrase = commonShoppingPhrase(source);
        if (shoppingPhrase != null) {
            showTranslation(source, shoppingPhrase, generation);
            return;
        }
        status.setText(modelDownloaded ? "Translating on your device…" : "Preparing Chinese language pack. First use may take a moment…");
        translator.downloadModelIfNeeded(new DownloadConditions.Builder().build())
            .addOnSuccessListener(unused -> {
                if (!isCurrent(generation)) return;
                modelDownloaded = true;
                status.setText(currentSource().equals(source) ? "Translating on your device…" : "Message edited. Translate again when this translation finishes.");
                translator.translate(source)
                    .addOnSuccessListener(result -> showTranslation(source, result, generation))
                    .addOnFailureListener(error -> fail(generation));
            })
            .addOnFailureListener(error -> fail(generation));
    }

    private boolean isCurrent(int generation) { return !destroyed && generation == requestGeneration; }

    private String commonShoppingPhrase(String source) {
        String value = source.toLowerCase(java.util.Locale.ROOT)
            .replaceAll("\\s+", " ").replaceAll("[?.!]+$", "").trim();
        switch (value) {
            case "is this item in stock":
            case "is this product in stock":
            case "do you have this in stock":
                return "请问这件商品有现货吗？";
            case "can you ship it to singapore":
            case "can you ship to singapore":
            case "do you ship to singapore":
                return "请问可以寄到新加坡吗？";
            case "can you send a real photo":
            case "can you send me a real photo":
                return "可以发一张商品实拍照片吗？";
            case "what is the size":
            case "what are the dimensions":
                return "请问商品的尺寸是多少？";
            default:
                return null;
        }
    }

    private void showTranslation(String source, String result, int generation) {
        if (!isCurrent(generation)) return;
        pendingSource = "";
        working = false;
        translatedSource = source;
        translated = result == null ? "" : result.trim();
        output.setText(translated.isEmpty() ? "No translation returned. Try again." : translated);
        output.setTextColor(matchesCurrentDraft() ? INK : MUTED);
        status.setText(translated.isEmpty() ? "No translation returned. Try again." : matchesCurrentDraft()
            ? "Ready to copy. Paste into Taobao after returning." : "Message edited. Translate again to update the Chinese text.");
        updateActions();
        saveDraft();
        contentScroll.post(() -> {
            if (!destroyed && !working) contentScroll.smoothScrollTo(0, Math.max(0, output.getTop() - dp(12)));
        });
    }

    private void fail(int generation) {
        if (!isCurrent(generation)) return;
        pendingSource = "";
        working = false;
        status.setText("Couldn't translate. First use needs internet to download the language pack. Try again.");
        updateActions();
    }

    private void saveDraft() {
        if (drafts == null || input == null) return;
        drafts.edit().putString("input", input.getText().toString())
            .putString("translated", translated).putString("translatedSource", translatedSource).apply();
    }

    private void copy() {
        if (working || !matchesCurrentDraft()) return;
        ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
            .setPrimaryClip(ClipData.newPlainText("Chinese shopping message", translated));
        Toast.makeText(this, "Copied. Paste it into Taobao.", Toast.LENGTH_SHORT).show();
        saveDraft();
        returnToPreviousApp();
        finish();
    }

    private void returnToPreviousApp() {
        if (taskReturned) return;
        taskReturned = true;
        moveTaskToBack(true);
    }

    @Override public void finish() {
        if (getIntent().getBooleanExtra("return_to_app", false)) returnToPreviousApp();
        super.finish();
    }

    @Override protected void onResume() {
        super.onResume();
        OverlayService.onComposerVisible(true);
    }

    @Override protected void onPause() {
        saveDraft();
        OverlayService.onComposerVisible(false);
        super.onPause();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("input", input.getText().toString());
        state.putString("translated", translated);
        state.putString("translatedSource", translatedSource);
        state.putBoolean("working", working);
        state.putString("pendingSource", pendingSource);
        super.onSaveInstanceState(state);
    }

    @Override protected void onDestroy() {
        destroyed = true;
        requestGeneration++;
        if (translator != null) translator.close();
        super.onDestroy();
    }
}
