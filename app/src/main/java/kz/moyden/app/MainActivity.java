package kz.moyden.app;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {
    private WebView web;
    private ValueCallback<Uri[]> chooser;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (getActionBar() != null) getActionBar().hide();
        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setBlockNetworkLoads(true);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        WebView.setWebContentsDebuggingEnabled(false);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return !r.getUrl().toString().startsWith("file:///android_asset/");
            }
        });
        if (lockOn()) getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
        web.addJavascriptInterface(new Bridge(), "AndroidBridge");
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (chooser != null) chooser.onReceiveValue(null);
                chooser = cb;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                try {
                    startActivityForResult(i, 1);
                } catch (Exception e) {
                    chooser = null;
                    return false;
                }
                return true;
            }
        });
        web.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == 3) {
            authing = false;
            if (res == RESULT_OK) {
                authed = true;
                pausedAt = System.currentTimeMillis();
                web.setVisibility(View.VISIBLE);
            } else {
                finish();
            }
            return;
        }
        if (req == 1 && chooser != null) {
            Uri[] r = null;
            if (res == RESULT_OK && data != null && data.getData() != null) r = new Uri[]{data.getData()};
            chooser.onReceiveValue(r);
            chooser = null;
        } else {
            super.onActivityResult(req, res, data);
        }
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("(window.__back&&window.__back())?'1':'0'", new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String v) {
                if (!"\"1\"".equals(v)) MainActivity.super.onBackPressed();
            }
        });
    }

    private boolean authed = false, authing = false;
    private long pausedAt = 0;

    private boolean lockOn() {
        return getSharedPreferences("sec", 0).getBoolean("lock", false);
    }

    @Override
    protected void onPause() {
        super.onPause();
        pausedAt = System.currentTimeMillis();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!lockOn()) {
            web.setVisibility(View.VISIBLE);
            return;
        }
        if (authed && System.currentTimeMillis() - pausedAt > 60000) authed = false;
        if (authed) {
            web.setVisibility(View.VISIBLE);
        } else {
            web.setVisibility(View.INVISIBLE);
            if (!authing) askUnlock();
        }
    }

    private void askUnlock() {
        KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        Intent i = km.isDeviceSecure() ? km.createConfirmDeviceCredentialIntent("Ежедневник", "Подтвердите, что это вы") : null;
        if (i == null) {
            authed = true;
            web.setVisibility(View.VISIBLE);
            return;
        }
        authing = true;
        startActivityForResult(i, 3);
    }

    private void toast(final String t) {
        runOnUiThread(new Runnable() {
            public void run() { Toast.makeText(MainActivity.this, t, Toast.LENGTH_LONG).show(); }
        });
    }

    public class Bridge {
        @JavascriptInterface
        public boolean isLocked() {
            return lockOn();
        }

        @JavascriptInterface
        public boolean setLock(final boolean on) {
            KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            if (on && !km.isDeviceSecure()) return false;
            getSharedPreferences("sec", 0).edit().putBoolean("lock", on).apply();
            if (on) {
                authed = true;
                pausedAt = System.currentTimeMillis();
            }
            runOnUiThread(new Runnable() {
                public void run() {
                    if (on) getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
                    else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
                }
            });
            return true;
        }

        @JavascriptInterface
        public void setReminder(boolean on, int h, int m) {
            getSharedPreferences("rem", 0).edit().putBoolean("on", on).putInt("h", h).putInt("m", m).apply();
            if (on) Reminder.schedule(MainActivity.this, h, m); else Reminder.cancel(MainActivity.this);
        }

        @JavascriptInterface
        public void askNotifications() {
            runOnUiThread(new Runnable() {
                public void run() {
                    if (android.os.Build.VERSION.SDK_INT >= 33
                            && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 2);
                    }
                }
            });
        }

        @JavascriptInterface
        public boolean save(String name, String data) {
            try {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Downloads.DISPLAY_NAME, name);
                v.put(MediaStore.Downloads.MIME_TYPE, "application/json");
                v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri u = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                OutputStream o = getContentResolver().openOutputStream(u);
                o.write(data.getBytes(StandardCharsets.UTF_8));
                o.close();
                toast("Файл сохранён в «Загрузки»: " + name);
                return true;
            } catch (Exception e) {
                toast("Не удалось сохранить файл");
                return false;
            }
        }
    }
}
