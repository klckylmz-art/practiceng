package com.practiceng.app;

import android.app.Activity;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.util.Base64;
import org.tukaani.xz.XZInputStream;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public class MainActivity extends Activity implements TextToSpeech.OnInitListener {
    private WebView web;
    private TextToSpeech tts;
    private boolean ttsReady = false;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        tts = new TextToSpeech(this, this);

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
        web.addJavascriptInterface(new TTSBridge(), "AndroidTTS");

        try {
            StringBuilder b64 = new StringBuilder();
            for (int part = 0; ; part++) {
                String name = String.format(Locale.US, "daily200.html.xz.b64.part%02d", part);
                try (InputStream pin = getAssets().open(name)) {
                    ByteArrayOutputStream pout = new ByteArrayOutputStream();
                    byte[] pbuf = new byte[8192];
                    int pn;
                    while ((pn = pin.read(pbuf)) != -1) pout.write(pbuf, 0, pn);
                    b64.append(new String(pout.toByteArray(), StandardCharsets.US_ASCII));
                } catch (FileNotFoundException missing) {
                    break;
                }
            }
            byte[] packedBytes = Base64.decode(b64.toString(), Base64.DEFAULT);
            XZInputStream xz = new XZInputStream(new ByteArrayInputStream(packedBytes));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = xz.read(buf)) != -1) out.write(buf, 0, n);
            xz.close();
            String html = new String(out.toByteArray(), StandardCharsets.UTF_8);
            web.loadDataWithBaseURL("file:///android_asset/", html, "text/html", "UTF-8", null);
        } catch (Exception e) {
            web.loadData("<h2>Uygulama verisi yüklenemedi.</h2><pre>"+e.getMessage()+"</pre>", "text/html", "UTF-8");
        }
    }

    @Override public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            int result = tts.setLanguage(Locale.US);
            tts.setSpeechRate(0.9f);
            ttsReady = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED;
        }
    }

    public class TTSBridge {
        @JavascriptInterface public void speak(final String text) {
            runOnUiThread(() -> {
                if (ttsReady && text != null && !text.trim().isEmpty()) {
                    tts.stop();
                    tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "daily200");
                }
            });
        }
    }

    @Override public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack(); else moveTaskToBack(true);
    }

    @Override protected void onDestroy() {
        if (web != null) {
            web.removeJavascriptInterface("AndroidTTS");
            web.destroy();
        }
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        super.onDestroy();
    }
}
