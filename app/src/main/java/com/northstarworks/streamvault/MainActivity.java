package com.northstarworks.streamvault;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.view.KeyEvent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;

import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class MainActivity extends Activity {

    // ── Network Inject Server ────────────────────────────────────────────────
    private static final int INJECT_PORT = 7654;
    private ServerSocket injectServer;
    private Thread injectServerThread;

    private boolean isTvDevice() {
        int uiMode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_TYPE_MASK;
        return uiMode == Configuration.UI_MODE_TYPE_TELEVISION || getPackageManager().hasSystemFeature("android.software.leanback");
    }

    private static final int FILE_CHOOSER_REQUEST = 1001;
    private static final int LOCAL_STREAM_FILE_REQUEST = 1002;
    private static final int RECORDING_DIR_REQUEST = 1003;
    private static final int BACKUP_DIR_REQUEST = 1004;
    private static final int BACKUP_FILE_REQUEST = 1005;
    private WebView webView;
    // Static ref for PlayerActivity to call back into JS
    public static WebView webViewRef;
    private FrameLayout fullscreenContainer;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private ValueCallback<Uri[]> fileUploadCallback;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(Color.TRANSPARENT);
            getWindow().setNavigationBarColor(Color.parseColor("#0a0a0f"));
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1002);
            }
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#0a0a0f"));

        fullscreenContainer = new FrameLayout(this);
        fullscreenContainer.setVisibility(View.GONE);
        fullscreenContainer.setBackgroundColor(Color.BLACK);

        webView = new WebView(this);
        setupWebView();

        root.addView(webView,
            new FrameLayout.LayoutParams(-1, -1));
        root.addView(fullscreenContainer,
            new FrameLayout.LayoutParams(-1, -1));

        setContentView(root);
        webView.loadUrl("file:///android_asset/index.html");
        startNetworkInjectServer();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setAllowFileAccess(true);
        ws.setAllowContentAccess(true);
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        ws.setDatabaseEnabled(true);
        ws.setJavaScriptCanOpenWindowsAutomatically(true);
        ws.setUserAgentString(ws.getUserAgentString() + " StreamVault/4.1");
        ws.setAllowUniversalAccessFromFileURLs(true);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cm.setAcceptThirdPartyCookies(webView, true);
        }

        webView.addJavascriptInterface(new NativeBridge(), "NativePlayer");

        webViewRef = webView;
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view,
                WebResourceRequest request) {
                view.loadUrl(request.getUrl().toString());
                return true;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView webView,
                ValueCallback<Uri[]> filePathCallback,
                FileChooserParams fileChooserParams) {

                if (fileUploadCallback != null) {
                    fileUploadCallback.onReceiveValue(null);
                }
                fileUploadCallback = filePathCallback;

                try {
                    Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                    intent.setType("*/*");
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    Intent chooser = Intent.createChooser(intent, "Choose file");
                    startActivityForResult(chooser, FILE_CHOOSER_REQUEST);
                } catch (Exception e) {
                    fileUploadCallback = null;
                    return false;
                }
                return true;
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                customView = view;
                customViewCallback = callback;
                fullscreenContainer.addView(customView);
                fullscreenContainer.setVisibility(View.VISIBLE);
                webView.setVisibility(View.GONE);
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR);
            }

            @Override
            public void onHideCustomView() {
                if (customView == null) return;
                fullscreenContainer.removeView(customView);
                fullscreenContainer.setVisibility(View.GONE);
                webView.setVisibility(View.VISIBLE);
                if (customViewCallback != null) {
                    customViewCallback.onCustomViewHidden();
                }
                customView = null;
                customViewCallback = null;
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
            }
        });

        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
    }

    public class NativeBridge {
        @JavascriptInterface
        public boolean isAvailable() {
            return true;
        }

        @JavascriptInterface
        public boolean isTv() {
            return isTvDevice();
        }

        @JavascriptInterface
        public void playStream(String failoverJson, String title,
            String category, String nowNext,
            String foTimeoutStr, String foAutoStr, String savePathStr) {
            playStreamEx(failoverJson, title, category, nowNext, foTimeoutStr, foAutoStr, savePathStr, "", "0");
        }

        @JavascriptInterface
        public void playStreamEx(String failoverJson, String title,
            String category, String nowNext,
            String foTimeoutStr, String foAutoStr, String savePathStr,
            String itemId, String seekMsStr) {
            playStreamExTs(failoverJson, title, category, nowNext, foTimeoutStr, foAutoStr, savePathStr, itemId, seekMsStr, "true");
        }

        @JavascriptInterface
        public void playStreamExTs(String failoverJson, String title,
            String category, String nowNext,
            String foTimeoutStr, String foAutoStr, String savePathStr,
            String itemId, String seekMsStr, String tsEnabledStr) {
            runOnUiThread(() -> {
                Intent intent = new Intent(MainActivity.this, PlayerActivity.class);
                intent.putExtra(PlayerActivity.EXTRA_FAILOVER_JSON,
                    failoverJson != null ? failoverJson : "[]");
                intent.putExtra(PlayerActivity.EXTRA_TITLE,
                    title != null ? title : "Stream");
                intent.putExtra(PlayerActivity.EXTRA_CATEGORY,
                    category != null ? category : "");
                intent.putExtra(PlayerActivity.EXTRA_NOW_NEXT,
                    nowNext != null ? nowNext : "");

                int timeout = 15;
                try { timeout = Integer.parseInt(foTimeoutStr); } catch (Exception e) {}
                intent.putExtra(PlayerActivity.EXTRA_FO_TIMEOUT, timeout);

                boolean autoFo = !"false".equalsIgnoreCase(foAutoStr);
                intent.putExtra(PlayerActivity.EXTRA_FO_AUTO, autoFo);

                if (savePathStr != null && !savePathStr.isEmpty())
                    intent.putExtra(PlayerActivity.EXTRA_SAVE_PATH, savePathStr);
                if (itemId != null && !itemId.isEmpty())
                    intent.putExtra(PlayerActivity.EXTRA_ITEM_ID, itemId);

                long seekMs = 0;
                try { seekMs = Long.parseLong(seekMsStr); } catch (Exception e) {}
                if (seekMs > 0) intent.putExtra(PlayerActivity.EXTRA_SEEK_MS, seekMs);

                // Parse "true|30" format (enabled|bufferMinutes)
                boolean tsEnabled = tsEnabledStr != null && !tsEnabledStr.startsWith("false");
                int tsMaxMin = 30;
                if (tsEnabledStr != null && tsEnabledStr.contains(":")) {
                    try { tsMaxMin = Integer.parseInt(tsEnabledStr.split(":")[1]); } catch (Exception ignored) {}
                }
                intent.putExtra("ts_enabled", tsEnabled);
                intent.putExtra("ts_max_min", tsMaxMin);

                startActivity(intent);
            });
        }


        @JavascriptInterface
        public void showKeyboard() {
            runOnUiThread(() -> {
                try {
                    webView.requestFocus();
                    InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                    if (imm != null) {
                        imm.showSoftInput(webView, InputMethodManager.SHOW_IMPLICIT);
                        imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0);
                    }
                } catch (Exception ignored) {}
            });
        }


        @JavascriptInterface
        public void hideKeyboard() {
            runOnUiThread(() -> {
                try {
                    InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                    if (imm != null) {
                        imm.hideSoftInputFromWindow(webView.getWindowToken(), 0);
                        View current = getCurrentFocus();
                        if (current != null) {
                            imm.hideSoftInputFromWindow(current.getWindowToken(), 0);
                        }
                    }
                    webView.clearFocus();
                    webView.requestFocus();
                } catch (Exception ignored) {}
            });
        }

        @JavascriptInterface
        public void setHdhrTranscode(String quality) {
            String q = (quality != null && !quality.isEmpty()) ? quality : "mobile";
            getSharedPreferences("sv_prefs", MODE_PRIVATE).edit()
                .putString("hdhr_transcode", q).apply();
        }

        @JavascriptInterface
        public void setHdhrProxyEnabled(String enabled) {
            // Persist mobile proxy toggle so PlayerActivity skips the ?transcode hack
            boolean on = "true".equalsIgnoreCase(enabled);
            getSharedPreferences("sv_prefs", MODE_PRIVATE).edit()
                .putBoolean("hdhr_proxy_enabled", on).apply();
        }

        @JavascriptInterface
        public void scanHdhrDevices() {
            runOnUiThread(() -> toastNative("Scanning network for HDHomeRun devices…"));
            new Thread(() -> {
                String json = scanHdhrDevicesJson();
                runOnUiThread(() -> notifyJsHdhrScanResults(json));
            }).start();
        }

        @JavascriptInterface
        public void pickLocalStreamFile() {
            runOnUiThread(() -> openLocalFilePicker());
        }

        @JavascriptInterface
        public void pickRecordingDirectory() {
            runOnUiThread(() -> openDirectoryPicker(RECORDING_DIR_REQUEST));
        }

        @JavascriptInterface
        public void pickBackupDirectory() {
            runOnUiThread(() -> openDirectoryPicker(BACKUP_DIR_REQUEST));
        }

        @JavascriptInterface
        public void exportBackup(String backupJson, String filename, String targetDir) {
            runOnUiThread(() -> exportBackupToStorage(backupJson, filename, targetDir));
        }

        @JavascriptInterface
        public void pickBackupFile() {
            runOnUiThread(() -> openBackupFilePicker());
        }

        @JavascriptInterface
        public void scanInjectFile() {
            // Scan all external storage volumes for inject/*.json
            // Called by the "Inject from USB" button in Settings
            runOnUiThread(() -> scanUsbInjectFile());
        }

        @JavascriptInterface
        public void readBackupFile(String uriOrPath) {
            runOnUiThread(() -> {
                new Thread(() -> {
                    try {
                        String jsonText;
                        if (uriOrPath.startsWith("content://")) {
                            android.net.Uri uri = android.net.Uri.parse(uriOrPath);
                            try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) {}
                            try (InputStream is = getContentResolver().openInputStream(uri)) {
                                if (is == null) throw new IllegalStateException("Cannot open file");
                                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                                byte[] buf = new byte[8192]; int len;
                                while ((len = is.read(buf)) != -1) bos.write(buf, 0, len);
                                jsonText = bos.toString(StandardCharsets.UTF_8.name());
                            }
                        } else {
                            jsonText = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(uriOrPath)), StandardCharsets.UTF_8);
                        }
                        final String jt = jsonText;
                        final String label = uriOrPath.contains("/") ? uriOrPath.substring(uriOrPath.lastIndexOf('/')+1) : uriOrPath;
                        runOnUiThread(() -> notifyJsBackupFilePicked(jt, label));
                    } catch (Exception e) {
                        runOnUiThread(() -> notifyJsBackupError(e.getMessage() != null ? e.getMessage() : String.valueOf(e)));
                    }
                }).start();
            });
        }
    }

    // ── Network Inject HTTP Server ───────────────────────────────────────────
    // Listens on port 7654 for config pushes from the PC Configurator.
    //   GET  /ping   → {"status":"ok","app":"StreamVault"}
    //   POST /inject → body = backup JSON → calls onInjectFileFound in JS

    private void startNetworkInjectServer() {
        injectServerThread = new Thread(() -> {
            try {
                injectServer = new ServerSocket(INJECT_PORT);
                android.util.Log.i("SV-Inject", "Network inject server listening on port " + INJECT_PORT);
                while (!injectServer.isClosed()) {
                    try {
                        Socket client = injectServer.accept();
                        new Thread(() -> handleInjectClient(client)).start();
                    } catch (Exception e) {
                        if (!injectServer.isClosed())
                            android.util.Log.w("SV-Inject", "Accept error: " + e.getMessage());
                    }
                }
            } catch (Exception e) {
                android.util.Log.e("SV-Inject", "Server failed to start: " + e.getMessage());
            }
        });
        injectServerThread.setDaemon(true);
        injectServerThread.start();
    }

    private void handleInjectClient(Socket client) {
        try {
            client.setSoTimeout(8000);
            java.io.InputStream rawIn = client.getInputStream();
            java.io.OutputStream out  = client.getOutputStream();
            java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.InputStreamReader(rawIn, StandardCharsets.UTF_8));

            // --- Parse request line ---
            String requestLine = br.readLine();
            if (requestLine == null) { client.close(); return; }
            String[] rParts = requestLine.split(" ", 3);
            String method = rParts.length > 0 ? rParts[0].toUpperCase() : "GET";
            String path   = rParts.length > 1 ? rParts[1] : "/";

            // --- Read headers ---
            int contentLength = 0;
            String hLine;
            while ((hLine = br.readLine()) != null && !hLine.isEmpty()) {
                if (hLine.toLowerCase().startsWith("content-length:")) {
                    try { contentLength = Integer.parseInt(hLine.substring(15).trim()); }
                    catch (Exception ignored) {}
                }
            }

            // CORS headers so Electron renderer fetch() works too
            final String CORS = "Access-Control-Allow-Origin: *\r\n" +
                                "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
                                "Access-Control-Allow-Headers: Content-Type\r\n";

            if ("OPTIONS".equals(method)) {
                sendHttpResponse(out, "200 OK", CORS, "");
                client.close(); return;
            }

            if ("GET".equals(method) && "/ping".equals(path)) {
                String body = "{\"status\":\"ok\",\"app\":\"StreamVault\",\"port\":" + INJECT_PORT + "}";
                sendHttpResponse(out, "200 OK", CORS + "Content-Type: application/json\r\n", body);
                client.close(); return;
            }

            if ("GET".equals(method) && "/backup".equals(path)) {
                // Pull current config from app JS → return as backup JSON
                final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
                final String[] holder = {null};
                runOnUiThread(() -> webView.evaluateJavascript(
                    "(function(){try{return JSON.stringify(typeof makeBackupPayload==='function'?makeBackupPayload():null);}catch(e){return null;}})()",
                    value -> {
                        if (value != null && !value.equals("null")) {
                            try { holder[0] = new org.json.JSONArray("[" + value + "]").getString(0); } catch (Exception ignored) {}
                        }
                        latch.countDown();
                    }
                ));
                try { latch.await(8, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
                if (holder[0] != null) {
                    sendHttpResponse(out, "200 OK",
                        CORS + "Content-Type: application/json\r\nContent-Disposition: attachment; filename=\"streamvault-backup.json\"\r\n",
                        holder[0]);
                } else {
                    sendHttpResponse(out, "503 Service Unavailable", CORS + "Content-Type: application/json\r\n",
                        "{\"error\":\"Could not retrieve backup from app\"}");
                }
                client.close(); return;
            }

            if ("POST".equals(method) && "/inject".equals(path)) {
                if (contentLength <= 0 || contentLength > 8 * 1024 * 1024) {
                    sendHttpResponse(out, "400 Bad Request", CORS, "Invalid Content-Length");
                    client.close(); return;
                }
                // Read body (BufferedReader may have buffered some; use raw stream for remainder)
                char[] buf = new char[contentLength];
                int read = 0;
                while (read < contentLength) {
                    int n = br.read(buf, read, contentLength - read);
                    if (n < 0) break;
                    read += n;
                }
                String jsonText = new String(buf, 0, read);
                if (!jsonText.trim().startsWith("{")) {
                    sendHttpResponse(out, "400 Bad Request", CORS, "Body must be JSON object");
                    client.close(); return;
                }
                // ACK immediately, then apply on UI thread
                sendHttpResponse(out, "200 OK", CORS + "Content-Type: application/json\r\n", "{\"status\":\"ok\"}");
                client.close();
                final String jt = jsonText;
                runOnUiThread(() -> {
                    String escaped = JSONObject.quote(jt);
                    webView.evaluateJavascript(
                        "window.onInjectFileFound && window.onInjectFileFound(" + escaped + ",'network-inject.json');",
                        null);
                    toastNative("📡 Network inject received — applying settings…");
                });
                return;
            }

            sendHttpResponse(out, "404 Not Found", CORS, "Not found");
            client.close();
        } catch (Exception e) {
            android.util.Log.w("SV-Inject", "Client handler error: " + e.getMessage());
            try { client.close(); } catch (Exception ignored) {}
        }
    }

    private void sendHttpResponse(java.io.OutputStream out, String status,
                                  String extraHeaders, String body) throws java.io.IOException {
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        String headers = "HTTP/1.1 " + status + "\r\n" +
            extraHeaders +
            "Content-Length: " + bodyBytes.length + "\r\n" +
            "Connection: close\r\n\r\n";
        out.write(headers.getBytes(StandardCharsets.UTF_8));
        if (bodyBytes.length > 0) out.write(bodyBytes);
        out.flush();
    }

    // ── USB Inject File Scanner ──────────────────────────────────────────────
    private void scanUsbInjectFile() {
        new Thread(() -> {
            try {
                java.util.List<java.io.File> volumeRoots = new java.util.ArrayList<>();
                StringBuilder debugPaths = new StringBuilder();

                // Strategy 1: StorageManager (API 24+) — most reliable on Fire TV
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                    try {
                        android.os.storage.StorageManager sm =
                            (android.os.storage.StorageManager) getSystemService(STORAGE_SERVICE);
                        if (sm != null) {
                            for (android.os.storage.StorageVolume sv : sm.getStorageVolumes()) {
                                if (sv.isPrimary()) continue;
                                try {
                                    java.lang.reflect.Method m = sv.getClass().getMethod("getPath");
                                    Object result = m.invoke(sv);
                                    if (result != null) {
                                        java.io.File f = new java.io.File(result.toString());
                                        if (f.exists() && !volumeRoots.contains(f)) {
                                            volumeRoots.add(f);
                                            debugPaths.append("[SM] ").append(f.getAbsolutePath()).append("\n");
                                        }
                                    }
                                } catch (Exception ignored) {}
                            }
                        }
                    } catch (Exception ignored) {}
                }

                // Strategy 2: getExternalFilesDirs — navigate up 4 levels to volume root
                try {
                    java.io.File[] appDirs = getExternalFilesDirs(null);
                    if (appDirs != null) {
                        for (java.io.File d : appDirs) {
                            if (d == null) continue;
                            java.io.File vol = d;
                            for (int i = 0; i < 4 && vol != null; i++) vol = vol.getParentFile();
                            if (vol != null && !volumeRoots.contains(vol)) {
                                volumeRoots.add(vol);
                                debugPaths.append("[ED] ").append(vol.getAbsolutePath()).append("\n");
                            }
                        }
                    }
                } catch (Exception ignored) {}

                // Strategy 3: /mnt/media_rw/ — Fire TV's actual USB mount point, plus fallbacks
                for (String base : new String[]{"/mnt/media_rw", "/mnt/usb_storage", "/mnt/usb", "/storage"}) {
                    try {
                        java.io.File dir = new java.io.File(base);
                        if (!dir.exists()) continue;
                        java.io.File[] entries = dir.listFiles();
                        if (entries == null) {
                            debugPaths.append("[").append(base).append("] listFiles=null\n");
                            continue;
                        }
                        for (java.io.File e : entries) {
                            if (e.getName().equalsIgnoreCase("emulated") ||
                                e.getName().equalsIgnoreCase("self")) continue;
                            if (e.isDirectory() && !volumeRoots.contains(e)) {
                                volumeRoots.add(e);
                                debugPaths.append("[").append(base).append("] ").append(e.getAbsolutePath()).append("\n");
                            }
                        }
                    } catch (Exception ignored) {}
                }

                // Search each volume root for inject/*.json
                java.io.File foundFile = null;
                for (java.io.File root : volumeRoots) {
                    try {
                        java.io.File injectDir = new java.io.File(root, "inject");
                        debugPaths.append("Checking: ").append(injectDir.getAbsolutePath())
                                  .append(" exists=").append(injectDir.exists()).append("\n");
                        if (!injectDir.exists() || !injectDir.isDirectory()) continue;
                        java.io.File[] jsonFiles = injectDir.listFiles(
                            f -> f.isFile() && f.getName().toLowerCase().endsWith(".json"));
                        if (jsonFiles != null && jsonFiles.length > 0) {
                            java.util.Arrays.sort(jsonFiles, (a, b) -> b.getName().compareToIgnoreCase(a.getName()));
                            foundFile = jsonFiles[0];
                            break;
                        }
                    } catch (Exception ignored) {}
                }

                if (foundFile == null) {
                    final String msg = "No inject/*.json found. Checked:\n" + debugPaths;
                    runOnUiThread(() -> {
                        String js = "window.onInjectFileNotFound && window.onInjectFileNotFound(" + quoteJs(msg) + ");";
                        webView.evaluateJavascript(js, null);
                    });
                    return;
                }

                final java.io.File injectFile = foundFile;
                String jsonText = new String(
                    java.nio.file.Files.readAllBytes(injectFile.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
                final String jt = jsonText;
                final String label = injectFile.getName();
                runOnUiThread(() -> {
                    String js = "window.onInjectFileFound && window.onInjectFileFound("
                        + quoteJs(jt) + "," + quoteJs(label) + ");";
                    webView.evaluateJavascript(js, null);
                });

            } catch (Exception e) {
                final String msg = e.getMessage() != null ? e.getMessage() : String.valueOf(e);
                runOnUiThread(() -> {
                    String js = "window.onInjectFileNotFound && window.onInjectFileNotFound(" + quoteJs(msg) + ");";
                    webView.evaluateJavascript(js, null);
                    toastNative("USB inject scan failed: " + msg);
                });
            }
        }).start();
    }

    private void openLocalFilePicker() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "video/*", "audio/*", "application/x-mpegURL", "application/vnd.apple.mpegurl", "application/octet-stream"
            });
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(Intent.createChooser(intent, "Choose local stream or media file"), LOCAL_STREAM_FILE_REQUEST);
        } catch (Exception e) {
            toastNative("File picker unavailable: " + e.getMessage());
        }
    }


    private void openBackupFilePicker() {
        // TV: use our custom in-app DPad-friendly picker
        if (isTvDevice()) {
            listBackupFilesForJs();
            return;
        }
        // Mobile: open system picker with NO MIME type filter so .json is always selectable.
        // Android frequently mis-tags .json as text/plain; a strict filter greys them out.
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");   // no filter — all files shown and selectable
            // NO EXTRA_MIME_TYPES — that's what was blocking .json selection
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                          | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            // Try to open in Downloads/StreamVault directly
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                try {
                    // Build a URI pointing to Downloads/StreamVault folder
                    String docId = "primary:Download/StreamVault";
                    android.net.Uri folderUri = android.provider.DocumentsContract.buildDocumentUri(
                        "com.android.externalstorage.documents", docId);
                    intent.putExtra("android.provider.extra.INITIAL_URI", folderUri);
                } catch (Exception ignored) {}
            }
            startActivityForResult(intent, BACKUP_FILE_REQUEST);
        } catch (Exception e) {
            // Fallback: try without INITIAL_URI
            try {
                Intent fallback = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                fallback.addCategory(Intent.CATEGORY_OPENABLE);
                fallback.setType("*/*");
                fallback.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivityForResult(fallback, BACKUP_FILE_REQUEST);
            } catch (Exception e2) {
                toastNative("Backup picker unavailable: " + e2.getMessage());
            }
        }
    }

    /** Scan Downloads/StreamVault for .json backup files and send list to JS */
    private void listBackupFilesForJs() {
        new Thread(() -> {
            try {
                java.util.List<String[]> files = new java.util.ArrayList<>();

                // Try content:// MediaStore first (API 29+)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    android.net.Uri collection = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI;
                    String[] projection = {
                        android.provider.MediaStore.Downloads._ID,
                        android.provider.MediaStore.Downloads.DISPLAY_NAME,
                        android.provider.MediaStore.Downloads.DATE_MODIFIED,
                        android.provider.MediaStore.Downloads.SIZE
                    };
                    String selection = android.provider.MediaStore.Downloads.DISPLAY_NAME + " LIKE ?" +
                        " AND " + android.provider.MediaStore.Downloads.RELATIVE_PATH + " LIKE ?";
                    String[] selArgs = { "streamvault-backup-%.json", "%StreamVault%" };
                    try (android.database.Cursor cursor = getContentResolver().query(
                            collection, projection, selection, selArgs,
                            android.provider.MediaStore.Downloads.DATE_MODIFIED + " DESC")) {
                        if (cursor != null) {
                            while (cursor.moveToNext()) {
                                long id = cursor.getLong(0);
                                String name = cursor.getString(1);
                                long date = cursor.getLong(2) * 1000L;
                                long size = cursor.getLong(3);
                                android.net.Uri uri = android.content.ContentUris.withAppendedId(collection, id);
                                files.add(new String[]{name, uri.toString(),
                                    new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(new java.util.Date(date)),
                                    (size/1024) + " KB"});
                            }
                        }
                    } catch (Exception ignored) {}
                }

                // Also check filesystem (for older APIs or manually saved files)
                if (files.isEmpty()) {
                    java.io.File dir = new java.io.File(
                        android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
                        "StreamVault");
                    if (dir.exists()) {
                        java.io.File[] jsonFiles = dir.listFiles(f -> f.getName().endsWith(".json"));
                        if (jsonFiles != null) {
                            java.util.Arrays.sort(jsonFiles, (a,b) -> Long.compare(b.lastModified(), a.lastModified()));
                            for (java.io.File f : jsonFiles) {
                                files.add(new String[]{f.getName(), f.getAbsolutePath(),
                                    new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(new java.util.Date(f.lastModified())),
                                    (f.length()/1024) + " KB"});
                            }
                        }
                    }
                }

                final java.util.List<String[]> result = files;
                runOnUiThread(() -> {
                    if (result.isEmpty()) {
                        String js = "window.onBackupFileList && window.onBackupFileList([])";
                        webView.evaluateJavascript(js, null);
                        return;
                    }
                    StringBuilder json = new StringBuilder("[");
                    for (int i = 0; i < result.size(); i++) {
                        String[] f = result.get(i);
                        if (i > 0) json.append(",");
                        json.append("{")
                            .append("\"name\":").append(quoteJs(f[0]))
                            .append(",\"uri\":").append(quoteJs(f[1]))
                            .append(",\"date\":").append(quoteJs(f[2]))
                            .append(",\"size\":").append(quoteJs(f[3]))
                            .append("}");
                    }
                    json.append("]");
                    String js = "window.onBackupFileList && window.onBackupFileList(" + json + ")";
                    webView.evaluateJavascript(js, null);
                });
            } catch (Exception e) {
                runOnUiThread(() -> toastNative("Could not list backups: " + e.getMessage()));
            }
        }).start();
    }

    private void openDirectoryPicker(int requestCode) {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            startActivityForResult(Intent.createChooser(intent, requestCode == RECORDING_DIR_REQUEST ? "Choose recording folder" : "Choose backup folder"), requestCode);
        } catch (Exception e) {
            toastNative("Folder picker unavailable: " + e.getMessage());
        }
    }

    private void exportBackupToStorage(String backupJson, String filename, String targetDir) {
        String safeFilename = (filename != null && !filename.trim().isEmpty())
            ? filename.trim() : ("streamvault-backup-" + System.currentTimeMillis() + ".json");

        try {
            String savedPath;
            if (targetDir != null && targetDir.startsWith("content://")) {
                Uri treeUri = Uri.parse(targetDir);
                final int takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
                try { getContentResolver().takePersistableUriPermission(treeUri, takeFlags); } catch (Exception ignored) {}
                DocumentFile tree = DocumentFile.fromTreeUri(this, treeUri);
                if (tree == null || (!tree.exists() && !tree.canWrite())) {
                    throw new IllegalStateException("Selected backup folder is not available");
                }
                DocumentFile existing = tree.findFile(safeFilename);
                if (existing != null) existing.delete();
                DocumentFile out = tree.createFile("application/json", safeFilename);
                if (out == null) throw new IllegalStateException("Could not create backup file");
                try (OutputStream os = getContentResolver().openOutputStream(out.getUri(), "w")) {
                    if (os == null) throw new IllegalStateException("Could not open backup file");
                    os.write(backupJson.getBytes(StandardCharsets.UTF_8));
                }
                savedPath = describeTreeUri(treeUri) + "/" + safeFilename;
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, safeFilename);
                values.put(MediaStore.Downloads.MIME_TYPE, "application/json");
                values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/StreamVault");
                values.put(MediaStore.Downloads.IS_PENDING, 1);
                ContentResolver resolver = getContentResolver();
                Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri == null) throw new IllegalStateException("Could not create backup in Downloads");
                try (OutputStream os = resolver.openOutputStream(uri, "w")) {
                    if (os == null) throw new IllegalStateException("Could not open backup output stream");
                    os.write(backupJson.getBytes(StandardCharsets.UTF_8));
                }
                values.clear();
                values.put(MediaStore.Downloads.IS_PENDING, 0);
                resolver.update(uri, values, null, null);
                savedPath = "Downloads/StreamVault/" + safeFilename;
            } else {
                File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "StreamVault");
                if (!dir.exists() && !dir.mkdirs()) {
                    throw new IllegalStateException("Could not create Downloads/StreamVault");
                }
                File outFile = new File(dir, safeFilename);
                try (FileOutputStream fos = new FileOutputStream(outFile)) {
                    fos.write(backupJson.getBytes(StandardCharsets.UTF_8));
                }
                savedPath = outFile.getAbsolutePath();
            }
            notifyJsBackupSaved(savedPath);
            toastNative("Backup saved to " + savedPath);
        } catch (Exception e) {
            notifyJsBackupError(e.getMessage() != null ? e.getMessage() : String.valueOf(e));
            toastNative("Backup failed: " + e.getMessage());
        }
    }

    private String describeTreeUri(Uri treeUri) {
        try {
            String docId = android.provider.DocumentsContract.getTreeDocumentId(treeUri);
            if (docId == null || docId.isEmpty()) return "Selected folder";
            int idx = docId.indexOf(':');
            String vol = idx >= 0 ? docId.substring(0, idx) : docId;
            String path = idx >= 0 ? docId.substring(idx + 1) : "";
            String base;
            if ("primary".equalsIgnoreCase(vol)) base = "Internal storage";
            else base = vol;
            return path.isEmpty() ? base : (base + "/" + path);
        } catch (Exception e) {
            return "Selected folder";
        }
    }

    private void sendDirectoryPickedEvent(String kind, Uri uri) {
        String label = describeTreeUri(uri);
        String js = "window.onNativeDirectoryPicked && window.onNativeDirectoryPicked(" +
            quoteJs(kind) + "," + quoteJs(uri.toString()) + "," + quoteJs(label) + ");";
        webView.evaluateJavascript(js, null);
    }

    private void notifyJsBackupSaved(String savedPath) {
        String js = "window.onNativeBackupSaved && window.onNativeBackupSaved(" + quoteJs(savedPath) + ");";
        webView.evaluateJavascript(js, null);
    }

    private void notifyJsBackupError(String message) {
        String js = "window.onNativeBackupError && window.onNativeBackupError(" + quoteJs(message) + ");";
        webView.evaluateJavascript(js, null);
    }


    private void notifyJsBackupFilePicked(String jsonText, String label) {
        String js = "window.onNativeBackupFilePicked && window.onNativeBackupFilePicked(" + quoteJs(jsonText) + "," + quoteJs(label) + ");";
        webView.evaluateJavascript(js, null);
    }

    private void notifyJsHdhrScanResults(String jsonText) {
        String js = "window.onNativeHdhrScanResults && window.onNativeHdhrScanResults(" + quoteJs(jsonText) + ");";
        webView.evaluateJavascript(js, null);
    }

    private String scanHdhrDevicesJson() {
        org.json.JSONArray arr = new org.json.JSONArray();
        try {
            List<String> bases = getPrivateIpv4Bases();
            ExecutorService pool = Executors.newFixedThreadPool(24);
            List<Callable<org.json.JSONObject>> tasks = new ArrayList<>();
            for (String base : bases) {
                for (int i = 1; i <= 254; i++) {
                    final String ip = base + i;
                    tasks.add(() -> probeHdhr(ip));
                }
            }
            List<Future<org.json.JSONObject>> futures = pool.invokeAll(tasks);
            pool.shutdownNow();
            java.util.HashSet<String> seen = new java.util.HashSet<>();
            for (Future<org.json.JSONObject> f : futures) {
                try {
                    org.json.JSONObject o = f.get();
                    if (o == null) continue;
                    String key = o.optString("DeviceID", o.optString("ip", ""));
                    if (seen.contains(key)) continue;
                    seen.add(key);
                    arr.put(o);
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return arr.toString();
    }

    private List<String> getPrivateIpv4Bases() {
        List<String> bases = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (!(addr instanceof Inet4Address) || addr.isLoopbackAddress()) continue;
                    String host = addr.getHostAddress();
                    if (host == null) continue;
                    if (!(host.startsWith("192.168.") || host.startsWith("10.") || host.matches("172\\.(1[6-9]|2\\d|3[0-1])\\..*"))) continue;
                    int lastDot = host.lastIndexOf('.');
                    if (lastDot > 0) {
                        String base = host.substring(0, lastDot + 1);
                        if (!bases.contains(base)) bases.add(base);
                    }
                }
            }
        } catch (Exception ignored) {}
        if (bases.isEmpty()) bases.add("192.168.1.");
        return bases;
    }

    private org.json.JSONObject probeHdhr(String ip) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL("http://" + ip + "/discover.json");
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(220);
            conn.setReadTimeout(420);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/json");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) return null;
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (InputStream is = conn.getInputStream()) {
                byte[] buf = new byte[2048];
                int len;
                while ((len = is.read(buf)) != -1) bos.write(buf, 0, len);
            }
            String body = bos.toString(StandardCharsets.UTF_8.name());
            org.json.JSONObject o = new org.json.JSONObject(body);
            if (!o.has("LineupURL") && o.has("BaseURL")) {
                o.put("LineupURL", o.optString("BaseURL") + "/lineup.json");
            }
            if (!o.has("BaseURL")) {
                o.put("BaseURL", "http://" + ip);
            }
            o.put("ip", ip);
            return o;
        } catch (Exception ignored) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private String quoteJs(String value) {
        if (value == null) return "null";
        return JSONObject.quote(value);
    }

    private void toastNative(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == LOCAL_STREAM_FILE_REQUEST) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                Uri uri = data.getData();
                try {
                    final int takeFlags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    getContentResolver().takePersistableUriPermission(uri, takeFlags | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Exception ignored) {}
                String label = uri.getLastPathSegment() != null ? uri.getLastPathSegment() : uri.toString();
                webView.evaluateJavascript(
                    "window.onNativeLocalStreamPicked && window.onNativeLocalStreamPicked(" + quoteJs(uri.toString()) + "," + quoteJs(label) + ");", null);
            }
            return;
        }

        if (requestCode == FILE_CHOOSER_REQUEST && fileUploadCallback != null) {
            Uri[] results = null;
            if (resultCode == RESULT_OK && data != null) {
                String dataString = data.getDataString();
                if (dataString != null) {
                    results = new Uri[]{Uri.parse(dataString)};
                }
            }
            fileUploadCallback.onReceiveValue(results);
            fileUploadCallback = null;
            return;
        }

        if (requestCode == BACKUP_FILE_REQUEST) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                final Uri uri = data.getData();
                // Read on background thread — file could be large
                new Thread(() -> {
                    try {
                        // Persist read permission
                        try {
                            final int tf = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                            getContentResolver().takePersistableUriPermission(uri, tf | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        } catch (Exception ignored) {}

                        try (InputStream is = getContentResolver().openInputStream(uri)) {
                            if (is == null) throw new IllegalStateException("Could not open backup file");
                            ByteArrayOutputStream bos = new ByteArrayOutputStream();
                            byte[] buf = new byte[8192];
                            int len;
                            while ((len = is.read(buf)) != -1) { bos.write(buf, 0, len); }
                            String jsonText = bos.toString(StandardCharsets.UTF_8.name());
                            // Basic validation — must start with {
                            String trimmed = jsonText.trim();
                            if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
                                throw new IllegalStateException("File does not appear to be a valid StreamVault backup (.json)");
                            }
                            String seg = uri.getLastPathSegment();
                            String label = seg != null ? seg : uri.toString();
                            // Strip path prefix if present (e.g. "primary:Download/StreamVault/file.json")
                            if (label.contains("/")) label = label.substring(label.lastIndexOf('/') + 1);
                            if (label.contains(":")) label = label.substring(label.lastIndexOf(':') + 1);
                            final String fl = label;
                            final String jt = jsonText;
                            runOnUiThread(() -> notifyJsBackupFilePicked(jt, fl));
                        }
                    } catch (Exception e) {
                        final String msg = e.getMessage() != null ? e.getMessage() : String.valueOf(e);
                        runOnUiThread(() -> {
                            notifyJsBackupError(msg);
                            toastNative("Restore failed: " + msg);
                        });
                    }
                }).start();
            }
            return;
        }

        if ((requestCode == RECORDING_DIR_REQUEST || requestCode == BACKUP_DIR_REQUEST)
            && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            final int takeFlags = data.getFlags()
                & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            try { getContentResolver().takePersistableUriPermission(uri, takeFlags); } catch (Exception ignored) {}
            sendDirectoryPickedEvent(requestCode == RECORDING_DIR_REQUEST ? "recording" : "backup", uri);
        }
    }

    @Override
    public void onBackPressed() {
        if (customView != null) {
            if (customViewCallback != null) {
                customViewCallback.onCustomViewHidden();
            }
            fullscreenContainer.removeView(customView);
            fullscreenContainer.setVisibility(View.GONE);
            webView.setVisibility(View.VISIBLE);
            customView = null;
            customViewCallback = null;
            return;
        }

        webView.evaluateJavascript(
            "(function(){" +
            "var p=document.getElementById('player-scr');" +
            "if(p&&p.classList.contains('on')){" +
            "document.getElementById('p-back').click();" +
            "return 'handled'}" +
            "var b=document.getElementById('br-scr');" +
            "if(b&&b.classList.contains('on')){" +
            "document.getElementById('br-back').click();" +
            "return 'handled'}" +
            "return 'exit'})()",
            value -> {
                if (value != null && value.contains("exit")) {
                    finish();
                }
            }
        );
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
    }

    @Override
    protected void onPause() {
        if (webView != null) webView.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        try { if (injectServer != null && !injectServer.isClosed()) injectServer.close(); } catch (Exception ignored) {}
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
        }
        super.onDestroy();
    }


    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && webView != null && isTvDevice()) {
            int code = event.getKeyCode();
            if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN ||
                code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT ||
                code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER ||
                code == KeyEvent.KEYCODE_NUMPAD_ENTER || code == KeyEvent.KEYCODE_BACK) {
                webView.evaluateJavascript("window.__svHandleTvKey && window.__svHandleTvKey(" + code + ")", null);
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }
}
