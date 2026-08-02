package com.yoann.monapplication;

import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Petit relais HTTP local utilisé par Google Cast.
 *
 * Le Chromecast ne partage pas automatiquement le VPN du téléphone. Ce relais
 * reçoit la requête du téléviseur sur le réseau local puis récupère le flux via
 * la connexion Android (et donc via le VPN actif, lorsqu'il y en a un).
 */
public final class CastProxyServer {
    private static final String TAG = "StreamBoxCastProxy";
    private static final String USER_AGENT = "VLC/3.0.21 LibVLC/3.0.21";
    private static final Pattern URI_ATTRIBUTE = Pattern.compile("URI=\"([^\"]+)\"");

    private final ExecutorService workers = Executors.newCachedThreadPool();
    private volatile boolean running;
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private String localAddress;

    public synchronized void start() throws IOException {
        if (running && serverSocket != null && !serverSocket.isClosed()) return;

        localAddress = findReachableLanAddress();
        if (localAddress == null || localAddress.isEmpty()) {
            throw new IOException("Adresse Wi-Fi locale introuvable. Le téléphone et le téléviseur doivent être sur le même Wi-Fi.");
        }

        serverSocket = new ServerSocket(0);
        serverSocket.setReuseAddress(true);
        running = true;
        acceptThread = new Thread(this::acceptLoop, "streambox-cast-proxy");
        acceptThread.setDaemon(true);
        acceptThread.start();
        Log.i(TAG, "Proxy démarré sur " + getBaseUrl());
    }

    public synchronized void stop() {
        running = false;
        if (serverSocket != null) {
            try { serverSocket.close(); } catch (IOException ignored) {}
            serverSocket = null;
        }
        if (acceptThread != null) {
            acceptThread.interrupt();
            acceptThread = null;
        }
        workers.shutdownNow();
    }

    public boolean isRunning() {
        return running && serverSocket != null && !serverSocket.isClosed();
    }

    public String proxyUrl(String upstreamUrl) throws IOException {
        start();
        return getBaseUrl() + "/proxy?u=" + URLEncoder.encode(upstreamUrl, StandardCharsets.UTF_8.name());
    }

    public String getBaseUrl() {
        ServerSocket socket = serverSocket;
        if (socket == null || localAddress == null) return "";
        return "http://" + localAddress + ":" + socket.getLocalPort();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                socket.setSoTimeout(45_000);
                workers.execute(() -> handle(socket));
            } catch (IOException error) {
                if (running) Log.w(TAG, "Accept impossible", error);
            }
        }
    }

    private void handle(Socket socket) {
        try (Socket client = socket;
             BufferedInputStream input = new BufferedInputStream(client.getInputStream());
             BufferedOutputStream output = new BufferedOutputStream(client.getOutputStream())) {

            HttpRequest request = readRequest(input);
            if (request == null) return;
            if (!"GET".equals(request.method) && !"HEAD".equals(request.method)) {
                writeSimple(output, 405, "Method Not Allowed", "Méthode non prise en charge");
                return;
            }

            String upstream = queryParameter(request.target, "u");
            if (upstream == null || upstream.trim().isEmpty()) {
                writeSimple(output, 400, "Bad Request", "URL source absente");
                return;
            }
            proxy(request, output, upstream);
        } catch (Throwable error) {
            Log.w(TAG, "Requête proxy interrompue", error);
        }
    }

    private void proxy(HttpRequest request, OutputStream output, String upstreamUrl) throws IOException {
        HttpURLConnection connection = null;
        try {
            URL upstream = new URL(upstreamUrl);
            connection = (HttpURLConnection) upstream.openConnection();
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(20_000);
            connection.setReadTimeout(45_000);
            connection.setRequestMethod("HEAD".equals(request.method) ? "HEAD" : "GET");
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("Accept", "*/*");
            connection.setRequestProperty("Accept-Encoding", "identity");
            connection.setRequestProperty("Connection", "close");
            connection.setRequestProperty("Icy-MetaData", "1");

            String range = request.headers.get("range");
            if (range != null && !range.isEmpty()) connection.setRequestProperty("Range", range);
            String referer = request.headers.get("referer");
            if (referer != null && !referer.isEmpty()) connection.setRequestProperty("Referer", referer);

            int code = connection.getResponseCode();
            String contentType = safeContentType(connection.getContentType(), upstreamUrl);
            boolean playlist = isPlaylist(contentType, upstreamUrl);

            InputStream raw = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (playlist && raw != null && !"HEAD".equals(request.method) && code >= 200 && code < 400) {
                byte[] original = readAll(raw);
                String rewritten = rewritePlaylist(new String(original, StandardCharsets.UTF_8), upstream);
                byte[] body = rewritten.getBytes(StandardCharsets.UTF_8);
                writeHeaders(output, code, reason(code), "application/vnd.apple.mpegurl", body.length,
                        null, "bytes", null);
                output.write(body);
                output.flush();
                return;
            }

            long length = connection.getContentLengthLong();
            String contentRange = connection.getHeaderField("Content-Range");
            String acceptRanges = connection.getHeaderField("Accept-Ranges");
            String cacheControl = connection.getHeaderField("Cache-Control");
            writeHeaders(output, code, reason(code), contentType, length, contentRange, acceptRanges, cacheControl);

            if (!"HEAD".equals(request.method) && raw != null) {
                try (InputStream source = new BufferedInputStream(raw)) {
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = source.read(buffer)) >= 0) {
                        if (read == 0) continue;
                        output.write(buffer, 0, read);
                        output.flush();
                    }
                }
            }
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private String rewritePlaylist(String playlist, URL base) {
        StringBuilder rewritten = new StringBuilder();
        String[] lines = playlist.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        for (String rawLine : lines) {
            String line = rawLine;
            if (line.startsWith("#")) {
                Matcher matcher = URI_ATTRIBUTE.matcher(line);
                StringBuffer buffer = new StringBuffer();
                while (matcher.find()) {
                    String absolute = resolve(base, matcher.group(1));
                    String replacement = "URI=\"" + safeProxyUrl(absolute) + "\"";
                    matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
                }
                matcher.appendTail(buffer);
                line = buffer.toString();
            } else if (!line.trim().isEmpty()) {
                line = safeProxyUrl(resolve(base, line.trim()));
            }
            rewritten.append(line).append('\n');
        }
        return rewritten.toString();
    }

    private String safeProxyUrl(String absoluteUrl) {
        try {
            if (absoluteUrl.startsWith("data:") || absoluteUrl.startsWith("blob:")) return absoluteUrl;
            return getBaseUrl() + "/proxy?u=" + URLEncoder.encode(absoluteUrl, StandardCharsets.UTF_8.name());
        } catch (Exception ignored) {
            return absoluteUrl;
        }
    }

    private static String resolve(URL base, String reference) {
        try { return new URL(base, reference).toString(); }
        catch (Exception ignored) { return reference; }
    }

    private static HttpRequest readRequest(InputStream input) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.ISO_8859_1));
        String first = reader.readLine();
        if (first == null || first.trim().isEmpty()) return null;
        String[] parts = first.split(" ", 3);
        if (parts.length < 2) return null;

        Map<String, String> headers = new LinkedHashMap<>();
        String line;
        while ((line = reader.readLine()) != null && !line.isEmpty()) {
            int separator = line.indexOf(':');
            if (separator <= 0) continue;
            headers.put(line.substring(0, separator).trim().toLowerCase(Locale.US), line.substring(separator + 1).trim());
        }
        return new HttpRequest(parts[0].toUpperCase(Locale.US), parts[1], headers);
    }

    private static String queryParameter(String target, String key) {
        int question = target.indexOf('?');
        if (question < 0 || question + 1 >= target.length()) return null;
        String query = target.substring(question + 1);
        for (String pair : query.split("&")) {
            int equals = pair.indexOf('=');
            String name = equals >= 0 ? pair.substring(0, equals) : pair;
            if (!key.equals(name)) continue;
            String value = equals >= 0 ? pair.substring(equals + 1) : "";
            try { return URLDecoder.decode(value, StandardCharsets.UTF_8.name()); }
            catch (Exception ignored) { return value; }
        }
        return null;
    }

    private static void writeHeaders(OutputStream output, int code, String reason, String contentType,
                                     long contentLength, String contentRange, String acceptRanges,
                                     String cacheControl) throws IOException {
        StringBuilder headers = new StringBuilder();
        headers.append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n");
        headers.append("Connection: close\r\n");
        headers.append("Access-Control-Allow-Origin: *\r\n");
        headers.append("Access-Control-Allow-Headers: Range, Origin, Accept, Content-Type\r\n");
        headers.append("Access-Control-Expose-Headers: Content-Length, Content-Range, Accept-Ranges\r\n");
        if (contentType != null && !contentType.isEmpty()) headers.append("Content-Type: ").append(contentType).append("\r\n");
        if (contentLength >= 0) headers.append("Content-Length: ").append(contentLength).append("\r\n");
        if (contentRange != null && !contentRange.isEmpty()) headers.append("Content-Range: ").append(contentRange).append("\r\n");
        headers.append("Accept-Ranges: ").append(acceptRanges == null || acceptRanges.isEmpty() ? "bytes" : acceptRanges).append("\r\n");
        if (cacheControl != null && !cacheControl.isEmpty()) headers.append("Cache-Control: ").append(cacheControl).append("\r\n");
        else headers.append("Cache-Control: no-store\r\n");
        headers.append("\r\n");
        output.write(headers.toString().getBytes(StandardCharsets.ISO_8859_1));
        output.flush();
    }

    private static void writeSimple(OutputStream output, int code, String reason, String message) throws IOException {
        byte[] body = message.getBytes(StandardCharsets.UTF_8);
        writeHeaders(output, code, reason, "text/plain; charset=utf-8", body.length, null, "none", "no-store");
        output.write(body);
        output.flush();
    }

    private static byte[] readAll(InputStream input) throws IOException {
        try (InputStream source = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = source.read(buffer)) >= 0) {
                if (read > 0) output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static boolean isPlaylist(String contentType, String url) {
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.US);
        String value = url == null ? "" : url.toLowerCase(Locale.US);
        return value.contains(".m3u8") || type.contains("mpegurl") || type.contains("m3u8");
    }

    private static String safeContentType(String upstreamType, String url) {
        if (upstreamType != null && !upstreamType.trim().isEmpty()) return upstreamType;
        String value = url == null ? "" : url.toLowerCase(Locale.US);
        if (value.contains(".m3u8")) return "application/vnd.apple.mpegurl";
        if (value.contains(".mpd")) return "application/dash+xml";
        if (value.contains(".ts")) return "video/mp2t";
        if (value.contains(".mp4")) return "video/mp4";
        if (value.contains(".mkv")) return "video/x-matroska";
        return "application/octet-stream";
    }

    private static String reason(int code) {
        switch (code) {
            case 200: return "OK";
            case 206: return "Partial Content";
            case 301: return "Moved Permanently";
            case 302: return "Found";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 416: return "Range Not Satisfiable";
            case 429: return "Too Many Requests";
            case 500: return "Internal Server Error";
            case 502: return "Bad Gateway";
            case 503: return "Service Unavailable";
            default: return "Upstream Response";
        }
    }

    private static String findReachableLanAddress() throws IOException {
        List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
        List<InetAddress> preferred = new ArrayList<>();
        List<InetAddress> fallback = new ArrayList<>();

        for (NetworkInterface network : interfaces) {
            try {
                if (!network.isUp() || network.isLoopback()) continue;
            } catch (Exception ignored) { continue; }
            String name = network.getName() == null ? "" : network.getName().toLowerCase(Locale.US);
            if (name.startsWith("tun") || name.startsWith("ppp") || name.startsWith("rmnet") || name.startsWith("dummy")) continue;
            Enumeration<InetAddress> addresses = network.getInetAddresses();
            while (addresses.hasMoreElements()) {
                InetAddress address = addresses.nextElement();
                if (!(address instanceof Inet4Address) || address.isLoopbackAddress()) continue;
                if (name.startsWith("wlan") || name.contains("wifi") || name.startsWith("ap")) preferred.add(address);
                else if (address.isSiteLocalAddress()) fallback.add(address);
            }
        }

        InetAddress selected = !preferred.isEmpty() ? preferred.get(0) : (!fallback.isEmpty() ? fallback.get(0) : null);
        return selected == null ? null : selected.getHostAddress();
    }

    private static final class HttpRequest {
        final String method;
        final String target;
        final Map<String, String> headers;

        HttpRequest(String method, String target, Map<String, String> headers) {
            this.method = method;
            this.target = target;
            this.headers = headers;
        }
    }
}
