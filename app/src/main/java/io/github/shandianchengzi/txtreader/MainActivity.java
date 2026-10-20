package io.github.shandianchengzi.txtreader;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
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
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private static final int PICK_TXT = 10;
    private static final int MAX_BYTES = 32 * 1024 * 1024;
    private static final Pattern HEAD = Pattern.compile(
        "(?im)^[ \\t\\u3000]*(第[一二三四五六七八九十百千万零〇两0-9]{1,12}[章节回卷部篇集][^\\r\\n]{0,35}|chapter[ \\t]+[0-9ivxlcdm]+[^\\r\\n]{0,45}|序章|楔子|前言|尾声|后记|番外[^\\r\\n]{0,25})[ \\t\\u3000]*$");

    private static class Book {
        String uri, name;
        int chapter, scroll;
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
    private int fontSize = 19, generation, speechOffset, speechChapter = -1;
    private float speed = 1.0f;
    private boolean speaking, ttsReady, ttsLoading;
    private TextToSpeech tts;

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
        scroller.addView(page);
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
        stopSpeech();
        savePosition();
        speechChapter = -1;
        speechOffset = 0;
        book = b;
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
        title.setText(book.name + " · " + (book.chapter + 1) + "/" + chapters.size() + " · " + c.title);
        page.setText(fullText.substring(c.start, c.end).trim());
        scroller.post(() -> scroller.scrollTo(0, scrollY));
        save();
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
                    if (tts != null) tts.setSpeechRate(speed);
                    save();
                }).show();
    }

    private void toggleSpeech() {
        if (book == null || chapters.isEmpty()) { message("请先导入小说"); return; }
        if (speaking) { stopSpeech(); return; }
        if (ttsReady) { startSpeech(); return; }
        if (ttsLoading) return;
        ttsLoading = true;
        speechButton.setText("初始化");
        tts = new TextToSpeech(this, status -> {
            ttsLoading = false;
            speechButton.setText("朗读");
            if (status != TextToSpeech.SUCCESS || tts == null) {
                message("系统 TTS 初始化失败，请检查语音引擎设置"); return;
            }
            Voice chosen = null;
            Set<Voice> voices = tts.getVoices();
            if (voices != null) {
                for (Voice v : voices) {
                    if (!v.isNetworkConnectionRequired() && "zh".equals(v.getLocale().getLanguage())) {
                        chosen = v;
                        if ("CN".equalsIgnoreCase(v.getLocale().getCountry())) break;
                    }
                }
            }
            if (chosen == null || tts.setVoice(chosen) != TextToSpeech.SUCCESS) {
                message("没有可用的中文离线语音。请在系统 TTS 设置中安装中文离线语音包。");
                return;
            }
            tts.setSpeechRate(speed);
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) {}
                @Override public void onDone(String id) {
                    runOnUiThread(() -> {
                        if (speaking && id.startsWith(generation + ":")) speakNext();
                    });
                }
                @Override public void onError(String id) {
                    runOnUiThread(() -> {
                        if (id.startsWith(generation + ":")) {
                            stopSpeech();
                            message("朗读失败，请检查离线语音数据");
                        }
                    });
                }
            });
            ttsReady = true;
            if (book != null && !chapters.isEmpty()) startSpeech();
        });
    }
    private void startSpeech() {
        if (speechChapter != book.chapter) { speechOffset = 0; speechChapter = book.chapter; }
        speaking = true;
        speechButton.setText("暂停");
        speakNext();
    }
    private void stopSpeech() {
        generation++;
        speaking = false;
        if (speechButton != null) speechButton.setText("朗读");
        if (tts != null) tts.stop();
    }
    private void speakNext() {
        if (!speaking || !ttsReady || book == null || chapters.isEmpty()) return;
        Chapter c = chapters.get(book.chapter);
        String str = fullText.substring(c.start, c.end);
        while (speechOffset < str.length() && Character.isWhitespace(str.charAt(speechOffset))) speechOffset++;
        if (speechOffset >= str.length()) {
            if (book.chapter + 1 < chapters.size()) {
                changeChapter(book.chapter + 1);
                speechChapter = book.chapter;
                speechOffset = 0;
                speaking = true;
                speechButton.setText("暂停");
                speakNext();
            } else {
                stopSpeech();
                speechOffset = 0;
                message("已朗读到小说末尾");
            }
            return;
        }
        int end = Math.min(speechOffset + 550, str.length());
        if (end < str.length()) {
            for (int i = end; i > speechOffset + 160; i--) {
                if ("。！？；：\n.!?;".indexOf(str.charAt(i - 1)) >= 0) { end = i; break; }
            }
        }
        String part = str.substring(speechOffset, end);
        speechOffset = end;
        String id = generation + ":" + speechOffset;
        int result = tts.speak(part, TextToSpeech.QUEUE_FLUSH, null, id);
        if (result != TextToSpeech.SUCCESS) {
            stopSpeech(); message("离线朗读失败");
        }
    }

    @Override protected void onPause() { super.onPause(); savePosition(); }
    @Override protected void onDestroy() {
        stopSpeech();
        if (tts != null) { tts.shutdown(); tts = null; }
        super.onDestroy();
    }
}
