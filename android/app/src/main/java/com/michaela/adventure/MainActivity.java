package com.michaela.adventure;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.webkit.WebResourceRequest;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Full-screen wrapper for the game. It plays the newest copy of the game it has
 * (downloaded from the GitHub repo, or the copy built into the app) and quietly
 * downloads newer versions of the game in the background, so game updates
 * arrive without reinstalling the app.
 */
public class MainActivity extends Activity {
    /** Bump this only when the Android wrapper itself changes (the in-game banner then asks for a reinstall). */
    static final int SHELL_VERSION = 5;
    static final String RAW = "https://tfensign.github.io/Michaela-s-Adventure/";   // GitHub Pages copy of web/
    private long lastCheck = 0;
    private volatile boolean checking = false;
    private WebView web;
    private File gameDir;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window w = getWindow();
        w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 28) {
            w.getAttributes().layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }

        web = new WebView(this);
        web.setBackgroundColor(Color.rgb(13, 17, 23));
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);          // saves best score, unlocked levels, volume
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(false);
        web.setWebViewClient(new WebViewClient() {
            // web links (like the update download) open in the phone's browser
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                String sc = u.getScheme();
                if ("http".equals(sc) || "https".equals(sc)) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) {}
                    return true;
                }
                return false;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onCreateWindow(WebView view, boolean dialog, boolean userGesture, android.os.Message resultMsg) {
                WebView.HitTestResult r = view.getHitTestResult();
                String url = r != null ? r.getExtra() : null;
                if (url != null) { try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception ignored) {} }
                return false;
            }
        });
        s.setSupportMultipleWindows(true);
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);
        setContentView(web);
        hideSystemBars();

        gameDir = new File(getFilesDir(), "game");
        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl(bestGameUrl());
    }

    // ---------- live game updates ----------
    private int bundledVersion() {
        try (InputStream in = getAssets().open("version.txt")) { return parseVersion(readAll(in)); } catch (Exception e) { return 0; }
    }
    private int downloadedVersion() {
        try (InputStream in = new java.io.FileInputStream(new File(gameDir, "version.txt"))) {
            if (!new File(gameDir, "index.html").exists()) return 0;
            return parseVersion(readAll(in));
        } catch (Exception e) { return 0; }
    }
    private static int parseVersion(String s) { try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; } }
    private static String readAll(InputStream in) throws java.io.IOException {
        StringBuilder sb = new StringBuilder(); BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8")); String line;
        while ((line = r.readLine()) != null) sb.append(line).append('\n'); return sb.toString();
    }
    private String bestGameUrl() {
        String q = "?shell=" + SHELL_VERSION;
        if (downloadedVersion() > bundledVersion()) return "file://" + new File(gameDir, "index.html").getAbsolutePath() + q;
        return "file:///android_asset/index.html" + q;
    }
    private void status(String t) {
        runOnUiThread(() -> { if (web != null) web.evaluateJavascript("try{updStatus(" + org.json.JSONObject.quote(t) + ")}catch(e){}", null); });
    }
    private void checkForGameUpdate() {
        long now = System.currentTimeMillis();
        if (checking || now - lastCheck < 5 * 60 * 1000) return;      // every app open, at most every 5 minutes
        lastCheck = now; checking = true;
        new Thread(() -> {
            try {
                int current = Math.max(bundledVersion(), downloadedVersion());
                status("Checking for updates...");
                int remote = parseVersion(httpText(RAW + "version.txt?t=" + now));
                if (remote <= current) { status(remote > 0 ? "Up to date" : "Couldn't check for updates"); return; }
                status("Downloading update...");
                gameDir.mkdirs();
                File tmp = new File(gameDir, "index.html.part");
                HttpURLConnection c = open(RAW + "index.html?v=" + remote);
                long n = 0;
                try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[65536]; int k;
                    while ((k = in.read(buf)) > 0) { out.write(buf, 0, k); n += k; }
                } finally { c.disconnect(); }
                if (n < 1_000_000) { tmp.delete(); status("Update download failed. Will retry."); lastCheck = 0; return; }
                File dst = new File(gameDir, "index.html");
                dst.delete();
                if (!tmp.renameTo(dst)) return;
                try (OutputStream out = new FileOutputStream(new File(gameDir, "version.txt"))) { out.write(String.valueOf(remote).getBytes("UTF-8")); }
                final String url = bestGameUrl();
                runOnUiThread(() -> {
                    // switches right away on the main menu, otherwise the next time the menu opens
                    if (web != null) web.evaluateJavascript("try{gameUpdateReady(" + org.json.JSONObject.quote(url) + ")}catch(e){location.replace(" + org.json.JSONObject.quote(url) + ")}", null);
                });
            } catch (Exception e) { status("Offline. Will check for updates later."); lastCheck = 0; }
            finally { checking = false; }
        }).start();
    }
    private static HttpURLConnection open(String u) throws java.io.IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setConnectTimeout(8000); c.setReadTimeout(20000); c.setUseCaches(false); c.setInstanceFollowRedirects(true);
        return c;
    }
    private static String httpText(String u) throws java.io.IOException {
        HttpURLConnection c = open(u);
        try (InputStream in = c.getInputStream()) { return readAll(in); } finally { c.disconnect(); }
    }

    private void hideSystemBars() {
        web.setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    @Override
    protected void onPause() {
        // pause the game and its music when the app goes to the background
        if (web != null) {
            web.evaluateJavascript("try{ if(!paused&&!titleUp) togglePause(); if(audioCtx) audioCtx.suspend(); }catch(e){}", null);
            web.onPause();
        }
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) {
            web.onResume();
            web.postDelayed(this::checkForGameUpdate, 1500);
            web.evaluateJavascript("try{ if(audioCtx) audioCtx.resume(); }catch(e){}", null);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (web != null) web.saveState(out);
    }

    @Override
    protected void onDestroy() {
        if (web != null) { web.destroy(); web = null; }
        super.onDestroy();
    }
}
