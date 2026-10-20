package io.github.shandianchengzi.txtreader;

import android.app.*;
import android.content.*;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.AudioFocusRequest;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.*;
import android.speech.tts.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;
import java.util.regex.*;

/** Independent foreground playback service: continues across Activity recreation,
 * screen lock and switching to another application. Only the system offline TTS is used. */
public final class SpeechService extends Service {
    static final String PLAY = "txtreader.PLAY";
    static final String STOP = "txtreader.STOP";
    static final String PAUSE = "txtreader.PAUSE";
    static final String RESUME = "txtreader.RESUME";
    private static final int NOTIFICATION_ID = 7;
    private static final String CHANNEL = "txtreader-reading";
    private static final int MAX_BYTES = 32 * 1024 * 1024;
    private static final Pattern HEAD = Pattern.compile(
            "(?im)^[ \\t\\u3000]*(第[一二三四五六七八九十百千万零〇两0-9]{1,12}[章节回卷部篇集][^\\r\\n]{0,35}|chapter[ \\t]+[0-9ivxlcdm]+[^\\r\\n]{0,45}|序章|楔子|前言|尾声|后记|番外[^\\r\\n]{0,25})[ \\t\\u3000]*$");

    interface Listener {
        void onSpeech(String uri, int chapter, int start, int end, boolean playing);
    }
    private static volatile SpeechService active;
    private static Listener listener;
    static boolean isPlaying(String uri) {
        SpeechService s = active;
        return s != null && s.playing && uri.equals(s.uri);
    }
    static boolean isActiveFor(String uri) {
        SpeechService s = active;
        return s != null && s.uri != null && s.started && s.uri.equals(uri);
    }
    static void listen(Listener callback) {
        listener = callback;
        SpeechService s = active;
        if (callback != null && s != null && s.uri != null)
            callback.onSpeech(s.uri, s.chapter, s.lastStart, s.lastEnd, s.playing);
    }
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayList<Integer> starts = new ArrayList<>();
    private TextToSpeech engine;
    private PowerManager.WakeLock wake;
    private String uri, bookName, text = "", utterance;
    private int chapter, position, lastStart, lastEnd, serial, loadSerial;
    private float speed;
    private boolean playing, started, focused, resumeOnGain, engineReady;
    // Android TTS queues multiple utterances internally. Keep three ahead of playback.
    // All queue fields are exclusively accessed from the main thread.
    private static final int BUFFER_COUNT = 3;
    private static final String LOG_TAG = "TXTReaderTTS";
    private static final class Segment {
        final int chapter, start, end;
        final String id, text;
        Segment(int chapter, int start, int end, String id, String text) {
            this.chapter = chapter;
            this.start = start;
            this.end = end;
            this.id = id;
            this.text = text;
        }
    }
    private final ArrayDeque<Segment> pending = new ArrayDeque<>();
    private int queuedChapter, queuedOffset, queueEpoch, recoveryAttempts;
    private long lastProgressTime;
    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            if (!started || !playing || !engineReady) return;
            Segment first = pending.peekFirst();
            // Only intervene when the TTS engine is idle, not during a long sentence.
            if (first != null && android.os.SystemClock.elapsedRealtime() - lastProgressTime > 16000
                    && engine != null && !engine.isSpeaking()) {
                android.util.Log.w(LOG_TAG, "queue idle; pending=" + pending.size() +
                        ", chapter=" + first.chapter + ", offset=" + first.start);
                recoverQueue(first, "idle watchdog");
                return;
            }
            main.postDelayed(this, 4000);
        }
    };
    private void startWatchdog() {
        main.removeCallbacks(watchdog);
        if (playing && engineReady) main.postDelayed(watchdog, 4000);
    }
    private void recoverQueue(Segment first, String reason) {
        if (!playing || !engineReady || first == null) return;
        android.util.Log.w(LOG_TAG, "recover " + reason + "; retry=" + recoveryAttempts);
        if (++recoveryAttempts > 2) {
            pausePlayback(true);
            android.widget.Toast.makeText(this,
                    "语音引擎连续未响应，已暂停，可手动继续",
                    android.widget.Toast.LENGTH_LONG).show();
        } else {
            resetQueue(first.chapter, first.start);
        }
    }
    private AudioManager audio;
    private AudioFocusRequest focusRequest;
    private MediaSession session;
    private final AudioManager.OnAudioFocusChangeListener focusListener = change -> main.post(() -> {
        if (!started) return;
        if (change == AudioManager.AUDIOFOCUS_GAIN) {
            focused = true;
            if (resumeOnGain && !playing) { resumeOnGain = false; resumePlayback(); }
        } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
                change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            resumeOnGain = playing;
            pausePlayback(false);
            focused = false;
        } else if (change == AudioManager.AUDIOFOCUS_LOSS) {
            resumeOnGain = false;
            pausePlayback(true);
        }
    });

    @Override public void onCreate() {
        super.onCreate();
        active = this;
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        session = new MediaSession(this, "TXT Reader");
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { main.post(() -> resumePlayback()); }
            @Override public void onPause() { main.post(() -> pausePlayback(true)); }
            @Override public void onStop() { main.post(() -> finish()); }
        }, main);
        session.setActive(true);
        updateMediaState();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26)
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "小说朗读",
                    NotificationManager.IMPORTANCE_LOW));
    }

    private void updateMediaState() {
        if (session == null) return;
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE |
                        PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_STOP)
                .setState(!started ? PlaybackState.STATE_STOPPED :
                        playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                        PlaybackState.PLAYBACK_POSITION_UNKNOWN, playing ? 1f : 0f).build());
    }
    private void updateNotification() {
        updateMediaState();
        if (started) ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                .notify(NOTIFICATION_ID, notification());
    }
    private boolean requestFocus() {
        if (focused) return true;
        if (Build.VERSION.SDK_INT >= 26) {
            if (focusRequest == null)
                focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                        .setOnAudioFocusChangeListener(focusListener, main).build();
            focused = audio.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        } else {
            focused = audio.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        }
        return focused;
    }
    private void releaseFocus() {
        if (audio != null) {
            if (Build.VERSION.SDK_INT >= 26 && focusRequest != null)
                audio.abandonAudioFocusRequest(focusRequest);
            else audio.abandonAudioFocus(focusListener);
        }
        focused = false;
    }
    private PendingIntent pending(String action, int request) {
        Intent i = new Intent(this, SpeechService.class).setAction(action);
        return PendingIntent.getService(this, request, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    private Notification notification() {
        Intent launch = new Intent(this, MainActivity.class);
        PendingIntent open = PendingIntent.getActivity(this, 2, launch,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder n = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        return n.setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(bookName == null ? "TXT 阅读器" : bookName)
                .setContentText((playing ? "离线朗读中" : "已暂停") +
                        " · 第 " + (chapter + 1) + " 章")
                .setOngoing(playing).setContentIntent(open)
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()))
                .addAction(playing ? android.R.drawable.ic_media_pause :
                        android.R.drawable.ic_media_play,
                        playing ? "暂停" : "继续", pending(playing ? PAUSE : RESUME, 1))
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", pending(STOP, 3))
                .build();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        if (STOP.equals(action)) { finish(); return START_NOT_STICKY; }
        if (PAUSE.equals(action)) { pausePlayback(true); return START_NOT_STICKY; }
        if (RESUME.equals(action)) { resumePlayback(); return START_NOT_STICKY; }
        if (!PLAY.equals(action)) return START_NOT_STICKY;
        String requestedUri = intent.getStringExtra("uri");
        if (requestedUri == null) { finish(); return START_NOT_STICKY; }
        serial++;
        loadSerial++;
        int currentLoad = loadSerial;
        playing = true;
        started = true;
        resumeOnGain = false;
        engineReady = false;
        uri = requestedUri;
        bookName = intent.getStringExtra("name");
        chapter = Math.max(0, intent.getIntExtra("chapter", 0));
        position = Math.max(0, intent.getIntExtra("offset", 0));
        speed = intent.getFloatExtra("speed", 1f);
        lastStart = position;
        lastEnd = position;
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(NOTIFICATION_ID, notification(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        else startForeground(NOTIFICATION_ID, notification());
        updateMediaState();
        if (wake == null) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TXTReader:Speech");
            wake.setReferenceCounted(false);
        }
        if (!wake.isHeld()) wake.acquire();
        recoveryAttempts = 0;
        ++queueEpoch;
        pending.clear();
        utterance = null;
        if (!requestFocus()) {
            playing = false;
            wake.release();
            updateNotification();
            android.widget.Toast.makeText(this, "其他应用正在占用音频，可稍后继续",
                    android.widget.Toast.LENGTH_LONG).show();
        }
        if (engine != null) { engine.stop(); engine.shutdown(); engine = null; }
        startWatchdog();
        text = "";
        starts.clear();
        final String source = requestedUri;
        new Thread(() -> {
            try (InputStream in = getContentResolver().openInputStream(Uri.parse(source))) {
                if (in == null) throw new IOException("小说文件不可读取");
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[16384];
                int count;
                while ((count = in.read(buffer)) != -1) {
                    if (out.size() + count > MAX_BYTES) throw new IOException("文件超出容量限制");
                    out.write(buffer, 0, count);
                }
                String decoded = decode(out.toByteArray()).replace("\r\n", "\n").replace('\r', '\n');
                main.post(() -> {
                    if (currentLoad != loadSerial || !started) return;
                    text = decoded;
                    starts.clear();
                    Matcher matcher = HEAD.matcher(text);
                    while (matcher.find()) {
                        if (starts.isEmpty() && matcher.start() > 0) starts.add(0);
                        starts.add(matcher.start());
                    }
                    if (starts.isEmpty()) starts.add(0);
                    chapter = Math.min(chapter, starts.size() - 1);
                    engine = new TextToSpeech(this, status -> main.post(() -> {
                        if (currentLoad != loadSerial || !started) return;
                        if (status != TextToSpeech.SUCCESS || engine == null) {
                            notifyFailure(); return;
                        }
                        Voice chosen = null;
                        Set<Voice> voices = engine.getVoices();
                        if (voices != null) for (Voice v : voices) {
                            if (!v.isNetworkConnectionRequired()
                                    && "zh".equals(v.getLocale().getLanguage())) {
                                chosen = v;
                                if ("CN".equalsIgnoreCase(v.getLocale().getCountry())) break;
                            }
                        }
                        if (chosen == null || engine.setVoice(chosen) != TextToSpeech.SUCCESS) {
                            notifyFailure(); return;
                        }
                        engine.setSpeechRate(speed);
                        engine.setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
                        engine.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                            @Override public void onStart(String id) {
                                main.post(() -> onSegmentStart(id));
                            }
                            @Override public void onDone(String id) {
                                main.post(() -> onSegmentDone(id));
                            }
                            @Override public void onError(String id) {
                                main.post(() -> onSegmentError(id, -1));
                            }
                            @Override public void onError(String id, int errorCode) {
                                main.post(() -> onSegmentError(id, errorCode));
                            }
                            @Override public void onStop(String id, boolean interrupted) {
                                android.util.Log.d(LOG_TAG, "onStop id=" + id +
                                        " interrupted=" + interrupted);
                            }
                        });
                        engineReady = true;
                        if (playing) resetQueue(chapter, position);
                    }));
                });
            } catch (Exception e) {
                main.post(() -> {
                    if (currentLoad == loadSerial) notifyFailure();
                });
            }
        }, "TXT offline speech reader").start();
        return START_NOT_STICKY;
    }

    private static String decode(byte[] bytes) {
        if (bytes.length >= 3 && (bytes[0] & 255) == 239
                && (bytes[1] & 255) == 187 && (bytes[2] & 255) == 191)
            return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        if (bytes.length >= 2 && (bytes[0] & 255) == 255 && (bytes[1] & 255) == 254)
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
        if (bytes.length >= 2 && (bytes[0] & 255) == 254 && (bytes[1] & 255) == 255)
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, Charset.forName("GB18030"));
        }
    }

    private void resumePlayback() {
        if (!started || playing) return;
        if (!requestFocus()) return;
        resumeOnGain = false;
        playing = true;
        if (wake != null && !wake.isHeld()) wake.acquire();
        updateNotification();
        recoveryAttempts = 0;
        if (engineReady) {
            Segment first = pending.peekFirst();
            resetQueue(first != null ? first.chapter : chapter,
                    first != null ? first.start : position);
        }
    }

    private void pausePlayback(boolean manual) {
        if (!started) return;
        if (manual) resumeOnGain = false;
        Segment first = pending.peekFirst();
        if (first != null) {
            chapter = first.chapter;
            position = first.start;
            lastStart = first.start;
            lastEnd = first.end;
        }
        ++queueEpoch;
        pending.clear();
        utterance = null;
        playing = false;
        if (engine != null) engine.stop();
        if (wake != null && wake.isHeld()) wake.release();
        if (manual) releaseFocus();
        main.removeCallbacks(watchdog);
        publish(false);
        updateNotification();
    }

    private String chapterText(int index) {
        int from = starts.get(index);
        int to = index + 1 < starts.size() ? starts.get(index + 1) : text.length();
        return text.substring(from, to).trim();
    }

    // Called only on explicit start/restart, seek, pause/resume or engine recovery.
    // Do not use QUEUE_FLUSH for normal transitions between sentences.
    private void resetQueue(int fromChapter, int fromOffset) {
        ++queueEpoch;
        pending.clear();
        queuedChapter = Math.min(Math.max(0, fromChapter), starts.size() - 1);
        queuedOffset = Math.max(0, fromOffset);
        chapter = queuedChapter;
        position = queuedOffset;
        utterance = null;
        if (engine != null) engine.stop();
        lastProgressTime = android.os.SystemClock.elapsedRealtime();
        refillQueue(true);
        startWatchdog();
    }

    private void refillQueue(boolean fresh) {
        if (!started || !playing || !engineReady || engine == null || starts.isEmpty()) return;
        while (pending.size() < BUFFER_COUNT) {
            if (queuedChapter >= starts.size()) break;
            String current = chapterText(queuedChapter);
            while (queuedOffset < current.length() &&
                    Character.isWhitespace(current.charAt(queuedOffset))) queuedOffset++;
            if (queuedOffset >= current.length()) {
                queuedChapter++;
                queuedOffset = 0;
                continue;
            }

            int begin = queuedOffset;
            int end = Math.min(begin + 120, current.length());
            for (int i = begin; i < end; i++) {
                if (current.charAt(i) == '\n' ||
                        "。！？!?；;".indexOf(current.charAt(i)) >= 0) {
                    end = i + 1;
                    break;
                }
            }
            String id = queueEpoch + ":" + (++serial);
            Segment part = new Segment(queuedChapter, begin, end,
                    id, current.substring(begin, end));
            int mode = fresh && pending.isEmpty()
                    ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD;
            int result = engine.speak(part.text, mode, null, part.id);
            if (result != TextToSpeech.SUCCESS) {
                android.util.Log.e(LOG_TAG, "tts.speak failed on " + id);
                if (pending.isEmpty()) recoverQueue(part, "enqueue error");
                return;
            }
            pending.addLast(part);
            queuedOffset = end;
        }
        android.util.Log.d(LOG_TAG, "queue size=" + pending.size() +
                ", epoch=" + queueEpoch + ", nextChapter=" + queuedChapter);
        if (pending.isEmpty() && queuedChapter >= starts.size()) finish();
    }

    private void onSegmentStart(String id) {
        if (!playing || !engineReady || pending.isEmpty()) return;
        Segment head = pending.peekFirst();
        if (!head.id.equals(id)) {
            android.util.Log.d(LOG_TAG, "ignored stale start " + id);
            return;
        }
        utterance = id;
        chapter = head.chapter;
        position = head.start;
        lastStart = head.start;
        lastEnd = head.end;
        lastProgressTime = android.os.SystemClock.elapsedRealtime();
        persistPosition();
        publish(true);
        refreshNotification();
    }

    private void onSegmentDone(String id) {
        if (!playing || pending.isEmpty()) return;
        Segment head = pending.peekFirst();
        if (!head.id.equals(id)) {
            android.util.Log.d(LOG_TAG, "ignored stale completion " + id);
            return;
        }
        pending.removeFirst();
        recoveryAttempts = 0;
        lastProgressTime = android.os.SystemClock.elapsedRealtime();
        if (!pending.isEmpty()) {
            Segment next = pending.peekFirst();
            chapter = next.chapter;
            position = next.start;
            lastStart = next.start;
            lastEnd = next.end;
        }
        refillQueue(false); // QUEUE_ADD only; keep already synthesized audio.
    }

    private void onSegmentError(String id, int code) {
        if (!playing || pending.isEmpty()) return;
        Segment head = pending.peekFirst();
        // If any queued item fails, rewind to the earliest unfinished sentence.
        boolean found = false;
        for (Segment entry : pending) {
            if (entry.id.equals(id)) { found = true; break; }
        }
        if (found) {
            android.util.Log.w(LOG_TAG, "utterance error " + id + " code=" + code);
            recoverQueue(head, "utterance error");
        }
    }

    private void publish(boolean nowPlaying) {
        Listener l = listener;
        if (l != null && uri != null)
            l.onSpeech(uri, chapter, lastStart, lastEnd, nowPlaying);
    }
    private void persistPosition() {
        // MainActivity.getPreferences(MODE_PRIVATE) is the "MainActivity" preference file.
        android.content.SharedPreferences prefs = getSharedPreferences("MainActivity", MODE_PRIVATE);
        try {
            org.json.JSONArray a = new org.json.JSONArray(prefs.getString("books", "[]"));
            for (int i = 0; i < a.length(); i++) {
                org.json.JSONObject b = a.getJSONObject(i);
                if (uri.equals(b.optString("uri"))) {
                    b.put("chapter", chapter);
                    b.put("audioOffset", lastStart);
                    break;
                }
            }
            prefs.edit().putString("books", a.toString()).apply();
        } catch (Exception ignored) {}
    }
    private void refreshNotification() { updateNotification(); }
    private void notifyFailure() {
        android.widget.Toast.makeText(this, "无法朗读：请检查本地 TXT 和中文离线 TTS 语音",
                android.widget.Toast.LENGTH_LONG).show();
        finish();
    }
    private void finish() {
        loadSerial++;
        serial++;
        playing = false;
        started = false;
        resumeOnGain = false;
        ++queueEpoch;
        pending.clear();
        main.removeCallbacks(watchdog);
        publish(false);
        if (engine != null) { engine.stop(); engine.shutdown(); engine = null; }
        if (wake != null && wake.isHeld()) wake.release();
        releaseFocus();
        updateMediaState();
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
        else stopForeground(true);
        stopSelf();
    }
    @Override public void onDestroy() {
        loadSerial++;
        playing = false;
        started = false;
        ++queueEpoch;
        pending.clear();
        main.removeCallbacks(watchdog);
        publish(false);
        if (engine != null) { engine.stop(); engine.shutdown(); engine = null; }
        if (wake != null && wake.isHeld()) wake.release();
        releaseFocus();
        if (session != null) { session.setActive(false); session.release(); session = null; }
        if (active == this) active = null;
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
