package com.sidscri.streamvault;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.OptIn;
import androidx.documentfile.provider.DocumentFile;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.rtsp.RtspMediaSource;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.ui.PlayerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@OptIn(markerClass = UnstableApi.class)
public class PlayerActivity extends Activity {

    public static final String EXTRA_URL = "stream_url";
    public static final String EXTRA_TITLE = "stream_title";
    public static final String EXTRA_CATEGORY = "stream_category";
    public static final String EXTRA_FAILOVER_JSON = "failover_json";
    public static final String EXTRA_NOW_NEXT = "now_next";
    public static final String EXTRA_FO_TIMEOUT = "fo_timeout";
    public static final String EXTRA_FO_AUTO = "fo_auto";
    public static final String EXTRA_SAVE_PATH = "save_path";

    // State
    private ExoPlayer player;
    private PlayerView playerView;
    private View loadingView;
    private View errorContainer;
    private View overlayTop, overlayCenter, overlayBottom;
    private TextView titleText, statusText, nowNextText, strengthText, errorText, recStatusText, sourceInfoText;
    private ImageButton playPauseBtn, lockBtn, recordBtn, prevBtn, nextBtn;
    private Handler handler;
    private Runnable hideOverlayRunnable;
    private Runnable failoverTimeoutRunnable;
    private Runnable strengthUpdater;
    private boolean overlayVisible = false;
    private boolean locked = false;
    private boolean networkAvailable = true;
    private volatile boolean recording = false;
    private String lastSuccessUrl = null;
    private String savePath = null;
    private long playbackStartTime = 0;
    private long foTimeoutMs = 15000;
    private long recordingStartTime = 0;
    private boolean foAuto = true;
    private ConnectivityManager.NetworkCallback networkCallback;
    private Thread recordThread;

    private final List<Variant> variants = new ArrayList<>();
    private int currentIdx = 0;

    static class RecordingTarget {
        final OutputStream outputStream;
        final String displayPath;

        RecordingTarget(OutputStream outputStream, String displayPath) {
            this.outputStream = outputStream;
            this.displayPath = displayPath;
        }
    }

    static class Variant {
        final String url, title, region, tag;
        Variant(String url, String title, String region, String tag) {
            this.url = url != null ? url : "";
            this.title = title != null ? title : "";
            this.region = region != null ? region : "";
            this.tag = tag != null ? tag : "";
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(Color.TRANSPARENT);
            getWindow().setNavigationBarColor(Color.BLACK);
        }

        handler = new Handler(Looper.getMainLooper());
        hideOverlayRunnable = () -> setOverlayVisible(false);
        failoverTimeoutRunnable = () -> {};

        parseIntent();
        if (variants.isEmpty()) { finish(); return; }

        buildUI();
        registerNetworkCallback();
        startStrengthMonitor();
        playVariant(0);
        hideSystemUI();
    }

    // ─── Intent Parsing ───
    private void parseIntent() {
        // Read failover timeout
        foTimeoutMs = getIntent().getIntExtra(EXTRA_FO_TIMEOUT, 15) * 1000L;
        foAuto = getIntent().getBooleanExtra(EXTRA_FO_AUTO, true);
        savePath = getIntent().getStringExtra(EXTRA_SAVE_PATH);

        // Parse failover JSON
        String json = getIntent().getStringExtra(EXTRA_FAILOVER_JSON);
        if (json != null && !json.isEmpty()) {
            try {
                JSONArray arr = new JSONArray(json);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    variants.add(new Variant(
                        o.optString("url", ""),
                        o.optString("title", ""),
                        o.optString("region", ""),
                        o.optString("tag", "")
                    ));
                }
            } catch (Exception e) {
                // Failed to parse JSON — try single URL fallback
            }
        }

        // Fallback: single URL
        if (variants.isEmpty()) {
            String url = getIntent().getStringExtra(EXTRA_URL);
            String title = getIntent().getStringExtra(EXTRA_TITLE);
            if (url != null && !url.isEmpty()) {
                variants.add(new Variant(url, title != null ? title : "Stream", "", ""));
            }
        }
    }

    private String getScheme(String raw) {
        if (raw == null) return "";
        int idx = raw.indexOf(':');
        if (idx <= 0) return "";
        return raw.substring(0, idx).toLowerCase(Locale.US);
    }

    private boolean isLikelyHls(String raw) {
        if (raw == null) return false;
        String v = raw.toLowerCase(Locale.US);
        return v.contains(".m3u8") || v.contains("m3u_plus") || v.contains("type=m3u") || v.contains("output=m3u8");
    }

    // ─── UI ───
    private void buildUI() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        // ExoPlayer view
        playerView = new PlayerView(this);
        playerView.setUseController(false);
        playerView.setKeepScreenOn(true);
        root.addView(playerView, matchParent());

        // Loading spinner
        loadingView = new ProgressBar(this, null, android.R.attr.progressBarStyleLarge);
        root.addView(loadingView, centered(dp(38), dp(38)));

        // Error view
        errorContainer = buildErrorView();
        root.addView(errorContainer, centered(-2, -2));

        // Touch overlay
        View touchCatcher = new View(this);
        touchCatcher.setBackgroundColor(Color.TRANSPARENT);
        GestureDetector gd = new GestureDetector(this,
            new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onSingleTapConfirmed(MotionEvent e) {
                    toggleOverlay();
                    return true;
                }
                @Override
                public boolean onDoubleTap(MotionEvent e) {
                    if (player != null) {
                        if (player.isPlaying()) player.pause();
                        else player.play();
                        updatePlayPauseIcon();
                    }
                    return true;
                }
            });
        touchCatcher.setOnTouchListener((v, e) -> { gd.onTouchEvent(e); return true; });
        root.addView(touchCatcher, matchParent());

        // Overlays
        overlayTop = buildTopOverlay();
        root.addView(overlayTop, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        overlayCenter = buildCenterOverlay();
        root.addView(overlayCenter, centered(-2, -2));

        overlayBottom = buildBottomOverlay();
        root.addView(overlayBottom, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        setOverlayVisible(false);
        setContentView(root);
    }

    private View buildTopOverlay() {
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(8), dp(34), dp(8), dp(8));
        top.setBackgroundColor(Color.parseColor("#CC000000"));

        // Back button
        ImageButton backBtn = makeButton(android.R.drawable.ic_media_previous);
        backBtn.setOnClickListener(v -> finish());
        top.addView(backBtn);

        // Title area
        LinearLayout titleArea = new LinearLayout(this);
        titleArea.setOrientation(LinearLayout.VERTICAL);
        titleArea.setPadding(dp(6), 0, 0, 0);

        titleText = new TextView(this);
        titleText.setTextColor(Color.WHITE);
        titleText.setTextSize(12);
        titleText.setSingleLine(true);
        titleText.setTypeface(null, Typeface.BOLD);
        titleArea.addView(titleText);

        statusText = new TextView(this);
        statusText.setTextColor(Color.parseColor("#80ffffff"));
        statusText.setTextSize(8);
        statusText.setSingleLine(true);
        titleArea.addView(statusText);

        nowNextText = new TextView(this);
        nowNextText.setTextColor(Color.parseColor("#6c5ce7"));
        nowNextText.setTextSize(8);
        nowNextText.setSingleLine(true);
        String nn = getIntent().getStringExtra(EXTRA_NOW_NEXT);
        if (nn != null && !nn.isEmpty()) nowNextText.setText(nn);
        titleArea.addView(nowNextText);

        strengthText = new TextView(this);
        strengthText.setTextColor(Color.parseColor("#60ffffff"));
        strengthText.setTextSize(7);
        strengthText.setSingleLine(true);
        titleArea.addView(strengthText);

        recStatusText = new TextView(this);
        recStatusText.setTextColor(Color.parseColor("#ff4757"));
        recStatusText.setTextSize(7);
        recStatusText.setSingleLine(true);
        titleArea.addView(recStatusText);

        top.addView(titleArea, new LinearLayout.LayoutParams(0, -2, 1));

        // Record button
        recordBtn = makeButton(android.R.drawable.ic_btn_speak_now);
        recordBtn.setColorFilter(Color.parseColor("#80ffffff"));
        recordBtn.setOnClickListener(v -> toggleRecording());
        top.addView(recordBtn);

        // Lock button
        lockBtn = makeButton(android.R.drawable.ic_lock_idle_lock);
        lockBtn.setColorFilter(Color.parseColor("#80ffffff"));
        lockBtn.setOnClickListener(v -> {
            locked = !locked;
            lockBtn.setColorFilter(locked ? Color.parseColor("#ffa502") : Color.parseColor("#80ffffff"));
            showMsg(locked ? "🔒 Locked to this source" : "🔓 Auto-failover enabled");
            scheduleHideOverlay();
        });
        top.addView(lockBtn);

        return top;
    }

    private View buildCenterOverlay() {
        LinearLayout center = new LinearLayout(this);
        center.setGravity(Gravity.CENTER);
        center.setOrientation(LinearLayout.HORIZONTAL);

        prevBtn = makeButton(android.R.drawable.ic_media_previous);
        prevBtn.setBackgroundColor(Color.parseColor("#33000000"));
        prevBtn.setColorFilter(Color.WHITE);
        prevBtn.setOnClickListener(v -> {
            playPreviousVariant();
            scheduleHideOverlay();
        });
        center.addView(prevBtn);

        playPauseBtn = new ImageButton(this);
        playPauseBtn.setImageResource(android.R.drawable.ic_media_pause);
        playPauseBtn.setColorFilter(Color.WHITE);
        playPauseBtn.setBackgroundColor(Color.parseColor("#6c5ce7"));
        playPauseBtn.setPadding(dp(15), dp(15), dp(15), dp(15));
        playPauseBtn.setOnClickListener(v -> {
            if (player != null) {
                if (player.isPlaying()) player.pause();
                else player.play();
                updatePlayPauseIcon();
                scheduleHideOverlay();
            }
        });
        LinearLayout.LayoutParams ppLp = new LinearLayout.LayoutParams(-2, -2);
        ppLp.leftMargin = dp(12);
        ppLp.rightMargin = dp(12);
        center.addView(playPauseBtn, ppLp);

        nextBtn = makeButton(android.R.drawable.ic_media_next);
        nextBtn.setBackgroundColor(Color.parseColor("#33000000"));
        nextBtn.setColorFilter(Color.WHITE);
        nextBtn.setOnClickListener(v -> {
            playNextVariant();
            scheduleHideOverlay();
        });
        center.addView(nextBtn);

        return center;
    }

    private View buildBottomOverlay() {
        LinearLayout bottom = new LinearLayout(this);
        bottom.setPadding(dp(10), dp(8), dp(10), dp(24));
        bottom.setBackgroundColor(Color.parseColor("#CC000000"));
        bottom.setGravity(Gravity.CENTER);

        sourceInfoText = new TextView(this);
        sourceInfoText.setTextColor(Color.parseColor("#40ffffff"));
        sourceInfoText.setTextSize(7);
        sourceInfoText.setSingleLine(true);
        sourceInfoText.setGravity(Gravity.CENTER);
        sourceInfoText.setText("Source 1/" + variants.size());
        bottom.addView(sourceInfoText);

        return bottom;
    }

    private View buildErrorView() {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setGravity(Gravity.CENTER);
        container.setVisibility(View.GONE);

        errorText = new TextView(this);
        errorText.setTextColor(Color.parseColor("#aaaaaa"));
        errorText.setTextSize(12);
        errorText.setGravity(Gravity.CENTER);
        errorText.setPadding(dp(20), 0, dp(20), dp(10));
        container.addView(errorText);

        TextView retryBtn = new TextView(this);
        retryBtn.setText("Tap to Retry");
        retryBtn.setTextColor(Color.parseColor("#6c5ce7"));
        retryBtn.setTextSize(13);
        retryBtn.setPadding(dp(16), dp(8), dp(16), dp(8));
        retryBtn.setBackgroundColor(Color.parseColor("#1a1a2e"));
        retryBtn.setOnClickListener(v -> {
            errorContainer.setVisibility(View.GONE);
            loadingView.setVisibility(View.VISIBLE);
            playVariant(currentIdx);
        });
        container.addView(retryBtn);

        return container;
    }

    // ─── Playback ───
    private void playVariant(int idx) {
        if (idx < 0 || idx >= variants.size()) {
            // All exhausted — try last success
            if (lastSuccessUrl != null) {
                showMsg("Retrying last working source");
                for (int i = 0; i < variants.size(); i++) {
                    if (lastSuccessUrl.equals(variants.get(i).url)) {
                        playVariant(i);
                        return;
                    }
                }
            }
            showAllFailed();
            return;
        }

        currentIdx = idx;
        Variant v = variants.get(idx);
        releasePlayer();
        loadingView.setVisibility(View.VISIBLE);
        errorContainer.setVisibility(View.GONE);

        // Update display
        String display = v.title.isEmpty() ? "Source " + (idx + 1) : v.title;
        if (!v.tag.isEmpty()) display += " (" + v.tag + ")";
        titleText.setText(display);
        statusText.setText("Source " + (idx + 1) + "/" + variants.size()
            + (locked ? " · 🔒" : ""));
        if (sourceInfoText != null) sourceInfoText.setText("Source " + (idx + 1) + "/" + variants.size() + " · Tap ◀ ▶ for previous / next");
        if (prevBtn != null) prevBtn.setAlpha(variants.size() > 1 ? 1f : 0.45f);
        if (nextBtn != null) nextBtn.setAlpha(variants.size() > 1 ? 1f : 0.45f);
        strengthText.setText("");

        if (idx > 0) showMsg("Trying: " + display);

        // Create player
        DataSource.Factory httpFactory = new DefaultHttpDataSource.Factory()
            .setUserAgent("StreamVault/4.3.7 ExoPlayer")
            .setConnectTimeoutMs(12000)
            .setReadTimeoutMs(12000)
            .setAllowCrossProtocolRedirects(true);

        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);
        playbackStartTime = System.currentTimeMillis();

        final String scheme = getScheme(v.url);
        final boolean useRtsp = "rtsp".equals(scheme) || "rtsps".equals(scheme);
        final boolean preferHls = !useRtsp && isLikelyHls(v.url);
        final MediaSource primarySource = useRtsp
            ? new RtspMediaSource.Factory().createMediaSource(MediaItem.fromUri(Uri.parse(v.url)))
            : (preferHls
                ? new HlsMediaSource.Factory(httpFactory)
                    .setAllowChunklessPreparation(true)
                    .createMediaSource(MediaItem.fromUri(Uri.parse(v.url)))
                : new ProgressiveMediaSource.Factory(httpFactory)
                    .createMediaSource(MediaItem.fromUri(Uri.parse(v.url))));

        final boolean[] primaryFailed = {false};

        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    loadingView.setVisibility(View.GONE);
                    errorContainer.setVisibility(View.GONE);
                    lastSuccessUrl = v.url;
                    cancelFailoverTimeout();
                } else if (state == Player.STATE_BUFFERING) {
                    loadingView.setVisibility(View.VISIBLE);
                    if (!locked && foAuto) startFailoverTimeout();
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                cancelFailoverTimeout();
                if (!primaryFailed[0] && preferHls) {
                    primaryFailed[0] = true;
                    try {
                        player.stop();
                        MediaSource progressive = new ProgressiveMediaSource.Factory(httpFactory)
                            .createMediaSource(MediaItem.fromUri(Uri.parse(v.url)));
                        player.setMediaSource(progressive);
                        player.prepare();
                        player.setPlayWhenReady(true);
                    } catch (Exception e) {
                        tryNextVariant();
                    }
                    return;
                }
                // Primary playback failed
                if (!locked && foAuto && networkAvailable) {
                    tryNextVariant();
                } else if (!networkAvailable) {
                    showMsg("Network lost — waiting for reconnect");
                    loadingView.setVisibility(View.VISIBLE);
                } else {
                    showStreamFailed();
                }
            }
        });

        player.setMediaSource(primarySource);
        player.setPlayWhenReady(true);
        player.prepare();

        showOverlayBriefly();
    }

    private void playPreviousVariant() {
        if (variants.isEmpty()) return;
        int next = currentIdx - 1;
        if (next < 0) next = variants.size() - 1;
        playVariant(next);
    }

    private void playNextVariant() {
        if (variants.isEmpty()) return;
        int next = currentIdx + 1;
        if (next >= variants.size()) next = 0;
        playVariant(next);
    }

    private void tryNextVariant() {
        if (locked) return;
        if (currentIdx + 1 < variants.size()) {
            playVariant(currentIdx + 1);
        } else if (lastSuccessUrl != null) {
            showMsg("All alternatives tried — back to last working");
            for (int i = 0; i < variants.size(); i++) {
                if (lastSuccessUrl.equals(variants.get(i).url)) {
                    playVariant(i);
                    return;
                }
            }
            showAllFailed();
        } else {
            showAllFailed();
        }
    }

    private void showAllFailed() {
        loadingView.setVisibility(View.GONE);
        errorContainer.setVisibility(View.VISIBLE);
        errorText.setText("All " + variants.size() + " source(s) failed");
    }

    private void showStreamFailed() {
        loadingView.setVisibility(View.GONE);
        errorContainer.setVisibility(View.VISIBLE);
        errorText.setText("Stream failed");
    }

    // ─── Failover Timeout ───
    private void startFailoverTimeout() {
        cancelFailoverTimeout();
        failoverTimeoutRunnable = () -> {
            if (player != null
                && player.getPlaybackState() == Player.STATE_BUFFERING
                && !locked && foAuto && networkAvailable
                && System.currentTimeMillis() - playbackStartTime > 8000) {
                showMsg("Buffering too long — trying next source");
                tryNextVariant();
            }
        };
        handler.postDelayed(failoverTimeoutRunnable, foTimeoutMs);
    }

    private void cancelFailoverTimeout() {
        if (failoverTimeoutRunnable != null) {
            handler.removeCallbacks(failoverTimeoutRunnable);
        }
    }

    // ─── Stream Strength Monitor ───
    private void startStrengthMonitor() {
        strengthUpdater = new Runnable() {
            @Override
            public void run() {
                if (player != null && player.getPlaybackState() == Player.STATE_READY) {
                    long bitrate = 0;
                    Format vf = player.getVideoFormat();
                    if (vf != null && vf.bitrate > 0) bitrate = vf.bitrate;

                    long buffMs = player.getBufferedPosition() - player.getCurrentPosition();
                    String bps = bitrate > 0 ? (bitrate / 1000) + "kbps" : "";
                    String buf = buffMs > 0 ? (buffMs / 1000) + "s buf" : "";

                    String label;
                    int color;
                    if (buffMs > 10000 && bitrate > 2000000) {
                        label = "●●●● Excellent";
                        color = Color.parseColor("#2ed573");
                    } else if (buffMs > 5000 && bitrate > 1000000) {
                        label = "●●●○ Good";
                        color = Color.parseColor("#2ed573");
                    } else if (buffMs > 2000) {
                        label = "●●○○ Fair";
                        color = Color.parseColor("#ffa502");
                    } else {
                        label = "●○○○ Weak";
                        color = Color.parseColor("#ff4757");
                    }

                    String display = label;
                    if (!bps.isEmpty()) display += " · " + bps;
                    if (!buf.isEmpty()) display += " · " + buf;

                    strengthText.setText(display);
                    strengthText.setTextColor(color);
                }

                // Update recording timer
                if (recording && recordingStartTime > 0) {
                    long elapsed = (System.currentTimeMillis() - recordingStartTime) / 1000;
                    long mins = elapsed / 60;
                    long secs = elapsed % 60;
                    recStatusText.setText("⏺ REC " + String.format(Locale.US, "%02d:%02d", mins, secs));
                } else {
                    recStatusText.setText("");
                }

                handler.postDelayed(this, 2000);
            }
        };
        handler.postDelayed(strengthUpdater, 3000);
    }

    // ─── Recording (HLS-aware) ───
    private void toggleRecording() {
        if (recording) stopRecording();
        else startRecording();
    }

    private void startRecording() {
        if (currentIdx >= variants.size()) return;
        recording = true;
        recordingStartTime = System.currentTimeMillis();
        recordBtn.setColorFilter(Color.parseColor("#ff4757"));
        showMsg("⏺ Recording started");
        scheduleHideOverlay();

        final String streamUrl = variants.get(currentIdx).url;
        final String safeName = variants.get(currentIdx).title.replaceAll("[^a-zA-Z0-9_-]", "_");
        final String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());

        recordThread = new Thread(() -> {
            OutputStream fos = null;
            String outDisplayPath = null;
            try {
                RecordingTarget target = openRecordingTarget("SV_" + safeName + "_" + ts + ".ts");
                fos = target.outputStream;
                outDisplayPath = target.displayPath;
                long totalBytes = 0;

                // Probe the stream to determine if HLS or raw
                HttpURLConnection probe = (HttpURLConnection) new URL(streamUrl).openConnection();
                probe.setRequestProperty("User-Agent", "StreamVault/4.2");
                probe.setConnectTimeout(10000);
                probe.setReadTimeout(10000);
                probe.setInstanceFollowRedirects(true);
                InputStream probeIn = probe.getInputStream();
                byte[] peek = new byte[4096];
                int peekLen = probeIn.read(peek);
                String peekStr = peekLen > 0 ? new String(peek, 0, peekLen) : "";

                boolean isHLS = peekStr.contains("#EXTM3U") || peekStr.contains("#EXT-X-")
                    || streamUrl.contains(".m3u8");

                if (!isHLS) {
                    // Raw stream — pipe directly
                    if (peekLen > 0) { fos.write(peek, 0, peekLen); totalBytes += peekLen; }
                    byte[] buf = new byte[16384];
                    int n;
                    while (recording && (n = probeIn.read(buf)) != -1) {
                        fos.write(buf, 0, n);
                        totalBytes += n;
                    }
                    probeIn.close();
                    fos.close();
                    final long finalBytes = totalBytes;
                    final String savedPath = outDisplayPath;
                    handler.post(() -> showMsg("⏹ Saved to: " + savedPath
                        + " (" + (finalBytes / 1024) + " KB)"));
                    return;
                }
                probeIn.close();
                probe.disconnect();

                // HLS recording: download segments
                String baseUrl = streamUrl.substring(0, streamUrl.lastIndexOf('/') + 1);
                Set<String> downloadedSegments = new HashSet<>();

                while (recording) {
                    try {
                        // Fetch the playlist
                        HttpURLConnection m3uConn = (HttpURLConnection) new URL(streamUrl).openConnection();
                        m3uConn.setRequestProperty("User-Agent", "StreamVault/4.2");
                        m3uConn.setConnectTimeout(8000);
                        m3uConn.setInstanceFollowRedirects(true);
                        BufferedReader reader = new BufferedReader(
                            new InputStreamReader(m3uConn.getInputStream()));
                        List<String> segmentUrls = new ArrayList<>();
                        String mediaPlaylistUrl = null;
                        String line;
                        boolean nextIsSeg = false;

                        while ((line = reader.readLine()) != null) {
                            line = line.trim();
                            if (line.startsWith("#EXTINF:")) {
                                nextIsSeg = true;
                                continue;
                            }
                            if (line.startsWith("#EXT-X-STREAM-INF")) {
                                nextIsSeg = true;
                                continue;
                            }
                            if (line.startsWith("#") || line.isEmpty()) continue;

                            String resolved = resolveUrl(baseUrl, line);
                            if (line.contains(".m3u8") || line.contains(".m3u")) {
                                mediaPlaylistUrl = resolved;
                            } else if (nextIsSeg || line.contains(".ts")
                                || !line.contains(".m3u")) {
                                segmentUrls.add(resolved);
                            }
                            nextIsSeg = false;
                        }
                        reader.close();
                        m3uConn.disconnect();

                        // Master playlist: fetch first media playlist
                        if (segmentUrls.isEmpty() && mediaPlaylistUrl != null) {
                            String mBase = mediaPlaylistUrl.substring(
                                0, mediaPlaylistUrl.lastIndexOf('/') + 1);
                            HttpURLConnection mc = (HttpURLConnection)
                                new URL(mediaPlaylistUrl).openConnection();
                            mc.setRequestProperty("User-Agent", "StreamVault/4.2");
                            mc.setInstanceFollowRedirects(true);
                            BufferedReader mr = new BufferedReader(
                                new InputStreamReader(mc.getInputStream()));
                            nextIsSeg = false;
                            while ((line = mr.readLine()) != null) {
                                line = line.trim();
                                if (line.startsWith("#EXTINF:")) {
                                    nextIsSeg = true;
                                    continue;
                                }
                                if (line.startsWith("#") || line.isEmpty()) continue;
                                if (nextIsSeg || !line.contains(".m3u")) {
                                    segmentUrls.add(resolveUrl(mBase, line));
                                }
                                nextIsSeg = false;
                            }
                            mr.close();
                            mc.disconnect();
                        }

                        // Download new segments
                        for (String segUrl : segmentUrls) {
                            if (!recording) break;
                            if (downloadedSegments.contains(segUrl)) continue;
                            downloadedSegments.add(segUrl);
                            try {
                                HttpURLConnection sc = (HttpURLConnection)
                                    new URL(segUrl).openConnection();
                                sc.setRequestProperty("User-Agent", "StreamVault/4.2");
                                sc.setConnectTimeout(8000);
                                sc.setReadTimeout(15000);
                                sc.setInstanceFollowRedirects(true);
                                InputStream si = sc.getInputStream();
                                byte[] buf = new byte[16384];
                                int n;
                                while (recording && (n = si.read(buf)) != -1) {
                                    fos.write(buf, 0, n);
                                    totalBytes += n;
                                }
                                si.close();
                                sc.disconnect();
                            } catch (Exception se) {
                                // Skip bad segment, continue
                            }
                        }

                        // Wait before re-fetching playlist (live HLS updates every 2-6s)
                        if (recording) Thread.sleep(4000);

                    } catch (Exception le) {
                        if (recording) Thread.sleep(3000);
                    }
                }

                fos.close();
                final long finalBytes = totalBytes;
                final String savedPath = outDisplayPath;
                handler.post(() -> showMsg("⏹ Saved to: " + savedPath
                    + " (" + (finalBytes > 1048576
                        ? finalBytes / 1048576 + " MB"
                        : finalBytes / 1024 + " KB") + ")"));

            } catch (Exception e) {
                if (fos != null) try { fos.close(); } catch (Exception x) {}
                handler.post(() -> showMsg("Record error: " + e.getMessage()));
            }
            handler.post(() -> {
                recording = false;
                recordingStartTime = 0;
                recordBtn.setColorFilter(Color.parseColor("#80ffffff"));
                recStatusText.setText("");
            });
        });
        recordThread.start();
    }

    private String resolveUrl(String base, String relative) {
        if (relative.startsWith("http://") || relative.startsWith("https://")) return relative;
        if (relative.startsWith("/")) {
            try {
                URL u = new URL(base);
                return u.getProtocol() + "://" + u.getHost()
                    + (u.getPort() > 0 ? ":" + u.getPort() : "") + relative;
            } catch (Exception e) {
                return base + relative;
            }
        }
        return base + relative;
    }

    private RecordingTarget openRecordingTarget(String filename) throws Exception {
        if (savePath != null && savePath.startsWith("content://")) {
            Uri treeUri = Uri.parse(savePath);
            DocumentFile tree = DocumentFile.fromTreeUri(this, treeUri);
            if (tree == null) throw new IllegalStateException("Selected recording folder is not available");
            DocumentFile out = tree.createFile("video/mp2t", filename);
            if (out == null) throw new IllegalStateException("Could not create recording file");
            OutputStream os = getContentResolver().openOutputStream(out.getUri(), "w");
            if (os == null) throw new IllegalStateException("Could not open recording file");
            return new RecordingTarget(os, describeTreeUri(treeUri) + "/" + filename);
        }

        File dir;
        if (savePath != null && !savePath.isEmpty()) {
            dir = new File(savePath);
        } else {
            dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        }
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("Could not create recording folder");
        }
        File outFile = new File(dir, filename);
        return new RecordingTarget(new FileOutputStream(outFile), outFile.getAbsolutePath());
    }

    private String describeTreeUri(Uri treeUri) {
        try {
            String docId = android.provider.DocumentsContract.getTreeDocumentId(treeUri);
            if (docId == null || docId.isEmpty()) return "Selected folder";
            int idx = docId.indexOf(':');
            String vol = idx >= 0 ? docId.substring(0, idx) : docId;
            String path = idx >= 0 ? docId.substring(idx + 1) : "";
            String base = "primary".equalsIgnoreCase(vol) ? "Internal storage" : vol;
            return path.isEmpty() ? base : (base + "/" + path);
        } catch (Exception e) {
            return "Selected folder";
        }
    }

    private void stopRecording() {
        recording = false;
        recordBtn.setColorFilter(Color.parseColor("#80ffffff"));
        showMsg("⏹ Stopping recording…");
    }

    // ─── Network Monitoring ───
    private void registerNetworkCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                networkCallback = new ConnectivityManager.NetworkCallback() {
                    @Override
                    public void onAvailable(Network network) {
                        handler.post(() -> {
                            boolean wasDown = !networkAvailable;
                            networkAvailable = true;
                            if (wasDown && player != null
                                && player.getPlaybackState() == Player.STATE_IDLE) {
                                showMsg("Network restored — retrying");
                                playVariant(currentIdx);
                            }
                        });
                    }

                    @Override
                    public void onLost(Network network) {
                        handler.post(() -> {
                            networkAvailable = false;
                            cancelFailoverTimeout();
                            showMsg("Network lost — pausing failover");
                        });
                    }
                };
                cm.registerNetworkCallback(
                    new NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build(),
                    networkCallback);
            } catch (Exception e) {
                // Ignore — network monitoring is optional
            }
        }
    }

    // ─── UI Helpers ───
    private void updatePlayPauseIcon() {
        if (player != null && player.isPlaying()) {
            playPauseBtn.setImageResource(android.R.drawable.ic_media_pause);
        } else {
            playPauseBtn.setImageResource(android.R.drawable.ic_media_play);
        }
    }

    private void toggleOverlay() {
        setOverlayVisible(!overlayVisible);
        if (overlayVisible) scheduleHideOverlay();
    }

    private void showOverlayBriefly() {
        setOverlayVisible(true);
        scheduleHideOverlay();
    }

    private void setOverlayVisible(boolean visible) {
        overlayVisible = visible;
        float alpha = visible ? 1f : 0f;
        overlayTop.animate().alpha(alpha).setDuration(200).start();
        overlayCenter.animate().alpha(alpha).setDuration(200).start();
        overlayBottom.animate().alpha(alpha).setDuration(200).start();
    }

    private void scheduleHideOverlay() {
        handler.removeCallbacks(hideOverlayRunnable);
        handler.postDelayed(hideOverlayRunnable, 4000);
    }

    private void showMsg(String msg) {
        runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
    }

    private void hideSystemUI() {
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private void releasePlayer() {
        if (player != null) {
            player.stop();
            player.release();
            player = null;
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    private FrameLayout.LayoutParams matchParent() {
        return new FrameLayout.LayoutParams(-1, -1);
    }

    private FrameLayout.LayoutParams centered(int w, int h) {
        return new FrameLayout.LayoutParams(w, h, Gravity.CENTER);
    }

    private ImageButton makeButton(int drawableRes) {
        ImageButton btn = new ImageButton(this);
        btn.setImageResource(drawableRes);
        btn.setColorFilter(Color.WHITE);
        btn.setBackgroundColor(Color.TRANSPARENT);
        btn.setPadding(dp(8), dp(8), dp(8), dp(8));
        return btn;
    }

    // ─── Lifecycle ───
    @Override
    public void onBackPressed() {
        if (recording) stopRecording();
        finish();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUI();
        if (player != null) player.play();
    }

    @Override
    protected void onPause() {
        if (player != null) player.pause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (recording) stopRecording();
        if (handler != null) handler.removeCallbacksAndMessages(null);
        releasePlayer();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && networkCallback != null) {
            try {
                ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                cm.unregisterNetworkCallback(networkCallback);
            } catch (Exception e) { /* ignore */ }
        }
        super.onDestroy();
    }


    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    toggleOverlay();
                    if (player != null) {
                        if (player.isPlaying()) player.pause(); else player.play();
                        updatePlayPauseIcon();
                    }
                    scheduleHideOverlay();
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    playPreviousVariant();
                    scheduleHideOverlay();
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    playNextVariant();
                    scheduleHideOverlay();
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    toggleOverlay();
                    scheduleHideOverlay();
                    return true;
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                    if (player != null) {
                        if (player.isPlaying()) player.pause(); else player.play();
                        updatePlayPauseIcon();
                    }
                    return true;
                case KeyEvent.KEYCODE_BACK:
                    finish();
                    return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }
}
