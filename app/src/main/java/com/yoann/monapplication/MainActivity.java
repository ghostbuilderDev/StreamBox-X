package com.yoann.monapplication;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.View;
import android.view.WindowManager;
import android.webkit.DownloadListener;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class MainActivity extends Activity {
    private static final int REQUEST_FILE_CHOOSER = 1001;
    private static final int REQUEST_LOCATION = 1002;
    private static final int REQUEST_MEDIA = 1003;
    private static final int REQUEST_NOTIFICATIONS = 1004;
    private static final int REQUEST_STORAGE = 1005;

    private static final boolean ENABLE_LOCATION = true;
    private static final boolean ENABLE_CAMERA = true;
    private static final boolean ENABLE_MICROPHONE = true;
    private static final boolean OPEN_EXTERNAL_LINKS = true;

    private WebView webView;
    private ValueCallback<Uri[]> fileChooserCallback;
    private boolean fileChooserAllowsMultiple = false;
    private String pendingGeoOrigin;
    private GeolocationPermissions.Callback pendingGeoCallback;
    private PermissionRequest pendingMediaRequest;

    @SuppressLint({"SetJavaScriptEnabled", "AllowFileAccessFromFileURLs"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().setStatusBarColor(Color.parseColor("#FF7A1A"));
        getWindow().setNavigationBarColor(Color.parseColor("#11141B"));

        webView = new WebView(this);
        setContentView(webView);
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setSupportMultipleWindows(false);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        webView.setWebViewClient(new WebViewClient() {
            private boolean handleUri(Uri uri) {
                if (uri == null) return false;
                String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
                if ("file".equals(scheme) || "about".equals(scheme) || "data".equals(scheme) || "blob".equals(scheme)) {
                    return false;
                }
                if (("http".equals(scheme) || "https".equals(scheme)) && !OPEN_EXTERNAL_LINKS) {
                    return false;
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                    return true;
                } catch (Exception error) {
                    Toast.makeText(MainActivity.this, "Impossible d’ouvrir ce lien", Toast.LENGTH_SHORT).show();
                    return true;
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleUri(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleUri(Uri.parse(url));
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(
                    WebView view,
                    ValueCallback<Uri[]> callback,
                    FileChooserParams params
            ) {
                if (fileChooserCallback != null) {
                    fileChooserCallback.onReceiveValue(null);
                }

                fileChooserCallback = callback;

                // Android System WebView peut annoncer à tort un mode
                // fichier unique, même pour <input type="file" multiple>.
                // On ouvre donc toujours le sélecteur système en mode multiple.
                fileChooserAllowsMultiple = true;

                try {
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("*/*");
                    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    intent.addFlags(
                            Intent.FLAG_GRANT_READ_URI_PERMISSION |
                            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                    );

                    if (params != null) {
                        String[] rawTypes = params.getAcceptTypes();
                        List<String> acceptedTypes = new ArrayList<>();

                        if (rawTypes != null) {
                            for (String rawType : rawTypes) {
                                if (rawType == null) continue;
                                String type = rawType.trim();
                                if (!type.isEmpty() &&
                                        !acceptedTypes.contains(type)) {
                                    acceptedTypes.add(type);
                                }
                            }
                        }

                        if (acceptedTypes.size() == 1) {
                            intent.setType(acceptedTypes.get(0));
                        } else if (acceptedTypes.size() > 1) {
                            intent.putExtra(
                                    Intent.EXTRA_MIME_TYPES,
                                    acceptedTypes.toArray(new String[0])
                            );
                        }
                    }

                    startActivityForResult(
                            Intent.createChooser(
                                    intent,
                                    "Sélectionner un ou plusieurs fichiers"
                            ),
                            REQUEST_FILE_CHOOSER
                    );
                    return true;
                } catch (Exception error) {
                    fileChooserCallback = null;
                    fileChooserAllowsMultiple = false;
                    Toast.makeText(
                            MainActivity.this,
                            "Sélecteur de fichier indisponible",
                            Toast.LENGTH_LONG
                    ).show();
                    return false;
                }
            }

            @Override
            public void onGeolocationPermissionsShowPrompt(
                    String origin,
                    GeolocationPermissions.Callback callback
            ) {
                if (!ENABLE_LOCATION) {
                    callback.invoke(origin, false, false);
                    return;
                }
                if (hasLocationPermission()) {
                    callback.invoke(origin, true, false);
                    return;
                }
                pendingGeoOrigin = origin;
                pendingGeoCallback = callback;
                requestPermissions(
                        new String[]{
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                        },
                        REQUEST_LOCATION
                );
            }

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                handleMediaPermissionRequest(request);
            }
        });

        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(
                    String url,
                    String userAgent,
                    String contentDisposition,
                    String mimeType,
                    long contentLength
            ) {
                try {
                    if (url.startsWith("blob:") || url.startsWith("data:")) {
                        Toast.makeText(
                                MainActivity.this,
                                "Le pont d’enregistrement Android n’a pas reçu ce fichier.",
                                Toast.LENGTH_LONG
                        ).show();
                        return;
                    }
                    DownloadManager.Request download =
                            new DownloadManager.Request(Uri.parse(url));
                    String filename =
                            URLUtil.guessFileName(url, contentDisposition, mimeType);
                    download.setTitle(filename);
                    download.setDescription("Téléchargement depuis Xtream-X");
                    download.setMimeType(mimeType);
                    if (userAgent != null) {
                        download.addRequestHeader("User-Agent", userAgent);
                    }
                    download.setNotificationVisibility(
                            DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                    );
                    download.setDestinationInExternalPublicDir(
                            Environment.DIRECTORY_DOWNLOADS,
                            filename
                    );
                    DownloadManager manager =
                            (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                    if (manager != null) manager.enqueue(download);
                } catch (Exception error) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                    } catch (Exception ignored) {
                        Toast.makeText(
                                MainActivity.this,
                                "Téléchargement impossible",
                                Toast.LENGTH_SHORT
                        ).show();
                    }
                }
            }
        });

        webView.addJavascriptInterface(new NativeBridge(this), "AndroidApp");
        webView.loadUrl("file:///android_asset/www/index.html");
    }

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED;
    }

    private void handleMediaPermissionRequest(PermissionRequest request) {
        List<String> missing = new ArrayList<>();

        for (String resource : request.getResources()) {
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)
                    && ENABLE_CAMERA
                    && checkSelfPermission(Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.CAMERA);
            }
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)
                    && ENABLE_MICROPHONE
                    && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.RECORD_AUDIO);
            }
        }

        if (!missing.isEmpty()) {
            pendingMediaRequest = request;
            requestPermissions(missing.toArray(new String[0]), REQUEST_MEDIA);
        } else {
            grantAllowedMedia(request);
        }
    }

    private void grantAllowedMedia(PermissionRequest request) {
        List<String> allowed = new ArrayList<>();

        for (String resource : request.getResources()) {
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)
                    && ENABLE_CAMERA
                    && checkSelfPermission(Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED) {
                allowed.add(resource);
            }
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)
                    && ENABLE_MICROPHONE
                    && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED) {
                allowed.add(resource);
            }
        }

        if (allowed.isEmpty()) {
            request.deny();
        } else {
            request.grant(allowed.toArray(new String[0]));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_FILE_CHOOSER && fileChooserCallback != null) {
            List<Uri> selectedUris = new ArrayList<>();

            if (resultCode == Activity.RESULT_OK && data != null) {
                ClipData clipData = data.getClipData();

                if (clipData != null) {
                    for (int index = 0; index < clipData.getItemCount(); index++) {
                        Uri uri = clipData.getItemAt(index).getUri();
                        if (uri != null && !selectedUris.contains(uri)) {
                            selectedUris.add(uri);
                        }
                    }
                }

                Uri singleUri = data.getData();
                if (singleUri != null && !selectedUris.contains(singleUri)) {
                    selectedUris.add(singleUri);
                }

                int grantedFlags = data.getFlags() &
                        (Intent.FLAG_GRANT_READ_URI_PERMISSION |
                         Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

                for (Uri uri : selectedUris) {
                    try {
                        getContentResolver().takePersistableUriPermission(
                                uri,
                                grantedFlags &
                                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                        );
                    } catch (Exception ignored) {
                        // Certains fournisseurs Android ne permettent pas
                        // la conservation durable de l'autorisation.
                    }
                }

                if (selectedUris.isEmpty()) {
                    Uri[] parsed =
                            WebChromeClient.FileChooserParams.parseResult(
                                    resultCode,
                                    data
                            );
                    if (parsed != null) {
                        for (Uri uri : parsed) {
                            if (uri != null && !selectedUris.contains(uri)) {
                                selectedUris.add(uri);
                            }
                        }
                    }
                }
            }

            Uri[] result = selectedUris.isEmpty()
                    ? null
                    : selectedUris.toArray(new Uri[0]);

            fileChooserCallback.onReceiveValue(result);
            fileChooserCallback = null;
            fileChooserAllowsMultiple = false;
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_LOCATION && pendingGeoCallback != null) {
            pendingGeoCallback.invoke(
                    pendingGeoOrigin,
                    hasLocationPermission(),
                    false
            );
            pendingGeoCallback = null;
            pendingGeoOrigin = null;
        }

        if (requestCode == REQUEST_MEDIA && pendingMediaRequest != null) {
            grantAllowedMedia(pendingMediaRequest);
            pendingMediaRequest = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.removeJavascriptInterface("AndroidApp");
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    public static final class NativeBridge {
        private final Activity activity;
        private final Map<String, ByteArrayOutputStream> fileBuffers =
                new ConcurrentHashMap<>();
        private final Map<String, String> fileNames =
                new ConcurrentHashMap<>();
        private final Map<String, String> fileMimeTypes =
                new ConcurrentHashMap<>();

        NativeBridge(Activity activity) {
            this.activity = activity;
        }


        private String cleanFilename(String value) {
            String name = value == null ? "fichier.bin" : value.trim();
            StringBuilder cleaned = new StringBuilder();

            for (int index = 0; index < name.length(); index++) {
                char character = name.charAt(index);
                int code = character;

                boolean forbidden =
                        code == 92 ||   // antislash
                        code == 47 ||   // slash
                        code == 58 ||   // deux-points
                        code == 42 ||   // astérisque
                        code == 63 ||   // point d'interrogation
                        code == 34 ||   // guillemet
                        code == 60 ||   // inférieur
                        code == 62 ||   // supérieur
                        code == 124;    // barre verticale

                cleaned.append(forbidden ? '_' : character);
            }

            String safeName = cleaned.toString().trim();
            return safeName.isEmpty() ? "fichier.bin" : safeName;
        }

        private File uniqueLegacyFile(File directory, String filename) {
            File target = new File(directory, filename);
            if (!target.exists()) return target;
            int dot = filename.lastIndexOf('.');
            String base = dot > 0 ? filename.substring(0, dot) : filename;
            String extension = dot > 0 ? filename.substring(dot) : "";
            int index = 1;
            while (target.exists()) {
                target = new File(
                        directory,
                        base + " (" + index + ")" + extension
                );
                index++;
            }
            return target;
        }

        private boolean saveBytesToDownloads(
                byte[] bytes,
                String requestedFilename,
                String requestedMimeType
        ) {
            final String filename = cleanFilename(requestedFilename);
            final String mimeType =
                    requestedMimeType == null ||
                    requestedMimeType.trim().isEmpty()
                            ? "application/octet-stream"
                            : requestedMimeType.trim();

            try {
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    ContentResolver resolver =
                            activity.getContentResolver();
                    ContentValues values = new ContentValues();
                    values.put(
                            MediaStore.MediaColumns.DISPLAY_NAME,
                            filename
                    );
                    values.put(
                            MediaStore.MediaColumns.MIME_TYPE,
                            mimeType
                    );
                    values.put(
                            MediaStore.MediaColumns.RELATIVE_PATH,
                            Environment.DIRECTORY_DOWNLOADS
                    );
                    values.put(
                            MediaStore.MediaColumns.IS_PENDING,
                            1
                    );

                    Uri uri = resolver.insert(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                            values
                    );
                    if (uri == null) {
                        throw new IllegalStateException(
                                "Android n'a pas créé le fichier."
                        );
                    }

                    try (OutputStream output =
                                 resolver.openOutputStream(uri)) {
                        if (output == null) {
                            throw new IllegalStateException(
                                    "Flux d'écriture indisponible."
                            );
                        }
                        output.write(bytes);
                        output.flush();
                    } catch (Exception error) {
                        resolver.delete(uri, null, null);
                        throw error;
                    }

                    values.clear();
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0);
                    resolver.update(uri, values, null, null);
                } else {
                    if (
                            activity.checkSelfPermission(
                                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                            ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        activity.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                activity.requestPermissions(
                                        new String[]{
                                                Manifest.permission.WRITE_EXTERNAL_STORAGE
                                        },
                                        REQUEST_STORAGE
                                );
                                Toast.makeText(
                                        activity,
                                        "Autorise le stockage puis relance le téléchargement.",
                                        Toast.LENGTH_LONG
                                ).show();
                            }
                        });
                        return false;
                    }

                    File directory =
                            Environment.getExternalStoragePublicDirectory(
                                    Environment.DIRECTORY_DOWNLOADS
                            );
                    if (!directory.exists() && !directory.mkdirs()) {
                        throw new IllegalStateException(
                                "Dossier Téléchargements inaccessible."
                        );
                    }

                    File target = uniqueLegacyFile(directory, filename);
                    try (FileOutputStream output =
                                 new FileOutputStream(target)) {
                        output.write(bytes);
                        output.flush();
                    }

                    MediaScannerConnection.scanFile(
                            activity,
                            new String[]{target.getAbsolutePath()},
                            new String[]{mimeType},
                            null
                    );
                }

                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(
                                activity,
                                "Fichier enregistré dans Téléchargements",
                                Toast.LENGTH_LONG
                        ).show();
                    }
                });
                return true;
            } catch (final Exception error) {
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(
                                activity,
                                "Enregistrement impossible : " +
                                        error.getMessage(),
                                Toast.LENGTH_LONG
                        ).show();
                    }
                });
                return false;
            }
        }

        @JavascriptInterface
        public String beginFileDownload(
                String filename,
                String mimeType
        ) {
            String sessionId = UUID.randomUUID().toString();
            fileBuffers.put(sessionId, new ByteArrayOutputStream());
            fileNames.put(sessionId, cleanFilename(filename));
            fileMimeTypes.put(
                    sessionId,
                    mimeType == null
                            ? "application/octet-stream"
                            : mimeType
            );
            return sessionId;
        }

        @JavascriptInterface
        public boolean appendFileChunk(
                String sessionId,
                String base64Chunk
        ) {
            ByteArrayOutputStream buffer = fileBuffers.get(sessionId);
            if (buffer == null || base64Chunk == null) return false;

            try {
                byte[] chunk = Base64.decode(
                        base64Chunk,
                        Base64.NO_WRAP
                );
                buffer.write(chunk);
                return true;
            } catch (Exception error) {
                cancelFileDownload(sessionId);
                return false;
            }
        }

        @JavascriptInterface
        public boolean finishFileDownload(String sessionId) {
            ByteArrayOutputStream buffer =
                    fileBuffers.remove(sessionId);
            String filename = fileNames.remove(sessionId);
            String mimeType = fileMimeTypes.remove(sessionId);

            if (buffer == null || filename == null) return false;

            try {
                byte[] bytes = buffer.toByteArray();
                buffer.close();
                return saveBytesToDownloads(
                        bytes,
                        filename,
                        mimeType
                );
            } catch (Exception error) {
                return false;
            }
        }

        @JavascriptInterface
        public void cancelFileDownload(String sessionId) {
            ByteArrayOutputStream buffer =
                    fileBuffers.remove(sessionId);
            fileNames.remove(sessionId);
            fileMimeTypes.remove(sessionId);
            if (buffer != null) {
                try {
                    buffer.close();
                } catch (Exception ignored) {
                }
            }
        }

        @JavascriptInterface
        public void showToast(final String message) {
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
                }
            });
        }

        @JavascriptInterface
        public void share(final String text) {
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Intent intent = new Intent(Intent.ACTION_SEND);
                    intent.setType("text/plain");
                    intent.putExtra(Intent.EXTRA_TEXT, text);
                    activity.startActivity(Intent.createChooser(intent, "Partager"));
                }
            });
        }

        @JavascriptInterface
        public void copyText(final String text) {
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    ClipboardManager clipboard =
                            (ClipboardManager) activity.getSystemService(
                                    Context.CLIPBOARD_SERVICE
                            );
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(
                                ClipData.newPlainText("Texte", text)
                        );
                    }
                    Toast.makeText(activity, "Copié", Toast.LENGTH_SHORT).show();
                }
            });
        }

        @JavascriptInterface
        public void vibrate(long milliseconds) {
            Vibrator vibrator =
                    (Vibrator) activity.getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator == null) return;

            long duration = Math.max(1L, Math.min(milliseconds, 2000L));
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                vibrator.vibrate(
                        VibrationEffect.createOneShot(
                                duration,
                                VibrationEffect.DEFAULT_AMPLITUDE
                        )
                );
            } else {
                vibrator.vibrate(duration);
            }
        }

        @JavascriptInterface
        public void openUrl(final String url) {
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        activity.startActivity(
                                new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        );
                    } catch (Exception ignored) {
                    }
                }
            });
        }


        private void sendBackgroundIntent(
                String action,
                String title,
                String message,
                boolean success
        ) {
            Intent intent = new Intent(activity, BuildForegroundService.class);
            intent.setAction(action);
            intent.putExtra(BuildForegroundService.EXTRA_TITLE, title);
            intent.putExtra(BuildForegroundService.EXTRA_MESSAGE, message);
            intent.putExtra(BuildForegroundService.EXTRA_SUCCESS, success);

            if (android.os.Build.VERSION.SDK_INT >= 26) {
                activity.startForegroundService(intent);
            } else {
                activity.startService(intent);
            }
        }

        @JavascriptInterface
        public void startBackgroundTask(final String title, final String message) {
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (android.os.Build.VERSION.SDK_INT >= 33 &&
                            activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                                    != PackageManager.PERMISSION_GRANTED) {
                        activity.requestPermissions(
                                new String[]{Manifest.permission.POST_NOTIFICATIONS},
                                REQUEST_NOTIFICATIONS
                        );
                    }
                    sendBackgroundIntent(
                            BuildForegroundService.ACTION_START,
                            title,
                            message,
                            true
                    );
                }
            });
        }

        @JavascriptInterface
        public void updateBackgroundTask(final String message) {
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    sendBackgroundIntent(
                            BuildForegroundService.ACTION_UPDATE,
                            "Génération APK en cours",
                            message,
                            true
                    );
                }
            });
        }

        @JavascriptInterface
        public void finishBackgroundTask(final String message, final boolean success) {
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    sendBackgroundIntent(
                            BuildForegroundService.ACTION_FINISH,
                            success ? "APK prêt" : "Génération interrompue",
                            message,
                            success
                    );
                }
            });
        }

        @JavascriptInterface
        public boolean supportsBackgroundTask() {
            return true;
        }


        @JavascriptInterface
        public boolean supportsNativeIptv() { return true; }

        @JavascriptInterface
        public void playNative(final String url, final String title, final String contentType, final String poster, final String fallbackUrlsJson) {
            activity.runOnUiThread(() -> {
                try {
                    Intent intent = new Intent(activity, NativePlayerActivity.class);
                    intent.putExtra("url", url);
                    intent.putExtra("title", title);
                    intent.putExtra("contentType", contentType);
                    intent.putExtra("poster", poster);
                    intent.putExtra("fallbackUrlsJson", fallbackUrlsJson == null ? "[]" : fallbackUrlsJson);
                    activity.startActivity(intent);
                } catch (Exception error) {
                    Toast.makeText(activity, "Lecteur natif indisponible : " + error.getMessage(), Toast.LENGTH_LONG).show();
                }
            });
        }

        @JavascriptInterface
        public void castNative(final String url, final String title, final String mimeType, final String poster) {
            activity.runOnUiThread(() -> {
                try {
                    Intent intent = new Intent(activity, NativePlayerActivity.class);
                    intent.putExtra("url", url);
                    intent.putExtra("title", title);
                    intent.putExtra("mimeType", mimeType);
                    intent.putExtra("poster", poster);
                    intent.putExtra("openCast", true);
                    activity.startActivity(intent);
                } catch (Exception error) {
                    Toast.makeText(activity, "Cast indisponible : " + error.getMessage(), Toast.LENGTH_LONG).show();
                }
            });
        }

        @JavascriptInterface
        public boolean saveDiagnostic(String filename, String content) {
            return saveBytesToDownloads(
                content == null ? new byte[0] : content.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                filename == null ? "streambox-diagnostic.txt" : filename,
                "text/plain"
            );
        }

        @JavascriptInterface
        public void saveSecret(String key, String value) {
            activity.getSharedPreferences("secure_profile", Context.MODE_PRIVATE)
                .edit().putString(key, value == null ? "" : value).apply();
        }

        @JavascriptInterface
        public String loadSecret(String key) {
            return activity.getSharedPreferences("secure_profile", Context.MODE_PRIVATE)
                .getString(key, "");
        }


        @JavascriptInterface
        public void saveCredentials(String name, String server, String username, String password) {
            activity.getSharedPreferences("secure_profile", Context.MODE_PRIVATE)
                .edit()
                .putString("credential_name", name == null ? "" : name)
                .putString("credential_server", server == null ? "" : server)
                .putString("credential_username", username == null ? "" : username)
                .putString("credential_password", password == null ? "" : password)
                .commit();
        }

        @JavascriptInterface
        public String loadCredentials() {
            android.content.SharedPreferences prefs = activity.getSharedPreferences("secure_profile", Context.MODE_PRIVATE);
            try {
                org.json.JSONObject value = new org.json.JSONObject();
                value.put("name", prefs.getString("credential_name", ""));
                value.put("server", prefs.getString("credential_server", ""));
                value.put("username", prefs.getString("credential_username", ""));
                value.put("password", prefs.getString("credential_password", ""));
                return value.toString();
            } catch (Exception ignored) {
                return "{}";
            }
        }

        @JavascriptInterface
        public void saveAccountJson(String json) {
            activity.getSharedPreferences("secure_profile", Context.MODE_PRIVATE)
                    .edit().putString("account_json", json == null ? "" : json).commit();
        }

        @JavascriptInterface
        public String loadAccountJson() {
            return activity.getSharedPreferences("secure_profile", Context.MODE_PRIVATE)
                    .getString("account_json", "");
        }

        @JavascriptInterface
        public String getPackageName() {
            return activity.getPackageName();
        }
    }
}
