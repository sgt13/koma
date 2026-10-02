package app.koma.reader;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.WebViewAssetLoader;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final int REQ_TREE = 41;
    private static final String HOST = "https://appassets.androidplatform.net";

    private WebView web;
    private Library lib;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean volumeKeys = false;
    private volatile boolean pageReady = false;
    private String pendingExternal = null;
    private TextRecognizer recognizer;
    private final ExecutorService ml = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        lib = new Library(this);

        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }

        web = new WebView(this);
        web.setBackgroundColor(0xFF16151C);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setTextZoom(100);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .addPathHandler("/cache/", new WebViewAssetLoader.InternalStoragePathHandler(this, lib.cacheRoot()))
                .build();

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if ("appassets.androidplatform.net".equals(u.getHost())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true;
            }
        });

        web.addJavascriptInterface(new Bridge(), "KomaNative");
        web.loadUrl(HOST + "/assets/www/index.html");
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction()) || intent.getData() == null) return;
        final Uri uri = intent.getData();
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) { }
        lib.exec.execute(() -> {
            try {
                String json = lib.registerExternal(uri);
                if (pageReady) call("openExternal", json);
                else pendingExternal = json;
            } catch (Throwable t) {
                call("onError", q("Ce fichier ne peut pas être ouvert : " + t.getMessage()));
            }
        });
    }

    /* ---------------- pont JavaScript ---------------- */
    private class Bridge {
        @JavascriptInterface
        public String ready() {
            pageReady = true;
            String p = pendingExternal;
            pendingExternal = null;
            return p == null ? "" : p;
        }

        @JavascriptInterface
        public void pickFolder() {
            main.post(() -> {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                try {
                    startActivityForResult(i, REQ_TREE);
                } catch (Exception e) {
                    call("onError", q("Aucun sélecteur de dossier n'est disponible sur cet appareil."));
                }
            });
        }

        @JavascriptInterface
        public String getFolders() { return lib.foldersJson(); }

        @JavascriptInterface
        public String getCachedBooks() { return lib.cachedBooksJson(); }

        @JavascriptInterface
        public void removeFolder(String uri) { lib.removeFolder(uri); }

        @JavascriptInterface
        public void scan() {
            lib.exec.execute(() -> {
                try {
                    String json = lib.scan((n) -> call("onScanProgress", String.valueOf(n)));
                    call("onScan", json);
                } catch (Throwable t) {
                    call("onError", q("Lecture des dossiers impossible : " + t.getMessage()));
                    call("onScan", lib.cachedBooksJson());
                }
            });
        }

        @JavascriptInterface
        public void cover(String id) {
            lib.covers.execute(() -> {
                String url = null;
                try { url = lib.cover(id); } catch (Throwable ignored) { }
                call("onCover", q(id) + "," + q(url == null ? "" : url));
            });
        }

        @JavascriptInterface
        public void open(String id) {
            lib.exec.execute(() -> {
                try {
                    String json = lib.open(id, (done, total) -> call("onProgress", q(id) + "," + done + "," + total));
                    call("onBook", q(id) + "," + json);
                } catch (Throwable t) {
                    String m = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                    call("onBookError", q(id) + "," + q("Impossible d'ouvrir ce fichier : " + m));
                }
            });
        }

        /** Détecte les blocs de texte d'une page avec l'IA de l'appareil (ML Kit, hors ligne). */
        @JavascriptInterface
        public void detectText(String reqId, String url) {
            ml.execute(() -> {
                try {
                    File f = lib.fileForUrl(url);
                    if (f == null) { call("onText", q(reqId) + ",null"); return; }
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inJustDecodeBounds = true;
                    BitmapFactory.decodeFile(f.getPath(), o);
                    int sample = 1;
                    while (Math.max(o.outWidth, o.outHeight) / sample > 2600) sample *= 2;
                    BitmapFactory.Options o2 = new BitmapFactory.Options();
                    o2.inSampleSize = sample;
                    final Bitmap bm = BitmapFactory.decodeFile(f.getPath(), o2);
                    if (bm == null || o.outWidth <= 0) { call("onText", q(reqId) + ",null"); return; }
                    final float sx = (float) o.outWidth / bm.getWidth(), sy = (float) o.outHeight / bm.getHeight();
                    if (recognizer == null) recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
                    recognizer.process(InputImage.fromBitmap(bm, 0))
                            .addOnSuccessListener(text -> {
                                JSONArray arr = new JSONArray();
                                try {
                                    for (Text.TextBlock b : text.getTextBlocks()) {
                                        Rect r = b.getBoundingBox();
                                        if (r == null) continue;
                                        float lh = 0; int n = 0;
                                        JSONArray la = new JSONArray();
                                        for (Text.Line l : b.getLines()) {
                                            Rect lr = l.getBoundingBox();
                                            if (lr == null) continue;
                                            lh += lr.height(); n++;
                                            JSONObject lj = new JSONObject();
                                            lj.put("x", lr.left * sx); lj.put("y", lr.top * sy);
                                            lj.put("w", lr.width() * sx); lj.put("h", lr.height() * sy);
                                            la.put(lj);
                                        }
                                        JSONObject j = new JSONObject();
                                        j.put("x", r.left * sx); j.put("y", r.top * sy);
                                        j.put("w", r.width() * sx); j.put("h", r.height() * sy);
                                        j.put("lh", n > 0 ? lh / n * sy : r.height() * sy);
                                        j.put("t", b.getText());
                                        j.put("l", la);
                                        arr.put(j);
                                    }
                                } catch (Exception ignored) { }
                                bm.recycle();
                                call("onText", q(reqId) + "," + arr);
                            })
                            .addOnFailureListener(e -> {
                                bm.recycle();
                                call("onText", q(reqId) + ",null");
                            });
                } catch (Throwable t) {
                    call("onText", q(reqId) + ",null");
                }
            });
        }

        @JavascriptInterface
        public void clearCache() {
            lib.exec.execute(() -> { lib.clearCache(); call("onCacheCleared", ""); });
        }

        @JavascriptInterface
        public void setReading(boolean on) {
            main.post(() -> {
                volumeKeys = on;
                immersive(on);
                if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            });
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_TREE) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            call("onFolders", lib.foldersJson());
            return;
        }
        Uri tree = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) { }
        lib.addFolder(tree);
        call("onFolderAdded", lib.foldersJson());
    }

    /* ---------------- plein écran / touches ---------------- */
    @SuppressWarnings("deprecation")
    private void immersive(boolean on) {
        Window w = getWindow();
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = w.getInsetsController();
            if (c == null) return;
            if (on) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            } else {
                c.show(WindowInsets.Type.systemBars());
            }
        } else {
            View d = w.getDecorView();
            if (on) {
                d.setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
            } else {
                d.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
            }
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        if (volumeKeys && (k == KeyEvent.KEYCODE_VOLUME_DOWN || k == KeyEvent.KEYCODE_VOLUME_UP)) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) call("volume", k == KeyEvent.KEYCODE_VOLUME_DOWN ? "1" : "-1");
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        web.evaluateJavascript("(window.koma && koma.back()) ? 'yes' : 'no'", v -> {
            if (v == null || !v.contains("yes")) finish();
        });
    }

    @Override
    protected void onDestroy() {
        if (web != null) web.destroy();
        super.onDestroy();
    }

    /* ---------------- utilitaires ---------------- */
    void call(String fn, String args) {
        main.post(() -> {
            if (web != null) web.evaluateJavascript("window.koma && koma." + fn + "(" + args + ")", null);
        });
    }

    static String q(String s) { return JSONObject.quote(s == null ? "" : s); }
}
