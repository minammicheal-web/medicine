package com.mina.medicine;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private static final int PICK = 1;
    private WebView web;
    private ValueCallback<Uri[]> fileCallback;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        web.addJavascriptInterface(new Bridge(), "Android");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if ("file".equals(u.getScheme())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = cb;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                try {
                    startActivityForResult(Intent.createChooser(i, "Choose file"), PICK);
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });

        if (state != null) web.restoreState(state);
        else web.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.evaluateJavascript("window.onAppResume&&window.onAppResume()", null);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != PICK || fileCallback == null) return;
        Uri[] out = null;
        if (res == RESULT_OK && data != null && data.getData() != null) out = new Uri[]{ data.getData() };
        fileCallback.onReceiveValue(out);
        fileCallback = null;
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    public void onBackPressed() {
        // 1) let the page close its top popup / menu, 2) go back in the page history,
        // 3) go to the first tab, 4) only then send the app to the background (never kill it).
        web.evaluateJavascript("(function(){try{return !!(window.MK&&MK.back())}catch(e){return false}})()", v -> {
            if ("true".equals(v)) return;
            if (web.canGoBack()) { web.goBack(); return; }
            web.evaluateJavascript("(function(){try{return !!(window.MK&&MK.home())}catch(e){return false}})()", h -> {
                if (!"true".equals(h)) moveTaskToBack(true);
            });
        });
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        if (web != null) web.evaluateJavascript("window.onDeviceTheme&&window.onDeviceTheme()", null);
    }

    /** Functions the page can call as Android.xxx(). */
    private class Bridge {
        /** Paints the navigation bar like the bottom of the page (a bottom sheet, the page, a dim popup). */
        @JavascriptInterface
        public void setNav(final String color, final boolean darkIcons) {
            runOnUiThread(() -> {
                try {
                    android.view.Window w = getWindow();
                    w.setNavigationBarColor(android.graphics.Color.parseColor(color));
                    if (Build.VERSION.SDK_INT >= 26) {
                        android.view.View d = w.getDecorView();
                        int f = d.getSystemUiVisibility();
                        f = darkIcons ? (f | android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR) : (f & ~android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
                        d.setSystemUiVisibility(f);
                    }
                } catch (Exception ignored) {}
            });
        }

        /** Paints the phone's status and navigation bars like the page and picks light or dark icons. */
        @JavascriptInterface
        public void setBars(final String color, final boolean darkIcons) {
            runOnUiThread(() -> {
                try {
                    android.view.Window w = getWindow();
                    int c = android.graphics.Color.parseColor(color);
                    w.setStatusBarColor(c);
                    w.setNavigationBarColor(c);
                    android.view.View d = w.getDecorView();
                    int f = d.getSystemUiVisibility();
                    f = darkIcons ? (f | android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR) : (f & ~android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
                    if (Build.VERSION.SDK_INT >= 26) {
                        f = darkIcons ? (f | android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR) : (f & ~android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
                    }
                    d.setSystemUiVisibility(f);
                } catch (Exception ignored) {}
            });
        }

        @JavascriptInterface
        public boolean isDark() {
            int m = getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            return m == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        }

        @JavascriptInterface
        public String saveFile(String name, String text) {
            try {
                byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
                    cv.put(MediaStore.Downloads.MIME_TYPE, "application/json");
                    cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                    Uri u = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                    try (OutputStream o = getContentResolver().openOutputStream(u)) { o.write(bytes); }
                    return "Downloads";
                } else {
                    File f = new File(getExternalFilesDir(null), name);
                    try (FileOutputStream o = new FileOutputStream(f)) { o.write(bytes); }
                    return f.getParent();
                }
            } catch (Exception e) {
                return "";
            }
        }

        /** Replaces the list of upcoming reminders: JSON array of {t, id, k, title, text, cid, dt}. */
        @JavascriptInterface
        public void setPlan(String json) {
            try { Reminders.setPlan(MainActivity.this, json); } catch (Exception ignored) { }
        }

        /** Doses marked "Taken" from a notification since the last call: JSON array of {cid, dt, at}. */
        @JavascriptInterface
        public String pullTaken() {
            try { return Reminders.pullTaken(MainActivity.this); } catch (Exception e) { return "[]"; }
        }

        @JavascriptInterface
        public String notifStatus() {
            if (Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
                return "denied";
            }
            return "granted";
        }

        @JavascriptInterface
        public void askNotifications() {
            runOnUiThread(() -> {
                if (Build.VERSION.SDK_INT >= 33
                        && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 77);
                }
            });
        }

        /** Downloads a web address as text (medicine lookup) and hands it to window.onNetText(id, ok, text). */
        @JavascriptInterface
        public void fetchText(final String id, final String url) {
            new Thread(() -> {
                String out = "";
                boolean ok = false;
                HttpURLConnection c = null;
                try {
                    c = (HttpURLConnection) new URL(url).openConnection();
                    c.setConnectTimeout(15000);
                    c.setReadTimeout(25000);
                    c.setInstanceFollowRedirects(true);
                    c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) MedicineApp");
                    c.setRequestProperty("Accept", "application/json");
                    int code = c.getResponseCode();
                    InputStream src = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
                    if (src != null) {
                        try (InputStream in = src; ByteArrayOutputStream bo = new ByteArrayOutputStream()) {
                            byte[] buf = new byte[8192];
                            int n;
                            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                            out = new String(bo.toByteArray(), StandardCharsets.UTF_8);
                        }
                    }
                    ok = code >= 200 && code < 300;
                } catch (Exception ignored) {
                } finally {
                    if (c != null) c.disconnect();
                }
                final String js = "window.onNetText&&window.onNetText(" + JSONObject.quote(id) + "," + ok + "," + JSONObject.quote(out) + ")";
                runOnUiThread(() -> web.evaluateJavascript(js, null));
            }).start();
        }
    }
}
