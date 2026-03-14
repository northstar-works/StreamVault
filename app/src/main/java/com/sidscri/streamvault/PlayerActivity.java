package com.sidscri.streamvault;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
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

@OptIn(markerClass = UnstableApi.class)
public class PlayerActivity extends Activity {

    public static final String EXTRA_URL = "stream_url";
    public static final String EXTRA_TITLE = "stream_title";
    public static final String EXTRA_CATEGORY = "stream_category";

    private ExoPlayer player;
    private PlayerView playerView;
    private View overlayTop;
    private View overlayCenter;
    private View overlayBottom;
    private View loadingView;
    private TextView titleText;
    private TextView categoryText;
    private TextView errorText;
    private View errorContainer;
    private ImageButton playPauseBtn;
    private Handler handler;
    private Runnable hideOverlayRunnable;
    private boolean overlayVisible = false;
    private String streamUrl;
    private String streamTitle;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(Color.TRANSPARENT);
            getWindow().setNavigationBarColor(Color.BLACK);
        }

        streamUrl = getIntent().getStringExtra(EXTRA_URL);
        streamTitle = getIntent().getStringExtra(EXTRA_TITLE);
        String category = getIntent().getStringExtra(EXTRA_CATEGORY);

        if (streamUrl == null || streamUrl.isEmpty()) {
            finish();
            return;
        }

        handler = new Handler(Looper.getMainLooper());
        hideOverlayRunnable = () -> setOverlayVisible(false);

        buildUI(category);
        initPlayer();
        hideSystemUI();
    }

    private void buildUI(String category) {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        // ── ExoPlayer view ──
        playerView = new PlayerView(this);
        playerView.setUseController(false); // We use our own overlay
        playerView.setKeepScreenOn(true);
        root.addView(playerView, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT));

        // ── Loading spinner ──
        loadingView = new ProgressBar(this, null, android.R.attr.progressBarStyleLarge);
        ((ProgressBar) loadingView).setIndeterminate(true);
        FrameLayout.LayoutParams loadLP = new FrameLayout.LayoutParams(
            dpToPx(48), dpToPx(48), Gravity.CENTER);
        root.addView(loadingView, loadLP);

        // ── Error view ──
        errorContainer = new LinearLayout(this);
        ((LinearLayout) errorContainer).setOrientation(LinearLayout.VERTICAL);
        ((LinearLayout) errorContainer).setGravity(Gravity.CENTER);
        errorContainer.setVisibility(View.GONE);

        errorText = new TextView(this);
        errorText.setTextColor(Color.parseColor("#aaaaaa"));
        errorText.setTextSize(14);
        errorText.setGravity(Gravity.CENTER);
        errorText.setPadding(dpToPx(30), 0, dpToPx(30), dpToPx(16));
        ((LinearLayout) errorContainer).addView(errorText);

        TextView retryBtn = new TextView(this);
        retryBtn.setText("Tap to Retry");
        retryBtn.setTextColor(Color.parseColor("#6c5ce7"));
        retryBtn.setTextSize(16);
        retryBtn.setPadding(dpToPx(24), dpToPx(12), dpToPx(24), dpToPx(12));
        retryBtn.setBackgroundColor(Color.parseColor("#1a1a2e"));
        retryBtn.setOnClickListener(v -> {
            errorContainer.setVisibility(View.GONE);
            loadingView.setVisibility(View.VISIBLE);
            initPlayer();
        });
        ((LinearLayout) errorContainer).addView(retryBtn);

        FrameLayout.LayoutParams errLP = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        root.addView(errorContainer, errLP);

        // ── Touch overlay (invisible, catches taps) ──
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
                    // Double tap center = play/pause
                    if (player != null) {
                        if (player.isPlaying()) player.pause();
                        else player.play();
                        updatePlayPauseIcon();
                    }
                    return true;
                }
            });
        touchCatcher.setOnTouchListener((v, event) -> {
            gd.onTouchEvent(event);
            return true;
        });
        root.addView(touchCatcher, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT));

        // ── Top overlay (back + title) ──
        overlayTop = buildTopOverlay(category);
        root.addView(overlayTop, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        // ── Center overlay (prev / play-pause / next) ──
        overlayCenter = buildCenterOverlay();
        root.addView(overlayCenter, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        // ── Bottom overlay (URL info) ──
        overlayBottom = buildBottomOverlay();
        root.addView(overlayBottom, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        setOverlayVisible(false);
        setContentView(root);
    }

    private View buildTopOverlay(String category) {
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dpToPx(12), dpToPx(44), dpToPx(16), dpToPx(16));
        top.setBackgroundColor(Color.parseColor("#CC000000"));

        // Back button
        ImageButton backBtn = new ImageButton(this);
        backBtn.setImageResource(android.R.drawable.ic_media_previous);
        backBtn.setBackgroundColor(Color.TRANSPARENT);
        backBtn.setColorFilter(Color.WHITE);
        backBtn.setPadding(dpToPx(12), dpToPx(12), dpToPx(12), dpToPx(12));
        backBtn.setOnClickListener(v -> finish());
        top.addView(backBtn);

        // Title area
        LinearLayout titleArea = new LinearLayout(this);
        titleArea.setOrientation(LinearLayout.VERTICAL);
        titleArea.setPadding(dpToPx(8), 0, 0, 0);

        TextView nowPlaying = new TextView(this);
        nowPlaying.setText("NOW PLAYING");
        nowPlaying.setTextColor(Color.parseColor("#80ffffff"));
        nowPlaying.setTextSize(10);
        nowPlaying.setLetterSpacing(0.1f);
        titleArea.addView(nowPlaying);

        titleText = new TextView(this);
        titleText.setText(streamTitle != null ? streamTitle : "Stream");
        titleText.setTextColor(Color.WHITE);
        titleText.setTextSize(16);
        titleText.setSingleLine(true);
        titleArea.addView(titleText);

        if (category != null && !category.isEmpty()) {
            categoryText = new TextView(this);
            categoryText.setText(category);
            categoryText.setTextColor(Color.parseColor("#60ffffff"));
            categoryText.setTextSize(12);
            titleArea.addView(categoryText);
        }

        LinearLayout.LayoutParams titleLP = new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        top.addView(titleArea, titleLP);

        return top;
    }

    private View buildCenterOverlay() {
        LinearLayout center = new LinearLayout(this);
        center.setOrientation(LinearLayout.HORIZONTAL);
        center.setGravity(Gravity.CENTER);
        int gap = dpToPx(28);

        // Play/Pause button
        playPauseBtn = new ImageButton(this);
        playPauseBtn.setImageResource(android.R.drawable.ic_media_pause);
        playPauseBtn.setColorFilter(Color.WHITE);
        playPauseBtn.setBackgroundColor(Color.parseColor("#6c5ce7"));
        playPauseBtn.setPadding(dpToPx(20), dpToPx(20), dpToPx(20), dpToPx(20));
        playPauseBtn.setOnClickListener(v -> {
            if (player != null) {
                if (player.isPlaying()) player.pause();
                else player.play();
                updatePlayPauseIcon();
                scheduleHideOverlay();
            }
        });

        center.addView(playPauseBtn);

        return center;
    }

    private View buildBottomOverlay() {
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setGravity(Gravity.CENTER);
        bottom.setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(36));
        bottom.setBackgroundColor(Color.parseColor("#CC000000"));

        TextView urlInfo = new TextView(this);
        urlInfo.setText(streamUrl != null ? streamUrl : "");
        urlInfo.setTextColor(Color.parseColor("#40ffffff"));
        urlInfo.setTextSize(10);
        urlInfo.setSingleLine(true);
        urlInfo.setGravity(Gravity.CENTER);
        bottom.addView(urlInfo);

        return bottom;
    }

    private void initPlayer() {
        releasePlayer();

        DataSource.Factory httpFactory = new DefaultHttpDataSource.Factory()
            .setUserAgent("StreamVault/3.0 ExoPlayer")
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(15000)
            .setAllowCrossProtocolRedirects(true);

        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);

        Uri uri = Uri.parse(streamUrl);

        // IPTV strategy: Try HLS first for everything (most IPTV is HLS)
        // If HLS fails, retry as progressive
        final boolean[] hlsFailed = {false};

        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    loadingView.setVisibility(View.GONE);
                    errorContainer.setVisibility(View.GONE);
                } else if (state == Player.STATE_BUFFERING) {
                    loadingView.setVisibility(View.VISIBLE);
                } else if (state == Player.STATE_ENDED) {
                    finish();
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                if (!hlsFailed[0]) {
                    // HLS failed — retry as progressive
                    hlsFailed[0] = true;
                    retryAsProgressive(httpFactory, uri);
                    return;
                }
                // Both failed
                loadingView.setVisibility(View.GONE);
                errorContainer.setVisibility(View.VISIBLE);
                String msg = "Cannot play this stream";
                if (error.getCause() != null) {
                    msg += "\n" + error.getCause().getMessage();
                }
                errorText.setText(msg);
            }
        });

        // Start with HLS
        MediaSource hlsSource = new HlsMediaSource.Factory(httpFactory)
            .setAllowChunklessPreparation(true)
            .createMediaSource(MediaItem.fromUri(uri));

        player.setMediaSource(hlsSource);
        player.setPlayWhenReady(true);
        player.prepare();

        showOverlayBriefly();
    }

    private void retryAsProgressive(DataSource.Factory httpFactory, Uri uri) {
        if (player != null) {
            player.stop();
            MediaSource progressiveSource = new ProgressiveMediaSource.Factory(httpFactory)
                .createMediaSource(MediaItem.fromUri(uri));
            player.setMediaSource(progressiveSource);
            player.prepare();
            player.setPlayWhenReady(true);
        }
    }

    private void updatePlayPauseIcon() {
        if (player != null && player.isPlaying()) {
            playPauseBtn.setImageResource(android.R.drawable.ic_media_pause);
        } else {
            playPauseBtn.setImageResource(android.R.drawable.ic_media_play);
        }
    }

    // ── Overlay visibility ──
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
        int duration = 250;
        overlayTop.animate().alpha(alpha).setDuration(duration).start();
        overlayCenter.animate().alpha(alpha).setDuration(duration).start();
        overlayBottom.animate().alpha(alpha).setDuration(duration).start();
    }

    private void scheduleHideOverlay() {
        handler.removeCallbacks(hideOverlayRunnable);
        handler.postDelayed(hideOverlayRunnable, 4000);
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

    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onBackPressed() {
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
        handler.removeCallbacksAndMessages(null);
        releasePlayer();
        super.onDestroy();
    }
}
