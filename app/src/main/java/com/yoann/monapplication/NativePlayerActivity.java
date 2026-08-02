package com.yoann.monapplication;

import android.Manifest;
import android.app.PictureInPictureParams;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Rational;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;
import androidx.mediarouter.app.MediaRouteButton;

import com.google.android.gms.cast.MediaInfo;
import com.google.android.gms.cast.MediaLoadRequestData;
import com.google.android.gms.cast.framework.CastButtonFactory;
import com.google.android.gms.cast.framework.CastContext;
import com.google.android.gms.cast.framework.CastSession;
import com.google.android.gms.cast.framework.SessionManagerListener;
import com.google.android.gms.cast.framework.media.RemoteMediaClient;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.json.JSONArray;

public class NativePlayerActivity extends android.app.Activity {
    private ExoPlayer player;
    private PlayerView playerView;
    private String url;
    private String title;
    private String mimeType;
    private String contentType;
    private String poster;
    private String lastDiagnostic = "";
    private String lastCastUrl = "";
    private String lastLoadedSessionId = "";
    private CastContext castContext;
    private MediaRouteButton castButton;
    private CastProxyServer castProxyServer;
    private final List<String> playbackUrls = new ArrayList<>();
    private int playbackUrlIndex = 0;
    private boolean openCastRequested = false;

    private final SessionManagerListener<CastSession> castListener = new SessionManagerListener<CastSession>() {
        @Override public void onSessionStarted(CastSession session, String id) { loadRemote(session, false); }
        @Override public void onSessionResumed(CastSession session, boolean wasSuspended) { loadRemote(session, false); }
        @Override public void onSessionStarting(CastSession session) {}
        @Override public void onSessionStartFailed(CastSession session, int error) { report("CAST_START_FAILED_" + error, null); }
        @Override public void onSessionEnding(CastSession session) {}
        @Override public void onSessionEnded(CastSession session, int error) {
            lastLoadedSessionId = "";
            stopCastProxy();
            if (player != null) player.play();
        }
        @Override public void onSessionResuming(CastSession session, String id) {}
        @Override public void onSessionResumeFailed(CastSession session, int error) { report("CAST_RESUME_FAILED_" + error, null); }
        @Override public void onSessionSuspended(CastSession session, int reason) {}
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        url = getIntent().getStringExtra("url");
        title = value(getIntent().getStringExtra("title"), "Lecture");
        mimeType = value(getIntent().getStringExtra("mimeType"), inferMime(url));
        contentType = value(getIntent().getStringExtra("contentType"), "video");
        poster = value(getIntent().getStringExtra("poster"), "");
        openCastRequested = getIntent().getBooleanExtra("openCast", false);
        if (url == null || url.trim().isEmpty()) { finish(); return; }
        playbackUrls.add(url);
        try {
            JSONArray fallback = new JSONArray(value(getIntent().getStringExtra("fallbackUrlsJson"), "[]"));
            for (int i = 0; i < fallback.length(); i++) {
                String candidate = fallback.optString(i, "").trim();
                if (!candidate.isEmpty() && !playbackUrls.contains(candidate)) playbackUrls.add(candidate);
            }
        } catch (Exception ignored) {}


        buildUi();
        initCast();
        initPlayer();

        if (openCastRequested) {
            requestCastPermissionsAndOpen();
        }
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        playerView = new PlayerView(this);
        playerView.setUseController(true);
        root.addView(playerView, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(8), dp(8), dp(8), dp(8));
        top.setBackgroundColor(0x66000000);

        Button back = new Button(this);
        back.setText("‹");
        back.setOnClickListener(v -> finish());

        TextView text = new TextView(this);
        text.setText(title);
        text.setTextColor(0xFFFFFFFF);
        text.setTextSize(17);
        text.setPadding(12, 0, 12, 0);

        top.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        top.addView(text, new LinearLayout.LayoutParams(0, dp(48), 1));

        castButton = new MediaRouteButton(this);
        top.addView(castButton, new LinearLayout.LayoutParams(dp(48), dp(48)));

        Button castText = new Button(this);
        castText.setText("Caster");
        castText.setTextColor(0xFFFFFFFF);
        castText.setBackgroundColor(0xCCFF6A00);
        castText.setOnClickListener(v -> requestCastPermissionsAndOpen());
        top.addView(castText, new LinearLayout.LayoutParams(dp(96), dp(48)));

        Button diag = new Button(this);
        diag.setText("Diag");
        diag.setTextColor(0xFFFFFFFF);
        diag.setBackgroundColor(0x66000000);
        diag.setOnClickListener(v -> saveDiagnostic(lastDiagnostic.isEmpty() ? buildDiagnostic("MANUAL", null) : lastDiagnostic));
        top.addView(diag, new LinearLayout.LayoutParams(dp(70), dp(48)));

        FrameLayout.LayoutParams topParams = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        root.addView(top, topParams);
        setContentView(root);
    }

    private void initCast() {
        try {
            castContext = CastContext.getSharedInstance(this);
            CastButtonFactory.setUpMediaRouteButton(getApplicationContext(), castButton);
            castContext.getSessionManager().addSessionManagerListener(castListener, CastSession.class);
        } catch (Throwable error) {
            castContext = null;
            Toast.makeText(this, "Module Cast indisponible : " + value(error.getMessage(), "erreur inconnue"), Toast.LENGTH_LONG).show();
        }
    }

    private void requestCastPermissionsAndOpen() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.NEARBY_WIFI_DEVICES}, 4301);
            return;
        }
        if (Build.VERSION.SDK_INT < 33 && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 4301);
            return;
        }
        openOrLoadCast();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 4301) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) openOrLoadCast();
            else Toast.makeText(this, "Autorisation Wi-Fi requise pour détecter le téléviseur", Toast.LENGTH_LONG).show();
        }
    }

    /** Charge immédiatement le média si une TV est déjà connectée. Sinon ouvre le sélecteur Cast. */
    private void openOrLoadCast() {
        if (castContext == null) initCast();
        if (castContext == null) {
            Toast.makeText(this, "Impossible d'initialiser Google Cast", Toast.LENGTH_LONG).show();
            return;
        }
        CastSession current = castContext.getSessionManager().getCurrentCastSession();
        if (current != null && current.isConnected()) {
            loadRemote(current, true);
        } else if (castButton != null) {
            castButton.postDelayed(() -> castButton.performClick(), 350);
        }
    }

    private void initPlayer() {
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(25_000)
                .setReadTimeoutMs(60_000)
                .setUserAgent("VLC/3.0.21 LibVLC/3.0.21");

        player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(http))
                .build();
        playerView.setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override public void onPlayerError(PlaybackException error) {
                if (playbackUrlIndex + 1 < playbackUrls.size()) {
                    playbackUrlIndex++;
                    url = playbackUrls.get(playbackUrlIndex);
                    Toast.makeText(NativePlayerActivity.this, "Nouvelle tentative de lecture…", Toast.LENGTH_SHORT).show();
                    loadCurrentMedia();
                    return;
                }
                report("PLAYER_ERROR_" + error.getErrorCodeName(), error);
                showPlaybackFailure(error);
            }
        });
        loadCurrentMedia();
    }


    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void showPlaybackFailure(PlaybackException error) {
        runOnUiThread(() -> new android.app.AlertDialog.Builder(this)
                .setTitle("Lecture impossible")
                .setMessage("Le serveur a refusé ce format ou le codec n'est pas pris en charge. Code : " + error.getErrorCodeName())
                .setPositiveButton("Ouvrir avec VLC", (dialog, which) -> openExternalPlayer())
                .setNeutralButton("Réessayer", (dialog, which) -> {
                    playbackUrlIndex = 0;
                    url = playbackUrls.get(0);
                    loadCurrentMedia();
                })
                .setNegativeButton("Fermer", (dialog, which) -> finish())
                .show());
    }

    private void openExternalPlayer() {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(Uri.parse(url), value(inferMime(url), "video/*"));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, "Ouvrir le film avec…"));
        } catch (Throwable error) {
            Toast.makeText(this, "Aucun lecteur externe compatible n'est installé", Toast.LENGTH_LONG).show();
        }
    }

    private void loadCurrentMedia() {
        if (player == null) return;
        MediaItem.Builder item = new MediaItem.Builder()
                .setUri(url)
                .setMediaMetadata(new MediaMetadata.Builder().setTitle(title).build());
        String inferred = inferMime(url);
        if (inferred != null) item.setMimeType(inferred);
        player.setMediaItem(item.build(), true);
        player.prepare();
        player.play();
    }

    private void loadRemote(CastSession session, boolean force) {
        try {
            if (session == null || !session.isConnected()) {
                throw new IllegalStateException("Session Cast non connectée");
            }
            String sessionId = value(session.getSessionId(), "connected");
            if (!force && sessionId.equals(lastLoadedSessionId)) return;

            RemoteMediaClient client = session.getRemoteMediaClient();
            if (client == null) throw new IllegalStateException("RemoteMediaClient absent");

            String castSource = buildCastSource();
            String castMime = value(inferMime(url), value(mimeType, "application/x-mpegURL"));
            lastCastUrl = castSource;

            com.google.android.gms.cast.MediaMetadata metadata =
                    new com.google.android.gms.cast.MediaMetadata(
                            com.google.android.gms.cast.MediaMetadata.MEDIA_TYPE_MOVIE
                    );
            metadata.putString(com.google.android.gms.cast.MediaMetadata.KEY_TITLE, title);
            if (!poster.isEmpty()) {
                metadata.addImage(new com.google.android.gms.common.images.WebImage(Uri.parse(poster)));
            }

            MediaInfo info = new MediaInfo.Builder(castSource)
                    .setStreamType(isLive() ? MediaInfo.STREAM_TYPE_LIVE : MediaInfo.STREAM_TYPE_BUFFERED)
                    .setContentType(castMime)
                    .setMetadata(metadata)
                    .build();

            long position = isLive() || player == null ? 0 : Math.max(0, player.getCurrentPosition());
            MediaLoadRequestData request = new MediaLoadRequestData.Builder()
                    .setMediaInfo(info)
                    .setAutoplay(true)
                    .setCurrentTime(position)
                    .build();

            client.load(request).setResultCallback(result -> runOnUiThread(() -> {
                if (result.getStatus().isSuccess()) {
                    lastLoadedSessionId = sessionId;
                    if (player != null) player.pause();
                    Toast.makeText(
                            NativePlayerActivity.this,
                            "Vidéo lancée sur le téléviseur",
                            Toast.LENGTH_SHORT
                    ).show();
                } else {
                    report(
                            "CAST_MEDIA_LOAD_REJECTED_" + result.getStatus().getStatusCode(),
                            new IllegalStateException(value(result.getStatus().getStatusMessage(), "Le téléviseur a refusé le média"))
                    );
                }
            }));
        } catch (Throwable error) {
            report("CAST_LOAD_FAILED", error);
        }
    }

    private String buildCastSource() throws Exception {
        // Le proxy permet au Chromecast d'utiliser le flux réseau/VPN du téléphone.
        if (castProxyServer == null || !castProxyServer.isRunning()) {
            castProxyServer = new CastProxyServer();
            castProxyServer.start();
        }
        return castProxyServer.proxyUrl(url);
    }

    private boolean isLive() {
        String value = contentType == null ? "" : contentType.toLowerCase(Locale.US);
        return value.contains("live") || (url != null && url.toLowerCase(Locale.US).contains("/live/"));
    }

    private void stopCastProxy() {
        if (castProxyServer != null) {
            castProxyServer.stop();
            castProxyServer = null;
        }
        lastCastUrl = "";
    }

    private void report(String code, Throwable error) {
        lastDiagnostic = buildDiagnostic(code, error);
        saveDiagnostic(lastDiagnostic);
        Toast.makeText(
                this,
                "Erreur détectée. Diagnostic enregistré dans Téléchargements.",
                Toast.LENGTH_LONG
        ).show();
    }

    private String buildDiagnostic(String code, Throwable error) {
        StringBuilder out = new StringBuilder();
        out.append("STREAMBOX NATIVE DIAGNOSTIC\n");
        out.append("Date: ").append(new Date()).append("\n");
        out.append("Code: ").append(code).append("\n");
        out.append("Android: ").append(Build.VERSION.RELEASE).append(" API ").append(Build.VERSION.SDK_INT).append("\n");
        out.append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append("\n");
        out.append("Network: ").append(networkType()).append("\n");
        out.append("Title: ").append(title).append("\n");
        out.append("ContentType: ").append(contentType).append("\n");
        out.append("Mime: ").append(mimeType).append("\n");
        out.append("URL: ").append(mask(url)).append("\n");
        out.append("CastProxy: ").append(lastCastUrl.isEmpty() ? "inactive" : mask(lastCastUrl)).append("\n");
        if (player != null) {
            out.append("PlayerState: ").append(player.getPlaybackState()).append("\n");
            out.append("Position: ").append(player.getCurrentPosition()).append("\n");
            out.append("Buffered: ").append(player.getBufferedPosition()).append("\n");
        }
        if (error != null) {
            Throwable current = error;
            int depth = 0;
            while (current != null && depth < 12) {
                out.append("Cause[").append(depth).append("]: ")
                        .append(current.getClass().getName()).append(": ")
                        .append(current.getMessage()).append("\n");
                current = current.getCause();
                depth++;
            }
        }
        return out.toString();
    }

    private void saveDiagnostic(String text) {
        try {
            String name = "streambox-diagnostic-" +
                    new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".txt";
            ContentResolver resolver = getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            values.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
            if (Build.VERSION.SDK_INT >= 29) {
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/StreamBoxDiagnostics");
            }
            Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IllegalStateException("MediaStore insert null");
            try (OutputStream output = resolver.openOutputStream(uri)) {
                if (output == null) throw new IllegalStateException("OutputStream null");
                output.write(text.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable error) {
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/plain");
            share.putExtra(Intent.EXTRA_TEXT, text);
            startActivity(Intent.createChooser(share, "Partager le diagnostic"));
        }
    }

    private String networkType() {
        try {
            ConnectivityManager manager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkCapabilities capabilities = manager.getNetworkCapabilities(manager.getActiveNetwork());
            if (capabilities == null) return "offline";
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return "vpn";
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "wifi";
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return "mobile";
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return "ethernet";
            return "other";
        } catch (Throwable error) {
            return "unknown";
        }
    }

    private static String inferMime(String input) {
        if (input == null) return null;
        String value = input.toLowerCase(Locale.US);
        if (value.contains(".m3u8")) return MimeTypes.APPLICATION_M3U8;
        if (value.contains(".mpd")) return MimeTypes.APPLICATION_MPD;
        if (value.contains(".mp4")) return MimeTypes.VIDEO_MP4;
        if (value.contains(".webm")) return MimeTypes.VIDEO_WEBM;
        if (value.contains(".ts")) return MimeTypes.VIDEO_MP2T;
        if (value.contains(".mkv")) return "video/x-matroska";
        return null;
    }

    private static String value(String input, String defaultValue) {
        return input == null || input.trim().isEmpty() ? defaultValue : input;
    }

    private static String mask(String input) {
        if (input == null) return "";
        return input
                .replaceAll("/(live|movie|series)/[^/]+/[^/]+/", "/$1/***/***/")
                .replaceAll("([?&](username|password)=)[^&]+", "$1***")
                .replaceAll("([?&]u=)[^&]+", "$1***");
    }

    @Override public void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (Build.VERSION.SDK_INT >= 26 && player != null && player.isPlaying()) {
            enterPictureInPictureMode(
                    new PictureInPictureParams.Builder()
                            .setAspectRatio(new Rational(16, 9))
                            .build()
            );
        }
    }

    @Override public void onPictureInPictureModeChanged(boolean inPip, Configuration config) {
        super.onPictureInPictureModeChanged(inPip, config);
    }

    @Override protected void onStop() {
        super.onStop();
        if (player != null && !isInPictureInPictureMode() && lastLoadedSessionId.isEmpty()) player.pause();
    }

    @Override protected void onStart() {
        super.onStart();
        if (player != null && lastLoadedSessionId.isEmpty()) player.play();
    }

    @Override protected void onDestroy() {
        if (castContext != null) {
            castContext.getSessionManager().removeSessionManagerListener(castListener, CastSession.class);
        }
        stopCastProxy();
        releasePlayer();
        super.onDestroy();
    }

    private void releasePlayer() {
        if (player != null) {
            player.release();
            player = null;
        }
    }
}
