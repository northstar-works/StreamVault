package com.sidscri.streamvault;

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

public class MainActivity extends Activity {

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

                boolean tsEnabled = !"false".equalsIgnoreCase(tsEnabledStr);
                intent.putExtra("ts_enabled", tsEnabled);

                startActivity(intent);
            });
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

    /** Read a backup file by URI or filesystem path and send to JS */
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
