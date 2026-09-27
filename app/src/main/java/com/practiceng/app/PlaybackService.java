package com.practiceng.app;

import android.app.*;
import android.content.*;
import android.os.*;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import org.json.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class PlaybackService extends Service implements TextToSpeech.OnInitListener {
    public static final String ACTION_STATE = "com.practiceng.app.PLAYBACK_STATE";
    public static final String EXTRA_JSON = "playlist_json";
    private static final String CH = "practiceng_playback";
    private static final int NOTIF_ID = 1401;

    private TextToSpeech tts;
    private PowerManager.WakeLock wakeLock;
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);
    private Thread worker;
    private volatile boolean ttsReady = false;
    private String playlistJson;
    private int cursor = 0;

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        PowerManager pm = (PowerManager)getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PracticEng:Playback");
        wakeLock.setReferenceCounted(false);
        tts = new TextToSpeech(this, this);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if ("STOP".equals(action)) {
            stopPlayback(true);
            return START_NOT_STICKY;
        }
        if (intent != null && intent.hasExtra(EXTRA_JSON)) {
            playlistJson = intent.getStringExtra(EXTRA_JSON);
            cursor = intent.getIntExtra("cursor", 0);
            getSharedPreferences("playback", MODE_PRIVATE).edit()
                    .putString("json", playlistJson).putInt("cursor", cursor).putBoolean("active", true).apply();
        } else if (playlistJson == null) {
            android.content.SharedPreferences p = getSharedPreferences("playback", MODE_PRIVATE);
            if (p.getBoolean("active", false)) {
                playlistJson = p.getString("json", null);
                cursor = p.getInt("cursor", 0);
            }
        }
        if (playlistJson != null) startForeground(NOTIF_ID, buildNotification("Hazırlanıyor…"));
        maybeStartWorker();
        return START_STICKY;
    }

    @Override public void onInit(int status) {
        ttsReady = status == TextToSpeech.SUCCESS;
        maybeStartWorker();
    }

    private synchronized void maybeStartWorker() {
        if (!ttsReady || playlistJson == null) return;
        if (worker != null && worker.isAlive()) {
            stopRequested.set(true);
            if (tts != null) tts.stop();
            try { worker.interrupt(); } catch (Exception ignored) {}
        }
        stopRequested.set(false);
        worker = new Thread(this::runPlaylist, "PracticEngPlayback");
        worker.start();
    }

    private void runPlaylist() {
        if (!wakeLock.isHeld()) wakeLock.acquire();
        try {
            JSONObject root = new JSONObject(playlistJson);
            JSONArray arr = root.getJSONArray("sentences");
            boolean loop = root.optBoolean("loop", true);
            int delayMs = root.optInt("delayMs", 4000);
            int trRepeat = Math.max(1, root.optInt("trRepeat", 1));
            int enRepeat = Math.max(1, root.optInt("enRepeat", 1));
            boolean trOn = root.optBoolean("trOn", true);
            boolean enOn = root.optBoolean("enOn", true);
            float trRate = (float)root.optDouble("trRate", 1.0);
            float enRate = (float)root.optDouble("enRate", 1.0);

            while (!stopRequested.get()) {
                if (arr.length() == 0) break;
                if (cursor >= arr.length()) {
                    if (loop) cursor = 0; else break;
                }
                JSONObject s = arr.getJSONObject(cursor);
                int topic = s.optInt("topic", 1);
                int orderPos = s.optInt("orderPos", cursor);
                String tr = s.optString("tr", "");
                String en = s.optString("en", "");

                broadcast(topic, orderPos, true);
                updateNotification("Konu " + topic + " • Cümle " + (orderPos + 1));

                int pairCount = Math.min(trRepeat, enRepeat);
                for (int i=0; i<pairCount && !stopRequested.get(); i++) {
                    if (trOn && !tr.isEmpty()) speakBlocking(tr, new Locale("tr","TR"), trRate);
                    if (enOn && !en.isEmpty()) speakBlocking(en, Locale.UK, enRate);
                }
                for (int i=pairCount; i<enRepeat && !stopRequested.get(); i++) {
                    if (enOn && !en.isEmpty()) speakBlocking(en, Locale.UK, enRate);
                }
                if (stopRequested.get()) break;

                cursor++;
                getSharedPreferences("playback", MODE_PRIVATE).edit().putInt("cursor", cursor).putInt("topic", topic).putInt("orderPos", orderPos).apply();
                if (delayMs > 0 && (loop || cursor < arr.length())) {
                    long end = SystemClock.elapsedRealtime() + delayMs;
                    while (!stopRequested.get() && SystemClock.elapsedRealtime() < end) {
                        SystemClock.sleep(Math.min(250, end - SystemClock.elapsedRealtime()));
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (!stopRequested.get()) stopPlayback(false);
        }
    }

    private void speakBlocking(String text, Locale locale, float uiRate) throws InterruptedException {
        if (tts == null || stopRequested.get()) return;
        tts.setLanguage(locale);
        tts.setSpeechRate(mapRate(uiRate));
        final Object lock = new Object();
        final boolean[] done = {false};
        String id = UUID.randomUUID().toString();
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String utteranceId) {}
            @Override public void onDone(String utteranceId) { synchronized(lock){ done[0]=true; lock.notifyAll(); } }
            @Override public void onError(String utteranceId) { synchronized(lock){ done[0]=true; lock.notifyAll(); } }
        });
        Bundle b = new Bundle();
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, b, id);
        synchronized (lock) {
            while (!done[0] && !stopRequested.get()) lock.wait(500);
        }
    }

    private float mapRate(float r) {
        float actual = r <= 1f ? (0.35f + (r - 0.45f) * (0.65f / 0.55f)) : (1f + (r - 1f) * (1.10f / 0.85f));
        return Math.max(0.3f, Math.min(2.1f, actual));
    }

    private void broadcast(int topic, int orderPos, boolean playing) {
        Intent i = new Intent(ACTION_STATE).setPackage(getPackageName());
        i.putExtra("topic", topic); i.putExtra("orderPos", orderPos); i.putExtra("playing", playing);
        sendBroadcast(i);
    }

    private void stopPlayback(boolean explicit) {
        stopRequested.set(true);
        if (tts != null) tts.stop();
        getSharedPreferences("playback", MODE_PRIVATE).edit().putBoolean("active", false).apply();
        broadcast(0, -1, false);
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(CH, "PracticEng playback", NotificationManager.IMPORTANCE_LOW);
            c.setDescription("Ekran kilitliyken cümle seslendirmesini sürdürür");
            getSystemService(NotificationManager.class).createNotificationChannel(c);
        }
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 1, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent stop = new Intent(this, PlaybackService.class).setAction("STOP");
        PendingIntent ps = PendingIntent.getService(this, 2, stop, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CH)
                .setContentTitle("PracticEng oynatılıyor")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(pi)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_pause, "Durdur", ps).build())
                .build();
    }

    private void updateNotification(String text) {
        getSystemService(NotificationManager.class).notify(NOTIF_ID, buildNotification(text));
    }

    public static JSONObject getState(Context c) {
        android.content.SharedPreferences p = c.getSharedPreferences("playback", MODE_PRIVATE);
        JSONObject o = new JSONObject();
        try {
            o.put("active", p.getBoolean("active", false));
            o.put("cursor", p.getInt("cursor", 0));
            o.put("topic", p.getInt("topic", 0));
            o.put("orderPos", p.getInt("orderPos", -1));
        } catch (JSONException ignored) {}
        return o;
    }

    @Override public void onDestroy() {
        stopRequested.set(true);
        if (tts != null) { tts.stop(); tts.shutdown(); }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
