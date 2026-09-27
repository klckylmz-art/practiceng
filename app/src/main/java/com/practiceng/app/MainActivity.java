package com.practiceng.app;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import android.webkit.*;
import android.view.*;
import android.util.Base64;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.tukaani.xz.XZInputStream;

public class MainActivity extends Activity {
    private WebView web;
    private BroadcastReceiver receiver;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 7);
        }
        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setDefaultTextEncodingName("utf-8");
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new Bridge(), "AndroidBridge");
        try {
            StringBuilder b64 = new StringBuilder();
            for (int part = 0; ; part++) {
                String name = String.format(java.util.Locale.US, "index.html.xz.b64.part%02d", part);
                try {
                    InputStream pin = getAssets().open(name);
                    ByteArrayOutputStream pout = new ByteArrayOutputStream();
                    byte[] pbuf = new byte[8192];
                    int pn;
                    while ((pn = pin.read(pbuf)) != -1) pout.write(pbuf, 0, pn);
                    pin.close();
                    b64.append(new String(pout.toByteArray(), StandardCharsets.US_ASCII));
                } catch (FileNotFoundException missing) { break; }
            }
            byte[] packedBytes = Base64.decode(b64.toString(), Base64.DEFAULT);
            XZInputStream gz = new XZInputStream(new ByteArrayInputStream(packedBytes));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = gz.read(buf)) != -1) out.write(buf, 0, n);
            gz.close();
            String html = new String(out.toByteArray(), StandardCharsets.UTF_8);
            web.loadDataWithBaseURL("file:///android_asset/", html, "text/html", "UTF-8", null);
        } catch (IOException e) {
            web.loadData("<h2>Uygulama verisi yüklenemedi.</h2>", "text/html", "UTF-8");
        }

        receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                int topic = intent.getIntExtra("topic", 0);
                int pos = intent.getIntExtra("orderPos", -1);
                boolean playing = intent.getBooleanExtra("playing", false);
                String js = "window.onNativePlaybackState && window.onNativePlaybackState("+topic+","+pos+","+playing+");";
                web.post(() -> web.evaluateJavascript(js, null));
            }
        };
        IntentFilter f = new IntentFilter(PlaybackService.ACTION_STATE);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, RECEIVER_NOT_EXPORTED); else registerReceiver(receiver, f);
    }

    @Override protected void onResume() {
        super.onResume();
        if (web != null) web.postDelayed(() -> web.evaluateJavascript("window.syncNativePlayback && window.syncNativePlayback();", null), 250);
    }

    @Override protected void onDestroy() {
        if (receiver != null) unregisterReceiver(receiver);
        if (web != null) {
            web.removeJavascriptInterface("AndroidBridge");
            web.destroy();
        }
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack(); else moveTaskToBack(true);
    }

    public class Bridge {
        @JavascriptInterface public void startPlaylist(String json, int cursor) {
            Intent i = new Intent(MainActivity.this, PlaybackService.class);
            i.putExtra(PlaybackService.EXTRA_JSON, json);
            i.putExtra("cursor", Math.max(0, cursor));
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        }
        @JavascriptInterface public void stopPlayback() {
            Intent i = new Intent(MainActivity.this, PlaybackService.class).setAction("STOP");
            startService(i);
        }
        @JavascriptInterface public String getPlaybackState() {
            return PlaybackService.getState(MainActivity.this).toString();
        }
        @JavascriptInterface public boolean isNative() { return true; }
    }
}
