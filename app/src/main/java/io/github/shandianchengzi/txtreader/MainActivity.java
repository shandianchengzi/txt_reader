package io.github.shandianchengzi.txtreader;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.text.Layout;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.provider.OpenableColumns;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity implements SpeechService.Listener {
    private static final int PICK_TXT = 10;
    private static final int BACKUP_EXPORT = 11, BACKUP_IMPORT = 12;
    private static final int MAX_BYTES = 32 * 1024 * 1024;
    private static final String BATTERY_REMIND_HIDDEN = "battery_remind_hidden";
    private static final String BATTERY_REMIND_LAST = "battery_remind_last";
    private static final long BATTERY_REMIND_INTERVAL_MS = 24L * 60L * 60L * 1000L;
    private static final Pattern HEAD = Pattern.compile(
        "(?im)^[ \\t\\u3000]*(第[一二三四五六七八九十百千万零〇两0-9]{1,12}[章节回卷部篇集][^\\r\\n]{0,35}|chapter[ \\t]+[0-9ivxlcdm]+[^\\r\\n]{0,45}|序章|楔子|前言|尾声|后记|番外[^\\r\\n]{0,25})[ \\t\\u3000]*$");

    private static class Book {
        String uri, name;
        int chapter, scroll, audioOffset = -1;
        Book(String u, String n) { uri = u; name = n; }
    }
    private static class Chapter {
        String title;
        int start, end;
        Chapter(String t, int s) { title = t; start = s; }
    }

    private final ArrayList<Book> library = new ArrayList<>();
    private final ArrayList<Chapter> chapters = new ArrayList<>();
    private Book book;
    private String fullText = "";
    private LinearLayout root;
    private TextView title, page;
    private ScrollView scroller;
    private Button speechButton;
    private int fontSize = 19, touchedOffset;
    private BackgroundColorSpan speechHighlight;
    private float speed = 1.0f;
    private boolean resumed;
    private boolean shownBatteryTipThisSession;
    private final android.os.Handler saves = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable saveLater = this::save;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        android.content.SharedPreferences prefs = getPreferences(MODE_PRIVATE);
        fontSize = prefs.getInt("font", 19);
        speed = prefs.getFloat("speed", 1.0f);
        try {
            JSONArray a = new JSONArray(prefs.getString("books", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Book b = new Book(o.getString("uri"), o.getString("name"));
                b.chapter = o.optInt("chapter", 0);
                b.scroll = o.optInt("scroll", 0);
                b.audioOffset = o.optInt("audioOffset", -1);
                library.add(b);
            }
        } catch (Exception ignored) {}

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(250, 248, 243));
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets i = insets.getInsets(WindowInsets.Type.systemBars());
                v.setPadding(i.left, i.top, i.right, i.bottom);
            } else {
                v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        setContentView(root);

        LinearLayout tools = row();
        button(tools, "导入TXT", this::pickFile);
        button(tools, "书架", this::showLibrary);
        button(tools, "目录", this::showChapters);
        button(tools, "字号", this::setFontSize);
        button(tools, "数据", this::showDataMenu);

        title = new TextView(this);
        title.setPadding(dp(14), dp(11), dp(14), dp(11));
        title.setTextSize(16);
        title.setTextColor(Color.DKGRAY);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        root.addView(title);

        scroller = new ScrollView(this);
        scroller.setFillViewport(true);
        page = new TextView(this);
        page.setTextColor(Color.rgb(46, 43, 39));
        page.setTextSize(fontSize);
        page.setLineSpacing(dp(5), 1.18f);
        page.setPadding(dp(19), dp(16), dp(19), dp(30));
        // Let ScrollView handle swipes; TextView's click callback only handles taps.
        page.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_UP)
                touchedOffset = page.getOffsetForPosition(event.getX(), event.getY());
            return false;
        });
        page.setOnClickListener(v -> beginSpeechAt(touchedOffset));
        scroller.addView(page);
        scroller.setOnScrollChangeListener((v, x, y, oldX, oldY) -> {
            updateProgress();
            saves.removeCallbacks(saveLater);
            saves.postDelayed(saveLater, 600);
        });
        root.addView(scroller, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout nav = row();
        button(nav, "上章", () -> moveChapter(-1));
        button(nav, "下章", () -> moveChapter(1));
        speechButton = button(nav, "朗读", this::toggleSpeech);
        button(nav, "语速", this::setSpeed);

        page.setText("点击「导入TXT」选择手机上的小说文件。\n\n支持离线阅读、自动分章节与系统离线语音朗读。");
        title.setText("TXT 阅读器");
        if (!library.isEmpty()) load(library.get(0));
    }

    private int dp(int n) { return (int) (n * getResources().getDisplayMetrics().density + 0.5f); }
    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(r);
        return r;
    }
    private Button button(LinearLayout row, String label, Runnable action) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(0, 0, 0, 0);
        row.addView(b, new LinearLayout.LayoutParams(0, dp(50), 1));
        b.setOnClickListener(v -> action.run());
        return b;
    }
    private void message(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    private void pickFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, PICK_TXT);
    }

    @Override protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (result == RESULT_OK && data != null && data.getData() != null) {
            if (req == BACKUP_EXPORT) { exportBackup(data.getData()); return; }
            if (req == BACKUP_IMPORT) { confirmImport(data.getData()); return; }
        }
        if (req != PICK_TXT || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        String name = uri.getLastPathSegment();
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int col = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (col >= 0) name = c.getString(col);
            }
        } catch (Exception ignored) {}
        if (name == null) name = "未命名小说.txt";
        if (!name.toLowerCase(Locale.ROOT).endsWith(".txt")) {
            message("请选择 .txt 小说文件"); return;
        }
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {}
        for (Book b : library) {
            if (b.uri.equals(uri.toString())) { load(b); return; }
        }
        Book b = new Book(uri.toString(), name);
        library.add(0, b);
        save();
        load(b);
    }

    private void load(Book b) {
        if (book != null && !book.uri.equals(b.uri)) stopSpeech();
        savePosition();
        book = b;
        chapters.clear();
        fullText = "";
        title.setText("加载中 · " + b.name);
        page.setText("正在读取 TXT…");
        new Thread(() -> {
            try (InputStream in = getContentResolver().openInputStream(Uri.parse(b.uri));
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                if (in == null) throw new IllegalStateException("无法打开文件");
                byte[] buffer = new byte[16384];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    if (out.size() + n > MAX_BYTES) throw new IllegalStateException("文件超过 32 MiB 限制");
                    out.write(buffer, 0, n);
                }
                String text = decode(out.toByteArray()).replace("\r\n", "\n").replace('\r', '\n');
                runOnUiThread(() -> {
                    if (book != b) return;
                    fullText = text;
                    splitChapters();
                    b.chapter = Math.max(0, Math.min(b.chapter, chapters.size() - 1));
                    displayChapter(b.scroll);
                    // Recover the most recent position if playback continued during load.
                    syncFromBackground();
                    if (resumed) SpeechService.listen(this);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (book != b) return;
                    book = null;
                    fullText = "";
                    chapters.clear();
                    title.setText("读取失败");
                    page.setText("无法打开该小说，请重新导入。\n" + e.getMessage());
                });
            }
        }).start();
    }

    private static String decode(byte[] b) {
        if (b.length >= 3 && (b[0] & 255) == 239 && (b[1] & 255) == 187 && (b[2] & 255) == 191)
            return new String(b, 3, b.length - 3, StandardCharsets.UTF_8);
        if (b.length >= 2 && (b[0] & 255) == 255 && (b[1] & 255) == 254)
            return new String(b, 2, b.length - 2, StandardCharsets.UTF_16LE);
        if (b.length >= 2 && (b[0] & 255) == 254 && (b[1] & 255) == 255)
            return new String(b, 2, b.length - 2, StandardCharsets.UTF_16BE);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(b)).toString();
        } catch (CharacterCodingException e) {
            return new String(b, Charset.forName("GB18030"));
        }
    }

    private void splitChapters() {
        chapters.clear();
        Matcher m = HEAD.matcher(fullText);
        while (m.find()) {
            if (chapters.isEmpty() && m.start() > 0) chapters.add(new Chapter("开篇", 0));
            chapters.add(new Chapter(m.group(1).trim(), m.start()));
        }
        if (chapters.isEmpty()) chapters.add(new Chapter("全文", 0));
        for (int i = 0; i < chapters.size(); i++) {
            Chapter c = chapters.get(i);
            c.end = i + 1 < chapters.size() ? chapters.get(i + 1).start : fullText.length();
        }
    }

    private void displayChapter(int scrollY) {
        if (book == null || chapters.isEmpty()) return;
        Chapter c = chapters.get(book.chapter);
        scroller.scrollTo(0, 0);
        speechHighlight = null;
        page.setText(new SpannableStringBuilder(fullText.substring(c.start, c.end).trim()),
                TextView.BufferType.SPANNABLE);
        Book expectedBook = book;
        int expectedChapter = book.chapter;
        scroller.post(() -> {
            if (book == expectedBook && book.chapter == expectedChapter) {
                scroller.scrollTo(0, scrollY);
                updateProgress();
            }
        });
        updateProgress();
        save();
    }

    // Position is derived from the actual first visible text line, not the chapter start.
    private int topVisibleOffset() {
        Layout layout = page.getLayout();
        if (layout == null) return 0;
        int y = Math.max(0, scroller.getScrollY() - page.getTotalPaddingTop());
        return layout.getLineStart(layout.getLineForVertical(y));
    }

    private int visibleParagraphStart() {
        String text = page.getText().toString();
        int offset = Math.min(topVisibleOffset(), text.length());
        int start = text.lastIndexOf('\n', Math.max(0, offset - 1)) + 1;
        while (start < text.length() && Character.isWhitespace(text.charAt(start))) start++;
        return start;
    }

    private void updateProgress() {
        if (book == null || chapters.isEmpty() || fullText.isEmpty()) return;
        Chapter c = chapters.get(book.chapter);
        int offset = Math.min(topVisibleOffset(), page.length());
        int pos = c.start + offset;
        if (book.chapter == chapters.size() - 1
                && scroller.getHeight() > 0
                && scroller.getScrollY() >= page.getHeight() - scroller.getHeight() - dp(2))
            pos = fullText.length();
        int percent = (int) (100L * Math.min(pos, fullText.length()) / fullText.length());
        title.setText(c.title + " · " + percent + "% · " + (book.chapter + 1) + "/" + chapters.size());
        book.scroll = scroller.getScrollY();
    }

    private void savePosition() {
        if (book != null && !chapters.isEmpty()) book.scroll = scroller.getScrollY();
        save();
    }
    private void save() {
        JSONArray a = new JSONArray();
        try {
            for (Book b : library) {
                JSONObject o = new JSONObject();
                o.put("uri", b.uri); o.put("name", b.name);
                o.put("chapter", b.chapter); o.put("scroll", b.scroll);
                o.put("audioOffset", b.audioOffset);
                a.put(o);
            }
        } catch (Exception ignored) {}
        getPreferences(MODE_PRIVATE).edit().putString("books", a.toString())
                .putInt("font", fontSize).putFloat("speed", speed).apply();
    }

    private void changeChapter(int index) {
        if (book == null || index < 0 || index >= chapters.size()) return;
        stopSpeech();
        savePosition();
        book.chapter = index;
        book.scroll = 0;
        book.audioOffset = -1;
        displayChapter(0);
    }
    private void moveChapter(int direction) {
        if (book == null || chapters.isEmpty()) { message("请先导入小说"); return; }
        int next = book.chapter + direction;
        if (next < 0 || next >= chapters.size()) { message("没有更多章节"); return; }
        changeChapter(next);
    }
    private void showChapters() {
        if (book == null || chapters.isEmpty()) { message("请先导入小说"); return; }
        String[] names = new String[chapters.size()];
        for (int i = 0; i < names.length; i++) names[i] = chapters.get(i).title;
        new AlertDialog.Builder(this).setTitle("章节目录").setSingleChoiceItems(names, book.chapter,
                (dialog, which) -> { dialog.dismiss(); changeChapter(which); }).setNegativeButton("取消", null).show();
    }
    private void showLibrary() {
        if (library.isEmpty()) { message("书架为空，点击导入TXT"); return; }
        String[] names = new String[library.size()];
        for (int i = 0; i < names.length; i++) names[i] = library.get(i).name;
        new AlertDialog.Builder(this).setTitle("我的书架")
                .setItems(names, (d, i) -> { savePosition(); load(library.get(i)); })
                .setNeutralButton("移除图书", (d, w) -> chooseRemoval()).show();
    }
    private void chooseRemoval() {
        String[] names = new String[library.size()];
        for (int i = 0; i < names.length; i++) names[i] = library.get(i).name;
        new AlertDialog.Builder(this).setTitle("从书架移除（不删除原文件）")
                .setItems(names, (d, i) -> {
                    Book removed = library.remove(i);
                    if (removed == book) {
                        stopSpeech(); book = null; chapters.clear(); fullText = "";
                        page.setText("点击「导入TXT」选择小说"); title.setText("TXT 阅读器");
                    }
                    save();
                }).setNegativeButton("取消", null).show();
    }
    private void setFontSize() {
        String[] choices = {"16", "18", "19", "21", "24", "28"};
        new AlertDialog.Builder(this).setTitle("正文字号")
                .setItems(choices, (d, i) -> {
                    fontSize = Integer.parseInt(choices[i]);
                    page.setTextSize(fontSize); save();
                }).show();
    }
    private void setSpeed() {
        String[] labels = {"0.8×", "1.0×", "1.2×", "1.5×", "2.0×"};
        float[] values = {0.8f, 1f, 1.2f, 1.5f, 2f};
        new AlertDialog.Builder(this).setTitle("朗读语速")
                .setItems(labels, (d, i) -> {
                    speed = values[i];
                    if (SpeechService.isPlaying(book == null ? "" : book.uri))
                        message("语速将在下一次开始朗读时生效");
                    save();
                }).show();
    }

    private void toggleSpeech() {
        if (book == null || chapters.isEmpty()) { message("请先导入小说"); return; }
        if (SpeechService.isPlaying(book.uri)) {
            startService(new Intent(this, SpeechService.class).setAction(SpeechService.PAUSE));
        } else if (SpeechService.isActiveFor(book.uri)) {
            startService(new Intent(this, SpeechService.class).setAction(SpeechService.RESUME));
            maybeShowBatteryReminder();
        } else beginSpeechAt(visibleParagraphStart());
    }

    private void beginSpeechAt(int offset) {
        if (book == null || chapters.isEmpty()) return;
        Intent intent = new Intent(this, SpeechService.class);
        intent.setAction(SpeechService.PLAY);
        intent.putExtra("uri", book.uri);
        intent.putExtra("name", book.name);
        intent.putExtra("chapter", book.chapter);
        intent.putExtra("offset", Math.max(0, Math.min(offset, page.length())));
        intent.putExtra("speed", speed);
        if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
        else startService(intent);
        if (android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 50);
        }
        speechButton.setText("暂停");
        maybeShowBatteryReminder();
    }

    /** Only the Android-wide optimization allowlist is detectable; OEM switches are not. */
    private boolean systemBatteryExempt() {
        if (android.os.Build.VERSION.SDK_INT < 23) return true;
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    private void maybeShowBatteryReminder() {
        if (shownBatteryTipThisSession || systemBatteryExempt()) return;
        android.content.SharedPreferences preferences = getPreferences(MODE_PRIVATE);
        if (preferences.getBoolean(BATTERY_REMIND_HIDDEN, false)) return;
        long now = System.currentTimeMillis();
        long previous = preferences.getLong(BATTERY_REMIND_LAST, 0L);
        if (previous > 0 && now >= previous
                && now - previous < BATTERY_REMIND_INTERVAL_MS) return;
        shownBatteryTipThisSession = true;
        preferences.edit().putLong(BATTERY_REMIND_LAST, now).apply();
        showBatteryGuide(false);
    }

    private void openAppPowerSettings() {
        // This is the portable entry point. Each manufacturer exposes its own battery menu.
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName()));
        try {
            startActivity(intent);
        } catch (Exception ex) {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (Exception ignored) {
                message("请手动进入系统设置 → 应用 → TXT 阅读器 → 电池／耗电管理");
            }
        }
    }

    private void openBatteryOptimizationSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        } catch (Exception ex) {
            openAppPowerSettings();
        }
    }

    private void showBatteryGuide(boolean manual) {
        String androidState = systemBatteryExempt()
                ? "Android 标准省电优化：本应用已获豁免。"
                : "Android 标准省电优化：本应用尚未获豁免。";
        String information = androidState
                + "\n\n为保证锁屏、切换应用后持续朗读，请进入手机设置，找到"
                + "TXT 阅读器的「电池／耗电管理」，选择「允许后台活动」"
                + "「允许后台高耗电」或「不限制／无限制」（具体名称因手机而异）。"
                + "\n\n注意：手机厂商的「允许后台活动」可能是单独的开关，"
                + "Android 没有统一接口检测，因此上面的结果不代表厂商开关状态。"
                + "\n\n开启可能增加耗电量；不修改设置也可以继续朗读。";
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle("允许后台活动，保持连续朗读")
                .setMessage(information)
                .setPositiveButton("打开应用设置", (d, w) -> openAppPowerSettings())
                .setNegativeButton("暂不设置", null);
        if (manual) {
            builder.setNeutralButton("系统省电优化列表",
                    (d, w) -> openBatteryOptimizationSettings());
        } else {
            CheckBox neverAgain = new CheckBox(this);
            neverAgain.setText("我已设置允许后台活动，不再提醒");
            neverAgain.setTextSize(13);
            neverAgain.setPadding(dp(16), 0, dp(12), dp(4));
            builder.setView(neverAgain);
            AlertDialog dialog = builder.create();
            dialog.setOnDismissListener(d -> {
                if (neverAgain.isChecked())
                    getPreferences(MODE_PRIVATE).edit()
                            .putBoolean(BATTERY_REMIND_HIDDEN, true).apply();
            });
            dialog.show();
            return;
        }
        builder.show();
    }

    private void stopSpeech() {
        Intent intent = new Intent(this, SpeechService.class).setAction(SpeechService.STOP);
        if (book != null && SpeechService.isActiveFor(book.uri)) startService(intent);
        clearHighlight();
        if (speechButton != null) speechButton.setText("朗读");
    }

    private void clearHighlight() {
        if (speechHighlight != null && page.getText() instanceof Spannable) {
            ((Spannable) page.getText()).removeSpan(speechHighlight);
            speechHighlight = null;
        }
    }

    private void highlight(int start, int end) {
        if (!(page.getText() instanceof Spannable)) return;
        Spannable span = (Spannable) page.getText();
        clearHighlight();
        int from = Math.max(0, Math.min(start, span.length()));
        int to = Math.max(from, Math.min(end, span.length()));
        if (from == to) return;
        speechHighlight = new BackgroundColorSpan(Color.rgb(241, 236, 222));
        span.setSpan(speechHighlight, from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        Layout layout = page.getLayout();
        if (layout == null) return;
        int line = layout.getLineForOffset(from);
        int top = page.getTotalPaddingTop() + layout.getLineTop(line);
        int bottom = page.getTotalPaddingTop() + layout.getLineBottom(line);
        int viewTop = scroller.getScrollY();
        if (top < viewTop || bottom > viewTop + scroller.getHeight() - dp(18))
            scroller.smoothScrollTo(0, Math.max(0, top - dp(26)));
    }

    @Override public void onSpeech(String uri, int chapter, int start, int end, boolean playing) {
        if (book == null || !book.uri.equals(uri) || chapters.isEmpty()) return;
        // An asynchronous STOP callback must never undo manual chapter navigation.
        if (!playing && !SpeechService.isActiveFor(uri)) {
            speechButton.setText("朗读");
            clearHighlight();
            return;
        }
        if (chapter >= 0 && chapter < chapters.size() && book.chapter != chapter) {
            book.chapter = chapter;
            book.scroll = 0;
            displayChapter(0);
        }
        book.audioOffset = start;
        if (playing) {
            speechButton.setText("暂停");
            highlight(start, end);
        } else {
            speechButton.setText(SpeechService.isActiveFor(uri) ? "继续" : "朗读");
            clearHighlight();
        }
        save();
    }

    private void syncFromBackground() {
        if (book == null || chapters.isEmpty()) return;
        String uri = book.uri;
        try {
            JSONArray a = new JSONArray(getPreferences(MODE_PRIVATE).getString("books", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject b = a.getJSONObject(i);
                if (!uri.equals(b.optString("uri"))) continue;
                int next = b.optInt("chapter", book.chapter);
                int offset = b.optInt("audioOffset", -1);
                boolean changed = next >= 0 && next < chapters.size() && next != book.chapter;
                boolean moved = offset >= 0 && offset != book.audioOffset;
                if (offset >= 0) book.audioOffset = offset;
                if (changed) {
                    book.chapter = next;
                    book.scroll = 0;
                    displayChapter(0);
                }
                if (offset >= 0 && (changed || moved)) scrollToOffset(offset);
                break;
            }
        } catch (Exception ignored) {}
    }

    private void scrollToOffset(int offset) {
        page.post(() -> {
            Layout layout = page.getLayout();
            if (layout != null) {
                int line = layout.getLineForOffset(Math.max(0, Math.min(offset, page.length())));
                scroller.scrollTo(0, Math.max(0, page.getTotalPaddingTop()
                        + layout.getLineTop(line) - dp(24)));
                updateProgress();
            }
        });
    }

    private void showDataMenu() {
        new AlertDialog.Builder(this).setTitle("本地书架与进度")
                .setItems(new String[]{"导出完整备份（含小说）", "从 ZIP 备份恢复", "后台朗读设置"},
                    (dialog, choice) -> {
                        if (choice == 0) {
                            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                            i.addCategory(Intent.CATEGORY_OPENABLE);
                            i.setType("application/zip");
                            i.putExtra(Intent.EXTRA_TITLE, "txt-reader-backup.zip");
                            startActivityForResult(i, BACKUP_EXPORT);
                        } else if (choice == 1) {
                            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                            i.addCategory(Intent.CATEGORY_OPENABLE);
                            i.setType("*/*");
                            startActivityForResult(i, BACKUP_IMPORT);
                        } else {
                            showBatteryGuide(true);
                        }
                    }).setNegativeButton("取消", null).show();
    }

    private JSONObject metadata() throws Exception {
        savePosition();
        JSONObject m = new JSONObject();
        JSONArray books = new JSONArray();
        for (Book b : library) {
            JSONObject r = new JSONObject();
            r.put("uri", b.uri);
            r.put("name", b.name);
            r.put("chapter", b.chapter);
            r.put("scroll", b.scroll);
            r.put("audioOffset", b.audioOffset);
            books.put(r);
        }
        m.put("books", books);
        m.put("font", fontSize);
        m.put("speed", speed);
        return m;
    }

    private void exportBackup(Uri target) {
        final JSONObject snapshot;
        try { snapshot = metadata(); }
        catch (Exception e) { message("备份失败：" + e.getMessage()); return; }
        new Thread(() -> {
            try {
                BackupStore.exportTo(this, target, snapshot);
                runOnUiThread(() -> message("书架、进度和小说已保存到 ZIP"));
            } catch (Exception e) {
                runOnUiThread(() -> message("备份失败，请勿使用不完整 ZIP：" + e.getMessage()));
            }
        }, "Export TXT backup").start();
    }

    private void confirmImport(Uri source) {
        new AlertDialog.Builder(this).setTitle("恢复书架")
                .setMessage("将用备份中的小说、书架、阅读位置和设置替换现有数据。"
                        + "恢复的小说保存在应用私有空间；卸载应用仍会删除它们，请保管 ZIP 备份。")
                .setPositiveButton("恢复", (d, w) -> importBackup(source))
                .setNegativeButton("取消", null).show();
    }

    private void importBackup(Uri source) {
        new Thread(() -> {
            try {
                JSONObject state = BackupStore.importFrom(this, source);
                runOnUiThread(() -> {
                    try {
                        stopSpeech();
                        saves.removeCallbacks(saveLater);
                        book = null;
                        library.clear();
                        chapters.clear();
                        fullText = "";
                        JSONArray entries = state.getJSONArray("books");
                        for (int i = 0; i < entries.length(); i++) {
                            JSONObject item = entries.getJSONObject(i);
                            Book b = new Book(item.getString("uri"), item.getString("name"));
                            b.chapter = item.optInt("chapter", 0);
                            b.scroll = item.optInt("scroll", 0);
                            b.audioOffset = item.optInt("audioOffset", -1);
                            library.add(b);
                        }
                        fontSize = Math.max(12, Math.min(32, state.optInt("font", 19)));
                        speed = Math.max(0.5f, Math.min(3f, (float) state.optDouble("speed", 1)));
                        page.setTextSize(fontSize);
                        save();
                        String keep = "";
                        if (!library.isEmpty()) {
                            keep = new java.io.File(Uri.parse(library.get(0).uri)
                                    .getPath()).getParentFile().getName();
                            load(library.get(0));
                        } else {
                            title.setText("TXT 阅读器");
                            page.setText("书架为空，请导入小说");
                        }
                        BackupStore.deleteOldImports(this, keep);
                        message("已恢复 " + library.size() + " 本小说及阅读进度");
                    } catch (Exception e) {
                        message("恢复失败：" + e.getMessage());
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> message("备份读取失败：" + e.getMessage()));
            }
        }, "Restore TXT backup").start();
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        if (book != null && SpeechService.isActiveFor(book.uri)) {
            SpeechService.listen(this);
        } else {
            syncFromBackground();
            SpeechService.listen(this);
        }
    }
    @Override protected void onPause() {
        resumed = false;
        SpeechService.listen(null);
        saves.removeCallbacks(saveLater);
        savePosition();
        super.onPause();
    }
    @Override protected void onDestroy() {
        saves.removeCallbacks(saveLater);
        super.onDestroy();
    }
}
