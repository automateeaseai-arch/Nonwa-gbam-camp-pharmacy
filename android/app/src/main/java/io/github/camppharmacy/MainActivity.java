package io.github.camppharmacy;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.print.PrintAttributes;
import android.print.PrintManager;
import android.provider.MediaStore;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Shows the Camp Pharmacy web app in Android's own web viewer, so it does not depend on Chrome's installer.
 * The app itself (and every update to it) still comes from the website; this only adds what a web viewer
 * cannot do on its own: saving files to Downloads, printing / saving the report as PDF, and the back button.
 */
public class MainActivity extends Activity {
    private static final String APP_URL = "https://automateeaseai-arch.github.io/Nonwa-gbam-camp-pharmacy/";
    private static final String APP_HOST = "automateeaseai-arch.github.io";
    private static final int PICK_FILE = 1;

    private WebView web;
    private ValueCallback<Uri[]> pendingPick;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setUserAgentString(s.getUserAgentString() + " CampPharmacyAndroid/1");

        web.addJavascriptInterface(new Bridge(), "AndroidBridge");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (APP_HOST.equals(u.getHost())) return false; // our own pages stay in the app
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (ActivityNotFoundException ignored) { }
                return true;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
                if (req.isForMainFrame()) view.loadDataWithBaseURL(null, OFFLINE_PAGE, "text/html", "utf-8", null);
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override // "Load backup" file picker
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (pendingPick != null) pendingPick.onReceiveValue(null);
                pendingPick = callback;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
                try { startActivityForResult(i, PICK_FILE); } catch (ActivityNotFoundException e) { pendingPick = null; return false; }
                return true;
            }
        });

        if (state == null || web.restoreState(state) == null) web.loadUrl(APP_URL);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_FILE || pendingPick == null) return;
        pendingPick.onReceiveValue(result == RESULT_OK && data != null && data.getData() != null ? new Uri[]{data.getData()} : null);
        pendingPick = null;
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        // The web app answers "true" when it handled Back itself (closed a box or went back to the search screen).
        web.evaluateJavascript("(window.appBack && window.appBack()) ? 'yes' : 'no'", r -> {
            if (r == null || !r.contains("yes")) {
                if (web.canGoBack() && web.getUrl() != null && !web.getUrl().startsWith(APP_URL)) web.goBack();
                else moveTaskToBack(true);
            }
        });
    }

    /** Functions the web app can call as window.AndroidBridge.* */
    private class Bridge {
        @JavascriptInterface
        public void saveFile(String name, String mime, String text) {
            String safe = name.replaceAll("[^A-Za-z0-9._-]", "_");
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            String msg;
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.Downloads.DISPLAY_NAME, safe);
                    v.put(MediaStore.Downloads.MIME_TYPE, mime);
                    Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                    if (uri == null) throw new Exception("no space");
                    try (OutputStream o = getContentResolver().openOutputStream(uri)) { o.write(bytes); }
                    msg = "Saved to Downloads: " + safe;
                } else {
                    File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                    File f = new File(dir, safe);
                    try (FileOutputStream o = new FileOutputStream(f)) { o.write(bytes); }
                    msg = "Saved: " + f.getAbsolutePath();
                }
            } catch (Exception e) {
                msg = "Could not save the file: " + e.getMessage();
            }
            final String m = msg;
            runOnUiThread(() -> Toast.makeText(MainActivity.this, m, Toast.LENGTH_LONG).show());
        }

        @JavascriptInterface
        public void print(String title) {
            runOnUiThread(() -> {
                PrintManager pm = (PrintManager) getSystemService(Context.PRINT_SERVICE);
                String job = title == null || title.isEmpty() ? "Pharmacy report" : title;
                pm.print(job, web.createPrintDocumentAdapter(job), new PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).build());
            });
        }
    }

    private static final String OFFLINE_PAGE =
        "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'></head>"
        + "<body style='font-family:sans-serif;padding:24px;color:#16211b;background:#f5f7f5'>"
        + "<h2 style='color:#0b6b3a'>No internet</h2>"
        + "<p>The first time, the app needs internet to download itself. After that it works offline.</p>"
        + "<button onclick=\"location.href='" + APP_URL + "'\" style='font-size:16px;padding:10px 18px;background:#0b6b3a;color:#fff;border:0;border-radius:8px'>Try again</button>"
        + "</body></html>";
}
