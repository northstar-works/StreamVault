package com.sidscri.streamvault;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.ActivityInfo;
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

import org.json.JSONObject;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {

    private static final int FILE_CHOOSER_REQUEST = 1001;
    private static final int RECORDING_DIR_REQUEST = 1003;
    private static final int BACKUP_DIR_REQUEST = 1004;
    private WebView webView;
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
        public void playStream(String failoverJson, String title,
            String category, String nowNext,
            String foTimeoutStr, String foAutoStr, String savePathStr) {
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

                if (savePathStr != null && !savePathStr.isEmpty()) {
                    intent.putExtra(PlayerActivity.EXTRA_SAVE_PATH, savePathStr);
                }

                startActivity(intent);
            });
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
    }

    private void openDirectoryPicker(int requestCode) {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            startActivityForResult(intent, requestCode);
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
}
