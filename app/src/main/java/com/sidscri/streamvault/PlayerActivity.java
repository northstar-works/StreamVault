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
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
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
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.ui.PlayerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

@OptIn(markerClass = UnstableApi.class)
public class PlayerActivity extends Activity {

    public static final String EXTRA_URL = "stream_url";
    public static final String EXTRA_TITLE = "stream_title";
    public static final String EXTRA_CATEGORY = "stream_category";
    public static final String EXTRA_FAILOVER_JSON = "failover_json";
    public static final String EXTRA_NOW_NEXT = "now_next";

    private static final long FAILOVER_TIMEOUT_MS = 15000;
    private static final long BUFFER_GRACE_MS = 8000;

    private ExoPlayer player;
    private PlayerView playerView;
    private View loadingView, errorContainer, overlayTop, overlayCenter, overlayBottom;
    private TextView titleText, statusText, errorText, nowNextText;
    private ImageButton playPauseBtn, lockBtn;
    private Handler handler;
    private Runnable hideOverlayRunnable, failoverTimeout;
    private boolean overlayVisible = false, locked = false, networkAvailable = true;
    private String lastSuccessUrl = null;
    private long playbackStartTime = 0;
    private ConnectivityManager.NetworkCallback networkCallback;

    private List<StreamVariant> variants = new ArrayList<>();
    private int currentVariantIdx = 0;

    static class StreamVariant {
        String url, title, region, tag;
        StreamVariant(String u, String t, String r, String tg) { url=u; title=t; region=r; tag=tg; }
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(Color.TRANSPARENT);
            getWindow().setNavigationBarColor(Color.BLACK);
        }
        handler = new Handler(Looper.getMainLooper());
        hideOverlayRunnable = () -> setOverlayVisible(false);
        failoverTimeout = () -> {};
        parseIntent();
        buildUI();
        registerNetworkCallback();
        playVariant(0);
        hideSystemUI();
    }

    private void parseIntent() {
        String json = getIntent().getStringExtra(EXTRA_FAILOVER_JSON);
        if (json != null) {
            try {
                JSONArray a = new JSONArray(json);
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.getJSONObject(i);
                    variants.add(new StreamVariant(o.getString("url"), o.optString("title",""), o.optString("region",""), o.optString("tag","")));
                }
            } catch (Exception e) {}
        }
        if (variants.isEmpty()) {
            String u = getIntent().getStringExtra(EXTRA_URL);
            if (u != null) variants.add(new StreamVariant(u, getIntent().getStringExtra(EXTRA_TITLE), "", ""));
        }
        if (variants.isEmpty()) finish();
    }

    private void buildUI() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        playerView = new PlayerView(this);
        playerView.setUseController(false);
        root.addView(playerView, mp());

        loadingView = new ProgressBar(this, null, android.R.attr.progressBarStyleLarge);
        root.addView(loadingView, ctr(dp(40), dp(40)));

        errorContainer = buildErr();
        root.addView(errorContainer, ctr(-2, -2));

        View touch = new View(this);
        touch.setBackgroundColor(Color.TRANSPARENT);
        GestureDetector gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onSingleTapConfirmed(MotionEvent e) { toggleOverlay(); return true; }
            @Override public boolean onDoubleTap(MotionEvent e) { if (player != null) { if (player.isPlaying()) player.pause(); else player.play(); updPP(); } return true; }
        });
        touch.setOnTouchListener((v, e) -> { gd.onTouchEvent(e); return true; });
        root.addView(touch, mp());

        overlayTop = buildTop();
        root.addView(overlayTop, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
        overlayCenter = buildCenter();
        root.addView(overlayCenter, ctr(-2, -2));
        overlayBottom = buildBot();
        root.addView(overlayBottom, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        setOverlayVisible(false);
        setContentView(root);
    }

    private View buildTop() {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.HORIZONTAL);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(dp(10), dp(36), dp(10), dp(10));
        t.setBackgroundColor(Color.parseColor("#CC000000"));

        ImageButton back = btn(android.R.drawable.ic_media_previous);
        back.setOnClickListener(v -> finish());
        t.addView(back);

        LinearLayout ta = new LinearLayout(this);
        ta.setOrientation(LinearLayout.VERTICAL);
        ta.setPadding(dp(6), 0, 0, 0);

        titleText = new TextView(this);
        titleText.setTextColor(Color.WHITE);
        titleText.setTextSize(13);
        titleText.setSingleLine(true);
        titleText.setTypeface(null, Typeface.BOLD);
        ta.addView(titleText);

        statusText = new TextView(this);
        statusText.setTextColor(Color.parseColor("#80ffffff"));
        statusText.setTextSize(9);
        statusText.setSingleLine(true);
        ta.addView(statusText);

        // Now/Next EPG text
        nowNextText = new TextView(this);
        nowNextText.setTextColor(Color.parseColor("#6c5ce7"));
        nowNextText.setTextSize(9);
        nowNextText.setSingleLine(true);
        String nn = getIntent().getStringExtra(EXTRA_NOW_NEXT);
        if (nn != null && !nn.isEmpty()) nowNextText.setText(nn);
        ta.addView(nowNextText);

        t.addView(ta, new LinearLayout.LayoutParams(0, -2, 1));

        lockBtn = btn(android.R.drawable.ic_lock_idle_lock);
        lockBtn.setColorFilter(Color.parseColor("#80ffffff"));
        lockBtn.setOnClickListener(v -> {
            locked = !locked;
            lockBtn.setColorFilter(locked ? Color.parseColor("#ffa502") : Color.parseColor("#80ffffff"));
            msg(locked ? "🔒 Locked" : "🔓 Auto-failover on");
            schedHide();
        });
        t.addView(lockBtn);
        return t;
    }

    private View buildCenter() {
        LinearLayout c = new LinearLayout(this);
        c.setGravity(Gravity.CENTER);
        playPauseBtn = new ImageButton(this);
        playPauseBtn.setImageResource(android.R.drawable.ic_media_pause);
        playPauseBtn.setColorFilter(Color.WHITE);
        playPauseBtn.setBackgroundColor(Color.parseColor("#6c5ce7"));
        playPauseBtn.setPadding(dp(16), dp(16), dp(16), dp(16));
        playPauseBtn.setOnClickListener(v -> { if (player != null) { if (player.isPlaying()) player.pause(); else player.play(); updPP(); schedHide(); } });
        c.addView(playPauseBtn);
        return c;
    }

    private View buildBot() {
        LinearLayout b = new LinearLayout(this);
        b.setPadding(dp(12), dp(10), dp(12), dp(26));
        b.setBackgroundColor(Color.parseColor("#CC000000"));
        b.setGravity(Gravity.CENTER);
        TextView info = new TextView(this);
        info.setTextColor(Color.parseColor("#40ffffff"));
        info.setTextSize(8);
        info.setSingleLine(true);
        info.setGravity(Gravity.CENTER);
        info.setText("Source 1/" + variants.size());
        b.addView(info);
        return b;
    }

    private View buildErr() {
        LinearLayout e = new LinearLayout(this);
        e.setOrientation(LinearLayout.VERTICAL);
        e.setGravity(Gravity.CENTER);
        e.setVisibility(View.GONE);
        errorText = new TextView(this);
        errorText.setTextColor(Color.parseColor("#aaa"));
        errorText.setTextSize(12);
        errorText.setGravity(Gravity.CENTER);
        errorText.setPadding(dp(20), 0, dp(20), dp(10));
        e.addView(errorText);
        TextView retry = new TextView(this);
        retry.setText("Retry");
        retry.setTextColor(Color.parseColor("#6c5ce7"));
        retry.setTextSize(13);
        retry.setPadding(dp(18), dp(8), dp(18), dp(8));
        retry.setBackgroundColor(Color.parseColor("#1a1a2e"));
        retry.setOnClickListener(v -> { errorContainer.setVisibility(View.GONE); loadingView.setVisibility(View.VISIBLE); playVariant(currentVariantIdx); });
        e.addView(retry);
        return e;
    }

    private void playVariant(int idx) {
        if (idx < 0 || idx >= variants.size()) {
            if (lastSuccessUrl != null) { msg("Retrying last working source"); for (int i = 0; i < variants.size(); i++) if (variants.get(i).url.equals(lastSuccessUrl)) { playVariant(i); return; } }
            loadingView.setVisibility(View.GONE); errorContainer.setVisibility(View.VISIBLE); errorText.setText("All " + variants.size() + " source(s) failed"); return;
        }
        currentVariantIdx = idx;
        StreamVariant sv = variants.get(idx);
        releasePlayer();
        loadingView.setVisibility(View.VISIBLE);
        errorContainer.setVisibility(View.GONE);
        String disp = sv.title.isEmpty() ? "Source " + (idx + 1) : sv.title;
        if (!sv.tag.isEmpty()) disp += " (" + sv.tag + ")";
        titleText.setText(disp);
        statusText.setText("Source " + (idx + 1) + "/" + variants.size() + (locked ? " · 🔒" : ""));
        if (idx > 0) msg("Trying: " + disp);

        DataSource.Factory http = new DefaultHttpDataSource.Factory()
            .setUserAgent("StreamVault/3.3 ExoPlayer").setConnectTimeoutMs(12000).setReadTimeoutMs(12000).setAllowCrossProtocolRedirects(true);

        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);
        playbackStartTime = System.currentTimeMillis();

        MediaSource hls = new HlsMediaSource.Factory(http).setAllowChunklessPreparation(true).createMediaSource(MediaItem.fromUri(Uri.parse(sv.url)));
        final boolean[] hlsFail = {false};

        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) { loadingView.setVisibility(View.GONE); errorContainer.setVisibility(View.GONE); lastSuccessUrl = sv.url; cancelFT(); }
                else if (state == Player.STATE_BUFFERING) { loadingView.setVisibility(View.VISIBLE); if (!locked) startFT(); }
            }
            @Override public void onPlayerError(PlaybackException error) {
                cancelFT();
                if (!hlsFail[0]) { hlsFail[0] = true; retryProg(http, Uri.parse(sv.url)); return; }
                if (!locked && networkAvailable) tryNext(); else if (!networkAvailable) { msg("Network lost — waiting…"); loadingView.setVisibility(View.VISIBLE); }
                else { loadingView.setVisibility(View.GONE); errorContainer.setVisibility(View.VISIBLE); errorText.setText("Stream failed"); }
            }
        });
        player.setMediaSource(hls);
        player.setPlayWhenReady(true);
        player.prepare();
        showBrief();
    }

    private void retryProg(DataSource.Factory h, Uri u) { if (player != null) { player.stop(); player.setMediaSource(new ProgressiveMediaSource.Factory(h).createMediaSource(MediaItem.fromUri(u))); player.prepare(); player.setPlayWhenReady(true); } }
    private void tryNext() { if (locked) return; if (currentVariantIdx + 1 < variants.size()) playVariant(currentVariantIdx + 1); else if (lastSuccessUrl != null) { msg("Back to last working"); for (int i = 0; i < variants.size(); i++) if (variants.get(i).url.equals(lastSuccessUrl)) { playVariant(i); return; } } else { loadingView.setVisibility(View.GONE); errorContainer.setVisibility(View.VISIBLE); errorText.setText("All failed"); } }
    private void startFT() { cancelFT(); failoverTimeout = () -> { if (player != null && player.getPlaybackState() == Player.STATE_BUFFERING && !locked && networkAvailable && System.currentTimeMillis() - playbackStartTime > BUFFER_GRACE_MS) { msg("Buffering too long"); tryNext(); } }; handler.postDelayed(failoverTimeout, FAILOVER_TIMEOUT_MS); }
    private void cancelFT() { handler.removeCallbacks(failoverTimeout); }

    private void registerNetworkCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network n) { handler.post(() -> { boolean was = !networkAvailable; networkAvailable = true; if (was && player != null && player.getPlaybackState() == Player.STATE_IDLE) { msg("Network back"); playVariant(currentVariantIdx); } }); }
                @Override public void onLost(Network n) { handler.post(() -> { networkAvailable = false; cancelFT(); msg("Network lost"); }); }
            };
            cm.registerNetworkCallback(new NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), networkCallback);
        }
    }

    private void updPP() { playPauseBtn.setImageResource(player != null && player.isPlaying() ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play); }
    private void toggleOverlay() { setOverlayVisible(!overlayVisible); if (overlayVisible) schedHide(); }
    private void showBrief() { setOverlayVisible(true); schedHide(); }
    private void setOverlayVisible(boolean v) { overlayVisible = v; float a = v ? 1f : 0f; overlayTop.animate().alpha(a).setDuration(200).start(); overlayCenter.animate().alpha(a).setDuration(200).start(); overlayBottom.animate().alpha(a).setDuration(200).start(); }
    private void schedHide() { handler.removeCallbacks(hideOverlayRunnable); handler.postDelayed(hideOverlayRunnable, 4000); }
    private void msg(String m) { runOnUiThread(() -> Toast.makeText(this, m, Toast.LENGTH_SHORT).show()); }
    private void hideSystemUI() { getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY); }
    private void releasePlayer() { if (player != null) { player.stop(); player.release(); player = null; } }
    private int dp(int d) { return (int) (d * getResources().getDisplayMetrics().density); }
    private FrameLayout.LayoutParams mp() { return new FrameLayout.LayoutParams(-1, -1); }
    private FrameLayout.LayoutParams ctr(int w, int h) { return new FrameLayout.LayoutParams(w, h, Gravity.CENTER); }
    private ImageButton btn(int r) { ImageButton b = new ImageButton(this); b.setImageResource(r); b.setColorFilter(Color.WHITE); b.setBackgroundColor(Color.TRANSPARENT); b.setPadding(dp(8), dp(8), dp(8), dp(8)); return b; }

    @Override public void onBackPressed() { finish(); }
    @Override protected void onResume() { super.onResume(); hideSystemUI(); if (player != null) player.play(); }
    @Override protected void onPause() { if (player != null) player.pause(); super.onPause(); }
    @Override protected void onDestroy() { handler.removeCallbacksAndMessages(null); releasePlayer(); if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && networkCallback != null) { try { ((ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE)).unregisterNetworkCallback(networkCallback); } catch (Exception e) {} } super.onDestroy(); }
}
