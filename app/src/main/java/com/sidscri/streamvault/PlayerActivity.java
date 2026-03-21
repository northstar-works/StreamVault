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
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
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
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@OptIn(markerClass = UnstableApi.class)
public class PlayerActivity extends Activity {

    public static final String EXTRA_URL            = "stream_url";
    public static final String EXTRA_TITLE          = "stream_title";
    public static final String EXTRA_CATEGORY       = "stream_category";
    public static final String EXTRA_FAILOVER_JSON  = "failover_json";
    public static final String EXTRA_NOW_NEXT       = "now_next";
    public static final String EXTRA_FO_TIMEOUT     = "fo_timeout";
    public static final String EXTRA_FO_AUTO        = "fo_auto";
    public static final String EXTRA_SAVE_PATH      = "save_path";
    public static final String EXTRA_ITEM_ID        = "item_id";
    public static final String EXTRA_SEEK_MS        = "seek_ms";

    // ─── State ───────────────────────────────────────────────────────────────
    private ExoPlayer   player;
    private PlayerView  playerView;
    private View        loadingView, errorContainer;
    private View        overlayTop, overlayCenter, overlayBottom;
    private TextView    titleText, statusText, nowNextText, strengthText,
                        errorText, recStatusText, sourceInfoText, timeshiftText;
    private SeekBar     timeshiftBar;
    private ImageButton playPauseBtn, lockBtn, recordBtn, prevBtn, nextBtn,
                        timeshiftPauseBtn, timeshiftLiveBtn;
    private Handler     handler;
    private Runnable    hideOverlayRunnable, failoverTimeoutRunnable, strengthUpdater, positionReporter;
    private boolean     overlayVisible = false, locked = false, networkAvailable = true;
    private volatile boolean recording  = false;
    private String      lastSuccessUrl  = null, savePath = null;
    private long        playbackStartTime = 0, foTimeoutMs = 15000, recordingStartTime = 0;
    private long        seekOnReadyMs   = 0;    // seek to this position after STATE_READY
    private String      itemId          = null; // for Plex progress reporting
    private boolean     foAuto          = true;
    private ConnectivityManager.NetworkCallback networkCallback;
    private Thread      recordThread;
    private WebView     parentWebView   = null; // set by MainActivity if available

    // Timeshift (live TV pause)
    private volatile boolean timeshiftEnabled  = false;
    private volatile boolean timeshiftPaused   = false;
    private volatile long    timeshiftBufMs    = 0;   // ms of buffer accumulated
    private volatile long    timeshiftPausedAt = 0;
    private volatile long    timeshiftOffset   = 0;   // ms behind live
    private static final long TS_MAX_BUF_MS   = 3600_000L; // 1 hour max
    private Thread      timeshiftThread;

    private final List<Variant> variants = new ArrayList<>();
    private int currentIdx = 0;

    // ─── Data classes ────────────────────────────────────────────────────────
    static class RecordingTarget {
        final OutputStream out;
        final String       displayPath;
        RecordingTarget(OutputStream out, String displayPath) { this.out=out; this.displayPath=displayPath; }
    }

    static class Variant {
        final String url, title, region, tag;
        final long   seekMs;
        Variant(String url, String title, String region, String tag, long seekMs) {
            this.url    = url    != null ? url    : "";
            this.title  = title  != null ? title  : "";
            this.region = region != null ? region : "";
            this.tag    = tag    != null ? tag    : "";
            this.seekMs = seekMs;
        }
    }

    // ─── Lifecycle ───────────────────────────────────────────────────────────
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
        hideOverlayRunnable     = () -> setOverlayVisible(false);
        failoverTimeoutRunnable = () -> {};
        parseIntent();
        if (variants.isEmpty()) { finish(); return; }
        buildUI();
        registerNetworkCallback();
        startStrengthMonitor();
        playVariant(0);
        hideSystemUI();
    }

    // ─── Intent ──────────────────────────────────────────────────────────────
    private void parseIntent() {
        foTimeoutMs = getIntent().getIntExtra(EXTRA_FO_TIMEOUT, 15) * 1000L;
        foAuto      = getIntent().getBooleanExtra(EXTRA_FO_AUTO, true);
        savePath    = getIntent().getStringExtra(EXTRA_SAVE_PATH);
        itemId      = getIntent().getStringExtra(EXTRA_ITEM_ID);
        long globalSeek = getIntent().getLongExtra(EXTRA_SEEK_MS, 0);

        String json = getIntent().getStringExtra(EXTRA_FAILOVER_JSON);
        if (json != null && !json.isEmpty()) {
            try {
                JSONArray arr = new JSONArray(json);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    long sk = o.has("seekMs") ? o.getLong("seekMs") : (i==0 ? globalSeek : 0);
                    variants.add(new Variant(
                        o.optString("url",""), o.optString("title",""),
                        o.optString("region",""), o.optString("tag",""), sk));
                }
            } catch (Exception ignored) {}
        }
        if (variants.isEmpty()) {
            String url   = getIntent().getStringExtra(EXTRA_URL);
            String title = getIntent().getStringExtra(EXTRA_TITLE);
            if (url != null && !url.isEmpty())
                variants.add(new Variant(url, title!=null?title:"Stream", "", "", globalSeek));
        }
    }

    private String getScheme(String raw) {
        if (raw==null) return "";
        int i=raw.indexOf(':'); return i>0 ? raw.substring(0,i).toLowerCase(Locale.US) : "";
    }
    private boolean isLikelyHls(String raw) {
        if (raw==null) return false;
        String v=raw.toLowerCase(Locale.US);
        return v.contains(".m3u8")||v.contains("m3u_plus")||v.contains("type=m3u")||v.contains("output=m3u8");
    }
    private boolean isIptvItem() {
        // IPTV items don't have itemId set (only Plex items do)
        return itemId == null || itemId.isEmpty();
    }

    // ─── UI ──────────────────────────────────────────────────────────────────
    private void buildUI() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        playerView = new PlayerView(this);
        playerView.setUseController(false);
        playerView.setKeepScreenOn(true);
        root.addView(playerView, matchParent());

        loadingView = new ProgressBar(this, null, android.R.attr.progressBarStyleLarge);
        root.addView(loadingView, centered(dp(38), dp(38)));

        errorContainer = buildErrorView();
        root.addView(errorContainer, centered(-2, -2));

        GestureDetector gd = new GestureDetector(this,
            new GestureDetector.SimpleOnGestureListener() {
                @Override public boolean onSingleTapConfirmed(MotionEvent e) { toggleOverlay(); return true; }
                @Override public boolean onDoubleTap(MotionEvent e) {
                    if (player!=null) { if (player.isPlaying()) player.pause(); else player.play(); updatePlayPauseIcon(); }
                    return true;
                }
            });
        View tc = new View(this);
        tc.setBackgroundColor(Color.TRANSPARENT);
        tc.setOnTouchListener((v,e)->{ gd.onTouchEvent(e); return true; });
        root.addView(tc, matchParent());

        overlayTop    = buildTopOverlay();
        overlayCenter = buildCenterOverlay();
        overlayBottom = buildBottomOverlay();
        root.addView(overlayTop,    new FrameLayout.LayoutParams(-1,-2,Gravity.TOP));
        root.addView(overlayCenter, centered(-2,-2));
        root.addView(overlayBottom, new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));
        setOverlayVisible(false);
        setContentView(root);
    }

    private View buildTopOverlay() {
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(8),dp(34),dp(8),dp(8));
        top.setBackgroundColor(Color.parseColor("#CC000000"));

        ImageButton backBtn = makeButton(android.R.drawable.ic_media_previous);
        backBtn.setOnClickListener(v->finish());
        top.addView(backBtn);

        LinearLayout ta = new LinearLayout(this);
        ta.setOrientation(LinearLayout.VERTICAL);
        ta.setPadding(dp(6),0,0,0);

        titleText    = makeLabel(12,Color.WHITE,true);
        statusText   = makeLabel(8,Color.parseColor("#80ffffff"),false);
        nowNextText  = makeLabel(8,Color.parseColor("#6c5ce7"),false);
        strengthText = makeLabel(7,Color.parseColor("#60ffffff"),false);
        recStatusText= makeLabel(7,Color.parseColor("#ff4757"),false);
        timeshiftText= makeLabel(7,Color.parseColor("#ffa502"),false);

        String nn = getIntent().getStringExtra(EXTRA_NOW_NEXT);
        if (nn!=null&&!nn.isEmpty()) nowNextText.setText(nn);

        ta.addView(titleText); ta.addView(statusText); ta.addView(nowNextText);
        ta.addView(strengthText); ta.addView(recStatusText); ta.addView(timeshiftText);
        top.addView(ta, new LinearLayout.LayoutParams(0,-2,1));

        recordBtn = makeButton(android.R.drawable.ic_btn_speak_now);
        recordBtn.setColorFilter(Color.parseColor("#80ffffff"));
        recordBtn.setOnClickListener(v->toggleRecording());
        top.addView(recordBtn);

        // Timeshift pause button (only for IPTV)
        if (isIptvItem()) {
            timeshiftPauseBtn = makeButton(android.R.drawable.ic_media_pause);
            timeshiftPauseBtn.setColorFilter(Color.parseColor("#80ffffff"));
            timeshiftPauseBtn.setOnClickListener(v->toggleTimeshiftPause());
            top.addView(timeshiftPauseBtn);
        }

        lockBtn = makeButton(android.R.drawable.ic_lock_idle_lock);
        lockBtn.setColorFilter(Color.parseColor("#80ffffff"));
        lockBtn.setOnClickListener(v->{
            locked=!locked;
            lockBtn.setColorFilter(locked?Color.parseColor("#ffa502"):Color.parseColor("#80ffffff"));
            showMsg(locked?"🔒 Locked to this source":"🔓 Auto-failover enabled");
            scheduleHideOverlay();
        });
        top.addView(lockBtn);
        return top;
    }

    private View buildCenterOverlay() {
        LinearLayout c = new LinearLayout(this);
        c.setGravity(Gravity.CENTER);
        c.setOrientation(LinearLayout.HORIZONTAL);

        prevBtn = makeButton(android.R.drawable.ic_media_previous);
        prevBtn.setBackgroundColor(Color.parseColor("#33000000"));
        prevBtn.setColorFilter(Color.WHITE);
        prevBtn.setOnClickListener(v->{ playPreviousVariant(); scheduleHideOverlay(); });
        c.addView(prevBtn);

        playPauseBtn = new ImageButton(this);
        playPauseBtn.setImageResource(android.R.drawable.ic_media_pause);
        playPauseBtn.setColorFilter(Color.WHITE);
        playPauseBtn.setBackgroundColor(Color.parseColor("#6c5ce7"));
        playPauseBtn.setPadding(dp(15),dp(15),dp(15),dp(15));
        playPauseBtn.setOnClickListener(v->{
            if (timeshiftPaused) { resumeTimeshift(); }
            else if (player!=null) { if (player.isPlaying()) player.pause(); else player.play(); updatePlayPauseIcon(); }
            scheduleHideOverlay();
        });
        LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(-2,-2);
        pLp.leftMargin=dp(12); pLp.rightMargin=dp(12);
        c.addView(playPauseBtn, pLp);

        nextBtn = makeButton(android.R.drawable.ic_media_next);
        nextBtn.setBackgroundColor(Color.parseColor("#33000000"));
        nextBtn.setColorFilter(Color.WHITE);
        nextBtn.setOnClickListener(v->{ playNextVariant(); scheduleHideOverlay(); });
        c.addView(nextBtn);
        return c;
    }

    private View buildBottomOverlay() {
        LinearLayout bot = new LinearLayout(this);
        bot.setOrientation(LinearLayout.VERTICAL);
        bot.setPadding(dp(10),dp(8),dp(10),dp(24));
        bot.setBackgroundColor(Color.parseColor("#CC000000"));
        bot.setGravity(Gravity.CENTER);

        sourceInfoText = makeLabel(7,Color.parseColor("#40ffffff"),false);
        sourceInfoText.setGravity(android.view.Gravity.CENTER);
        sourceInfoText.setText("Source 1/"+variants.size());
        bot.addView(sourceInfoText);

        // Timeshift seek bar (hidden by default, shown when timeshifted)
        if (isIptvItem()) {
            timeshiftBar = new SeekBar(this);
            timeshiftBar.setVisibility(View.GONE);
            timeshiftBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar s,int prog,boolean user) {
                    if (user && player!=null) {
                        long live = player.getDuration();
                        if (live>0) player.seekTo((long)(prog/100.0*live));
                    }
                }
                @Override public void onStartTrackingTouch(SeekBar s){}
                @Override public void onStopTrackingTouch(SeekBar s){}
            });
            bot.addView(timeshiftBar, new LinearLayout.LayoutParams(-1,-2));

            timeshiftLiveBtn = makeButton(android.R.drawable.ic_media_next);
            timeshiftLiveBtn.setVisibility(View.GONE);
            timeshiftLiveBtn.setColorFilter(Color.parseColor("#ff4757"));
            timeshiftLiveBtn.setOnClickListener(v->jumpToLive());
            LinearLayout row2 = new LinearLayout(this);
            row2.setGravity(Gravity.CENTER);
            TextView liveLabel = makeLabel(8,Color.parseColor("#ff4757"),false);
            liveLabel.setText("► LIVE  ");
            row2.addView(liveLabel);
            row2.addView(timeshiftLiveBtn);
            bot.addView(row2);
        }
        return bot;
    }

    private View buildErrorView() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER);
        c.setVisibility(View.GONE);
        errorText = makeLabel(12,Color.parseColor("#aaaaaa"),false);
        errorText.setGravity(Gravity.CENTER);
        errorText.setPadding(dp(20),0,dp(20),dp(10));
        c.addView(errorText);
        TextView retry = makeLabel(13,Color.parseColor("#6c5ce7"),false);
        retry.setText("Tap to Retry");
        retry.setPadding(dp(16),dp(8),dp(16),dp(8));
        retry.setBackgroundColor(Color.parseColor("#1a1a2e"));
        retry.setOnClickListener(v->{ errorContainer.setVisibility(View.GONE); loadingView.setVisibility(View.VISIBLE); playVariant(currentIdx); });
        c.addView(retry);
        return c;
    }

    // ─── Playback ────────────────────────────────────────────────────────────
    private void playVariant(int idx) {
        // On channel change, clear timeshift buffer
        stopTimeshiftBuffer();

        if (idx<0||idx>=variants.size()) { if (lastSuccessUrl!=null) { for(int i=0;i<variants.size();i++) if(lastSuccessUrl.equals(variants.get(i).url)){playVariant(i);return;} } showAllFailed(); return; }
        currentIdx = idx;
        Variant v  = variants.get(idx);
        seekOnReadyMs = v.seekMs;
        releasePlayer();
        loadingView.setVisibility(View.VISIBLE);
        errorContainer.setVisibility(View.GONE);

        String display = v.title.isEmpty()?"Source "+(idx+1):v.title;
        if (!v.tag.isEmpty()) display+=" ("+v.tag+")";
        titleText.setText(display);
        statusText.setText("Source "+(idx+1)+"/"+variants.size()+(locked?" · 🔒":""));
        if (sourceInfoText!=null) sourceInfoText.setText("Source "+(idx+1)+"/"+variants.size()+" · ◀▶ prev/next");
        if (prevBtn!=null) prevBtn.setAlpha(variants.size()>1?1f:0.45f);
        if (nextBtn!=null) nextBtn.setAlpha(variants.size()>1?1f:0.45f);
        strengthText.setText("");
        if (idx>0) showMsg("Trying: "+display);

        DataSource.Factory httpFactory = new DefaultHttpDataSource.Factory()
            .setUserAgent("StreamVault/4.6 ExoPlayer")
            .setConnectTimeoutMs(12000).setReadTimeoutMs(12000)
            .setAllowCrossProtocolRedirects(true);
        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);
        playbackStartTime = System.currentTimeMillis();

        String scheme = getScheme(v.url);
        boolean useRtsp   = "rtsp".equals(scheme)||"rtsps".equals(scheme);
        boolean preferHls = !useRtsp && isLikelyHls(v.url);
        MediaSource src = useRtsp
            ? new RtspMediaSource.Factory().createMediaSource(MediaItem.fromUri(Uri.parse(v.url)))
            : (preferHls
                ? new HlsMediaSource.Factory(httpFactory).setAllowChunklessPreparation(true).createMediaSource(MediaItem.fromUri(Uri.parse(v.url)))
                : new ProgressiveMediaSource.Factory(httpFactory).createMediaSource(MediaItem.fromUri(Uri.parse(v.url))));

        final boolean[] primaryFailed = {false};
        final DataSource.Factory hf2 = httpFactory;
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (state==Player.STATE_READY) {
                    loadingView.setVisibility(View.GONE);
                    errorContainer.setVisibility(View.GONE);
                    lastSuccessUrl = v.url;
                    cancelFailoverTimeout();
                    // Seek to resume position (Plex resume)
                    if (seekOnReadyMs>0) {
                        player.seekTo(seekOnReadyMs);
                        seekOnReadyMs=0;
                    }
                    // Start timeshift buffer for live TV
                    if (isIptvItem()) startTimeshiftBuffer();
                    // Start Plex position reporter
                    if (!isIptvItem()) startPositionReporter();
                } else if (state==Player.STATE_BUFFERING) {
                    loadingView.setVisibility(View.VISIBLE);
                    if (!locked&&foAuto) startFailoverTimeout();
                } else if (state==Player.STATE_ENDED) {
                    // Plex playback complete — clear resume position
                    if (itemId!=null&&!itemId.isEmpty()) {
                        reportToJs("window.onPlexPlaybackComplete && window.onPlexPlaybackComplete('"+itemId+"')");
                    }
                }
            }
            @Override public void onPlayerError(PlaybackException error) {
                cancelFailoverTimeout();
                if (!primaryFailed[0]&&preferHls) {
                    primaryFailed[0]=true;
                    try {
                        player.stop();
                        MediaSource progressive = new ProgressiveMediaSource.Factory(hf2).createMediaSource(MediaItem.fromUri(Uri.parse(v.url)));
                        player.setMediaSource(progressive); player.prepare(); player.setPlayWhenReady(true);
                    } catch (Exception e) { tryNextVariant(); }
                    return;
                }
                if (!locked&&foAuto&&networkAvailable) tryNextVariant();
                else if (!networkAvailable) { showMsg("Network lost — waiting…"); loadingView.setVisibility(View.VISIBLE); }
                else showStreamFailed();
            }
        });
        player.setMediaSource(src);
        player.setPlayWhenReady(true);
        player.prepare();
        showOverlayBriefly();
    }

    // ─── Plex Position Reporting ─────────────────────────────────────────────
    private void startPositionReporter() {
        stopPositionReporter();
        if (itemId==null||itemId.isEmpty()) return;
        positionReporter = new Runnable() {
            @Override public void run() {
                if (player!=null&&player.isPlaying()) {
                    long pos = player.getCurrentPosition();
                    long dur = player.getDuration();
                    String js = "window.onPlexPositionUpdate && window.onPlexPositionUpdate('"
                        +itemId+"',"+pos+","+dur+",'')";
                    reportToJs(js);
                }
                handler.postDelayed(this, 10000); // report every 10s
            }
        };
        handler.postDelayed(positionReporter, 15000); // first report after 15s
    }
    private void stopPositionReporter() {
        if (positionReporter!=null) { handler.removeCallbacks(positionReporter); positionReporter=null; }
    }
    private void reportToJs(String js) {
        // Try to reach MainActivity's WebView
        try { MainActivity.webViewRef.loadUrl("javascript:"+js); } catch (Exception ignored) {}
    }

    // ─── Live TV Timeshift ────────────────────────────────────────────────────
    // Strategy: when paused, ExoPlayer's internal HLS buffer grows naturally.
    // We just track the offset and let ExoPlayer handle the buffer.
    // For raw streams we can't buffer, so timeshift is only meaningful for HLS.
    private void startTimeshiftBuffer() {
        timeshiftEnabled  = true;
        timeshiftPaused   = false;
        timeshiftOffset   = 0;
        timeshiftBufMs    = 0;
        updateTimeshiftUI();
    }
    private void stopTimeshiftBuffer() {
        timeshiftEnabled  = false;
        timeshiftPaused   = false;
        timeshiftOffset   = 0;
        timeshiftBufMs    = 0;
        if (timeshiftText!=null) timeshiftText.setText("");
        if (timeshiftBar!=null)  timeshiftBar.setVisibility(View.GONE);
        if (timeshiftLiveBtn!=null) timeshiftLiveBtn.setVisibility(View.GONE);
    }
    private void toggleTimeshiftPause() {
        if (!timeshiftEnabled) return;
        if (timeshiftPaused) { resumeTimeshift(); }
        else { pauseTimeshift(); }
        scheduleHideOverlay();
    }
    private void pauseTimeshift() {
        if (player==null||!timeshiftEnabled) return;
        timeshiftPaused   = true;
        timeshiftPausedAt = System.currentTimeMillis();
        player.pause();
        updatePlayPauseIcon();
        updateTimeshiftUI();
        showMsg("⏸ Live TV paused — buffer growing");
    }
    private void resumeTimeshift() {
        if (player==null) return;
        timeshiftPaused = false;
        timeshiftOffset = System.currentTimeMillis()-timeshiftPausedAt;
        player.play();
        updatePlayPauseIcon();
        updateTimeshiftUI();
        // Buffer is now playing from where it was — ExoPlayer handles the lag naturally
    }
    private void jumpToLive() {
        if (player==null) return;
        timeshiftPaused = false;
        timeshiftOffset = 0;
        // Seek to end of buffer (live edge)
        long dur = player.getDuration();
        if (dur>0) player.seekTo(dur);
        player.play();
        updatePlayPauseIcon();
        updateTimeshiftUI();
        showMsg("● Back to live");
    }
    private void updateTimeshiftUI() {
        handler.post(()->{
            if (!timeshiftEnabled) return;
            long offsetSec = timeshiftOffset/1000;
            if (timeshiftPaused) {
                timeshiftText.setText("⏸ PAUSED — press ▶ to resume (buffers while paused)");
                timeshiftText.setTextColor(Color.parseColor("#ffa502"));
            } else if (offsetSec>5) {
                timeshiftText.setText("⏪ "+offsetSec+"s behind live  —  tap ▶▶ for live");
                timeshiftText.setTextColor(Color.parseColor("#ffa502"));
            } else {
                timeshiftText.setText("● LIVE");
                timeshiftText.setTextColor(Color.parseColor("#2ed573"));
            }
            if (timeshiftBar!=null) timeshiftBar.setVisibility(timeshiftOffset>5000?View.VISIBLE:View.GONE);
            if (timeshiftLiveBtn!=null) timeshiftLiveBtn.setVisibility(timeshiftOffset>5000?View.VISIBLE:View.GONE);
        });
    }

    // ─── Recording (HLS-aware, fixed) ────────────────────────────────────────
    private void toggleRecording() {
        if (recording) stopRecording(); else startRecording();
    }

    private void startRecording() {
        if (currentIdx>=variants.size()) return;
        recording = true;
        recordingStartTime = System.currentTimeMillis();
        recordBtn.setColorFilter(Color.parseColor("#ff4757"));
        showMsg("⏺ Recording started");
        scheduleHideOverlay();

        final String streamUrl = variants.get(currentIdx).url;
        final String safeName  = variants.get(currentIdx).title.replaceAll("[^a-zA-Z0-9_-]","_");
        final String ts        = new SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(new Date());

        recordThread = new Thread(()->{
            OutputStream fos = null;
            String outPath = null;
            try {
                RecordingTarget target = openRecordingTarget("SV_"+safeName+"_"+ts+".ts");
                fos    = target.out;
                outPath = target.displayPath;
                long totalBytes = 0;

                // Open connection and follow redirects to get the FINAL URL
                HttpURLConnection probe = (HttpURLConnection) new URL(streamUrl).openConnection();
                probe.setRequestProperty("User-Agent","StreamVault/4.6");
                probe.setConnectTimeout(12000);
                probe.setReadTimeout(15000);
                probe.setInstanceFollowRedirects(true);
                probe.connect();

                // The final URL after any redirects
                String finalUrl = probe.getURL().toString();
                String finalBase = finalUrl.contains("/")
                    ? finalUrl.substring(0, finalUrl.lastIndexOf('/')+1) : finalUrl;

                // Read first chunk to detect HLS vs raw
                InputStream probeIn = probe.getInputStream();
                byte[] peek = new byte[8192];
                int peekLen = 0, read;
                // Read up to 8KB for detection
                while (peekLen < peek.length && (read=probeIn.read(peek,peekLen,peek.length-peekLen))!=-1) {
                    peekLen+=read;
                }
                String peekStr = new String(peek,0,peekLen,"UTF-8");

                boolean isHls = peekStr.contains("#EXTM3U") || peekStr.contains("#EXT-X-")
                    || finalUrl.contains(".m3u8") || streamUrl.contains(".m3u8");

                if (!isHls) {
                    // Raw stream — write peek then pipe rest
                    if (peekLen>0) { fos.write(peek,0,peekLen); totalBytes+=peekLen; }
                    byte[] buf = new byte[65536];
                    int n;
                    while (recording && (n=probeIn.read(buf))!=-1) {
                        fos.write(buf,0,n);
                        totalBytes+=n;
                    }
                    probeIn.close();
                } else {
                    // HLS — parse the M3U8 content we already have
                    probeIn.close();
                    probe.disconnect();

                    Set<String> downloaded = new HashSet<>();
                    while (recording) {
                        try {
                            // Re-fetch the playlist
                            HttpURLConnection pc = (HttpURLConnection) new URL(finalUrl).openConnection();
                            pc.setRequestProperty("User-Agent","StreamVault/4.6");
                            pc.setConnectTimeout(10000);
                            pc.setInstanceFollowRedirects(true);
                            InputStream pis = pc.getInputStream();
                            String actualFinal = pc.getURL().toString();
                            String actualBase  = actualFinal.contains("/")
                                ? actualFinal.substring(0,actualFinal.lastIndexOf('/')+1) : actualFinal;

                            BufferedReader br = new BufferedReader(new InputStreamReader(pis,"UTF-8"));
                            List<String> segs = new ArrayList<>();
                            String mediaList = null;
                            String line;
                            boolean nextSeg = false;
                            while ((line=br.readLine())!=null) {
                                line=line.trim();
                                if (line.startsWith("#EXTINF:")) { nextSeg=true; continue; }
                                if (line.startsWith("#EXT-X-STREAM-INF")) { nextSeg=true; continue; }
                                if (line.startsWith("#")||line.isEmpty()) { nextSeg=false; continue; }
                                // It's a URI line
                                String resolved = resolveUrl(actualBase, line);
                                if (line.endsWith(".m3u8")||line.endsWith(".m3u")) {
                                    if (mediaList==null) mediaList=resolved;
                                } else {
                                    // Any non-M3U URI after EXTINF or without recognizable extension is a segment
                                    if (nextSeg) segs.add(resolved);
                                    // Also add if it looks like a segment URL (ts, aac, fmp4, no extension)
                                    else if (!line.contains(".m3u")) segs.add(resolved);
                                }
                                nextSeg=false;
                            }
                            br.close(); pc.disconnect();

                            // If this was a master playlist, resolve media playlist
                            if (segs.isEmpty()&&mediaList!=null) {
                                String mBase=mediaList.contains("/")?mediaList.substring(0,mediaList.lastIndexOf('/')+1):mediaList;
                                HttpURLConnection mc=(HttpURLConnection)new URL(mediaList).openConnection();
                                mc.setRequestProperty("User-Agent","StreamVault/4.6");
                                mc.setInstanceFollowRedirects(true);
                                BufferedReader mr=new BufferedReader(new InputStreamReader(mc.getInputStream(),"UTF-8"));
                                nextSeg=false;
                                while((line=mr.readLine())!=null){
                                    line=line.trim();
                                    if(line.startsWith("#EXTINF:")){nextSeg=true;continue;}
                                    if(line.startsWith("#")||line.isEmpty()){nextSeg=false;continue;}
                                    if(!line.contains(".m3u")) segs.add(resolveUrl(mBase,line));
                                    nextSeg=false;
                                }
                                mr.close(); mc.disconnect();
                            }

                            // Download new segments
                            for (String seg:segs) {
                                if (!recording) break;
                                if (downloaded.contains(seg)) continue;
                                downloaded.add(seg);
                                try {
                                    HttpURLConnection sc=(HttpURLConnection)new URL(seg).openConnection();
                                    sc.setRequestProperty("User-Agent","StreamVault/4.6");
                                    sc.setConnectTimeout(10000);
                                    sc.setReadTimeout(20000);
                                    sc.setInstanceFollowRedirects(true);
                                    InputStream si=sc.getInputStream();
                                    byte[] buf=new byte[65536]; int n;
                                    while(recording&&(n=si.read(buf))!=-1){fos.write(buf,0,n);totalBytes+=n;}
                                    si.close(); sc.disconnect();
                                } catch(Exception se){/* skip bad segment */}
                            }
                            if (recording) Thread.sleep(3000);
                        } catch(Exception le){if(recording)try{Thread.sleep(3000);}catch(Exception ignored){}}
                    }
                }

                // Flush and close
                fos.flush();
                fos.close();
                fos=null;
                final long fb=totalBytes;
                final String fp=outPath;
                handler.post(()->showMsg("⏹ Saved: "+fp+" ("+(fb>1048576?fb/1048576+" MB":fb/1024+" KB")+")"));
            } catch(Exception e) {
                if (fos!=null) try{fos.close();}catch(Exception x){}
                handler.post(()->showMsg("Record error: "+e.getMessage()));
            }
            handler.post(()->{recording=false;recordingStartTime=0;recordBtn.setColorFilter(Color.parseColor("#80ffffff"));recStatusText.setText("");});
        });
        recordThread.setName("sv-record");
        recordThread.start();
    }

    private String resolveUrl(String base, String rel) {
        if (rel.startsWith("http://")||rel.startsWith("https://")) return rel;
        if (rel.startsWith("//")) return "https:"+rel;
        if (rel.startsWith("/")) {
            try { URL u=new URL(base); return u.getProtocol()+"://"+u.getHost()+(u.getPort()>0?":"+u.getPort():"")+rel; }
            catch(Exception e){ return base+rel; }
        }
        return base+rel;
    }

    private RecordingTarget openRecordingTarget(String filename) throws Exception {
        if (savePath!=null&&savePath.startsWith("content://")) {
            Uri treeUri = Uri.parse(savePath);
            DocumentFile tree = DocumentFile.fromTreeUri(this,treeUri);
            if (tree==null) throw new IllegalStateException("Recording folder unavailable");
            DocumentFile out  = tree.createFile("video/mp2t",filename);
            if (out==null)  throw new IllegalStateException("Cannot create recording file");
            OutputStream os = getContentResolver().openOutputStream(out.getUri(),"w");
            if (os==null)   throw new IllegalStateException("Cannot open recording file for write");
            return new RecordingTarget(os, describeTreeUri(treeUri)+"/"+filename);
        }
        File dir = savePath!=null&&!savePath.isEmpty()
            ? new File(savePath)
            : Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (!dir.exists()&&!dir.mkdirs()) throw new IllegalStateException("Cannot create recording folder");
        File outFile = new File(dir, filename);
        return new RecordingTarget(new FileOutputStream(outFile), outFile.getAbsolutePath());
    }

    private String describeTreeUri(Uri u) {
        try {
            String docId = android.provider.DocumentsContract.getTreeDocumentId(u);
            if (docId==null||docId.isEmpty()) return "Selected folder";
            int i=docId.indexOf(':');
            String vol=i>=0?docId.substring(0,i):docId, path=i>=0?docId.substring(i+1):"";
            String base="primary".equalsIgnoreCase(vol)?"Internal storage":vol;
            return path.isEmpty()?base:(base+"/"+path);
        } catch(Exception e){ return "Selected folder"; }
    }

    private void stopRecording() {
        recording=false;
        recordBtn.setColorFilter(Color.parseColor("#80ffffff"));
        showMsg("⏹ Stopping recording…");
    }

    // ─── Failover ────────────────────────────────────────────────────────────
    private void playPreviousVariant() { if(variants.isEmpty())return; int n=currentIdx-1; if(n<0)n=variants.size()-1; playVariant(n); }
    private void playNextVariant()     { if(variants.isEmpty())return; int n=currentIdx+1; if(n>=variants.size())n=0; playVariant(n); }
    private void tryNextVariant() {
        if (locked) return;
        if (currentIdx+1<variants.size()) { playVariant(currentIdx+1); return; }
        if (lastSuccessUrl!=null) { for(int i=0;i<variants.size();i++) if(lastSuccessUrl.equals(variants.get(i).url)){playVariant(i);return;} }
        showAllFailed();
    }
    private void showAllFailed()    { loadingView.setVisibility(View.GONE); errorContainer.setVisibility(View.VISIBLE); errorText.setText("All "+variants.size()+" source(s) failed"); }
    private void showStreamFailed() { loadingView.setVisibility(View.GONE); errorContainer.setVisibility(View.VISIBLE); errorText.setText("Stream failed"); }
    private void startFailoverTimeout() {
        cancelFailoverTimeout();
        failoverTimeoutRunnable = ()->{
            if(player!=null&&player.getPlaybackState()==Player.STATE_BUFFERING&&!locked&&foAuto&&networkAvailable&&System.currentTimeMillis()-playbackStartTime>8000) {
                showMsg("Buffering too long — trying next source"); tryNextVariant();
            }
        };
        handler.postDelayed(failoverTimeoutRunnable,foTimeoutMs);
    }
    private void cancelFailoverTimeout() { if(failoverTimeoutRunnable!=null) handler.removeCallbacks(failoverTimeoutRunnable); }

    // ─── Strength Monitor ────────────────────────────────────────────────────
    private void startStrengthMonitor() {
        strengthUpdater = new Runnable() {
            @Override public void run() {
                if (player!=null&&player.getPlaybackState()==Player.STATE_READY) {
                    long bitrate=0; Format vf=player.getVideoFormat();
                    if (vf!=null&&vf.bitrate>0) bitrate=vf.bitrate;
                    long buffMs=player.getBufferedPosition()-player.getCurrentPosition();
                    String bps=bitrate>0?(bitrate/1000)+"kbps":"";
                    String buf=buffMs>0?(buffMs/1000)+"s buf":"";
                    String label; int color;
                    if (buffMs>10000&&bitrate>2000000){label="●●●● Excellent";color=Color.parseColor("#2ed573");}
                    else if(buffMs>5000&&bitrate>1000000){label="●●●○ Good";color=Color.parseColor("#2ed573");}
                    else if(buffMs>2000){label="●●○○ Fair";color=Color.parseColor("#ffa502");}
                    else {label="●○○○ Weak";color=Color.parseColor("#ff4757");}
                    String disp=label; if(!bps.isEmpty())disp+=" · "+bps; if(!buf.isEmpty())disp+=" · "+buf;
                    strengthText.setText(disp); strengthText.setTextColor(color);

                    // Update timeshift offset
                    if (timeshiftPaused) {
                        timeshiftOffset=System.currentTimeMillis()-timeshiftPausedAt;
                        updateTimeshiftUI();
                    }
                }
                if (recording&&recordingStartTime>0) {
                    long el=(System.currentTimeMillis()-recordingStartTime)/1000;
                    recStatusText.setText("⏺ REC "+String.format(Locale.US,"%02d:%02d",el/60,el%60));
                } else recStatusText.setText("");
                handler.postDelayed(this,2000);
            }
        };
        handler.postDelayed(strengthUpdater,3000);
    }

    // ─── Network ─────────────────────────────────────────────────────────────
    private void registerNetworkCallback() {
        if (Build.VERSION.SDK_INT>=Build.VERSION_CODES.N) {
            try {
                ConnectivityManager cm=(ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);
                networkCallback=new ConnectivityManager.NetworkCallback(){
                    @Override public void onAvailable(Network n){handler.post(()->{boolean was=!networkAvailable;networkAvailable=true;if(was&&player!=null&&player.getPlaybackState()==Player.STATE_IDLE){showMsg("Network restored");playVariant(currentIdx);}});}
                    @Override public void onLost(Network n){handler.post(()->{networkAvailable=false;cancelFailoverTimeout();showMsg("Network lost");});}
                };
                cm.registerNetworkCallback(new NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),networkCallback);
            } catch(Exception ignored){}
        }
    }

    // ─── UI helpers ──────────────────────────────────────────────────────────
    private void updatePlayPauseIcon() {
        if(player!=null&&player.isPlaying()) playPauseBtn.setImageResource(android.R.drawable.ic_media_pause);
        else playPauseBtn.setImageResource(android.R.drawable.ic_media_play);
    }
    private void toggleOverlay()    { setOverlayVisible(!overlayVisible); if(overlayVisible)scheduleHideOverlay(); }
    private void showOverlayBriefly(){ setOverlayVisible(true); scheduleHideOverlay(); }
    private void setOverlayVisible(boolean v) {
        overlayVisible=v; float a=v?1f:0f;
        overlayTop.animate().alpha(a).setDuration(200).start();
        overlayCenter.animate().alpha(a).setDuration(200).start();
        overlayBottom.animate().alpha(a).setDuration(200).start();
    }
    private void scheduleHideOverlay(){ handler.removeCallbacks(hideOverlayRunnable); handler.postDelayed(hideOverlayRunnable,4000); }
    private void showMsg(String msg) { runOnUiThread(()->Toast.makeText(this,msg,Toast.LENGTH_SHORT).show()); }
    private void hideSystemUI() {
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION|
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|
            View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }
    private void releasePlayer() { if(player!=null){player.stop();player.release();player=null;} }
    private int dp(int v) { return (int)(v*getResources().getDisplayMetrics().density); }
    private FrameLayout.LayoutParams matchParent() { return new FrameLayout.LayoutParams(-1,-1); }
    private FrameLayout.LayoutParams centered(int w,int h) { return new FrameLayout.LayoutParams(w,h,Gravity.CENTER); }
    private ImageButton makeButton(int res) { ImageButton b=new ImageButton(this); b.setImageResource(res); b.setColorFilter(Color.WHITE); b.setBackgroundColor(Color.TRANSPARENT); b.setPadding(dp(8),dp(8),dp(8),dp(8)); return b; }
    private TextView makeLabel(int sp, int color, boolean bold) { TextView t=new TextView(this); t.setTextColor(color); t.setTextSize(sp); t.setSingleLine(true); if(bold)t.setTypeface(null,Typeface.BOLD); return t; }

    // ─── Lifecycle ───────────────────────────────────────────────────────────
    @Override public void onBackPressed() { if(recording)stopRecording(); finish(); }
    @Override protected void onResume() { super.onResume(); hideSystemUI(); if(player!=null)player.play(); }
    @Override protected void onPause()  { if(player!=null)player.pause(); super.onPause(); }
    @Override protected void onDestroy() {
        stopRecording(); stopTimeshiftBuffer(); stopPositionReporter();
        if(handler!=null) handler.removeCallbacksAndMessages(null);
        releasePlayer();
        if (Build.VERSION.SDK_INT>=Build.VERSION_CODES.N&&networkCallback!=null) {
            try { ConnectivityManager cm=(ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE); cm.unregisterNetworkCallback(networkCallback); } catch(Exception ignored){}
        }
        super.onDestroy();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction()==KeyEvent.ACTION_DOWN) {
            switch(event.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    toggleOverlay();
                    if(timeshiftPaused){resumeTimeshift();}
                    else if(player!=null){if(player.isPlaying())player.pause();else player.play();updatePlayPauseIcon();}
                    scheduleHideOverlay(); return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:  playPreviousVariant(); scheduleHideOverlay(); return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT: playNextVariant();     scheduleHideOverlay(); return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                    // Timeshift: pause if IPTV and not paused
                    if(isIptvItem()&&timeshiftEnabled&&!timeshiftPaused){pauseTimeshift();}
                    else{toggleOverlay();scheduleHideOverlay();}
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if(isIptvItem()&&timeshiftPaused){jumpToLive();}
                    else{toggleOverlay();scheduleHideOverlay();}
                    return true;
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                    if(timeshiftPaused){resumeTimeshift();}
                    else if(player!=null){if(player.isPlaying())player.pause();else player.play();updatePlayPauseIcon();}
                    return true;
                case KeyEvent.KEYCODE_BACK: finish(); return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }
}
