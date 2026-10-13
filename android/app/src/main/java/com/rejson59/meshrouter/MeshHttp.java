package com.rejson59.meshrouter;

import android.net.Network;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Tiny, bounded HTTP/1.1 implementation for local mesh control traffic.
 * No external dependencies, no open proxy. Every API endpoint requires the
 * invite token; only GET / is public (an informational page, NOT a captive
 * portal). One request per socket, Connection: close.
 */
final class MeshHttp {
    private MeshHttp() {}

    interface Handler {
        Response handle(Request request, String remoteIp) throws Exception;
    }

    static final class Request {
        final String method;
        final String path;
        final Map<String, String> headers;
        final byte[] body;

        Request(String method, String path, Map<String, String> headers, byte[] body) {
            this.method = method;
            this.path = path;
            this.headers = headers;
            this.body = body;
        }

        JSONObject json() throws JSONException {
            return new JSONObject(new String(body, StandardCharsets.UTF_8));
        }

        String token() { return headers.getOrDefault("x-mesh-token", ""); }
    }

    static final class Response {
        final int code;
        final String contentType;
        final byte[] body;

        Response(int code, String contentType, byte[] body) {
            this.code = code;
            this.contentType = contentType;
            this.body = body;
        }

        static Response json(int code, JSONObject value) {
            return new Response(code, "application/json; charset=utf-8", value.toString().getBytes(StandardCharsets.UTF_8));
        }

        static Response text(int code, String value) {
            return new Response(code, "text/html; charset=utf-8", value.getBytes(StandardCharsets.UTF_8));
        }

        static Response bytes(byte[] value) {
            return new Response(200, "application/octet-stream", value);
        }
    }

    static final class Server {
        private final ServerSocket socket;
        private final ExecutorService workers = Executors.newFixedThreadPool(4);
        private final Handler handler;
        private final Thread acceptThread;
        private volatile boolean running = true;

        Server(int port, Handler handler) throws IOException {
            this.handler = handler;
            this.socket = new ServerSocket();
            this.socket.setReuseAddress(true);
            this.socket.bind(new InetSocketAddress("0.0.0.0", port), 8);
            acceptThread = new Thread(this::acceptLoop, "mesh-http-" + port);
            acceptThread.setDaemon(true);
            acceptThread.start();
        }

        private void acceptLoop() {
            while (running) {
                try {
                    Socket peer = socket.accept();
                    if (workers.isShutdown()) { peer.close(); break; }
                    workers.execute(() -> serve(peer));
                } catch (IOException | RuntimeException ignored) {
                    if (!running) break;
                }
            }
        }

        private void serve(Socket peer) {
            try (Socket s = peer) {
                s.setSoTimeout(5000);
                Request req = readRequest(s.getInputStream());
                Response response;
                try {
                    response = handler.handle(req, s.getInetAddress().getHostAddress());
                } catch (Exception e) {
                    response = Response.json(400, error("Nieprawidłowe żądanie"));
                }
                writeResponse(s.getOutputStream(), response);
            } catch (IOException ignored) {
                // A peer that closes early must not bring the server down.
            }
        }

        void stop() {
            running = false;
            try { socket.close(); } catch (IOException ignored) {}
            workers.shutdownNow();
            try { acceptThread.join(700); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    private static Request readRequest(InputStream input) throws IOException {
        String start = readLine(input, 2048);
        if (start == null) throw new IOException("Empty request");
        String[] parts = start.split(" ");
        if (parts.length != 3 || !(parts[0].equals("GET") || parts[0].equals("POST")) || !parts[2].startsWith("HTTP/1.")) {
            throw new IOException("Invalid request line");
        }
        Map<String, String> headers = new HashMap<>();
        for (int i = 0; i < 40; i++) {
            String line = readLine(input, 4096);
            if (line == null) throw new IOException("Truncated headers");
            if (line.isEmpty()) break;
            int colon = line.indexOf(':');
            if (colon < 1) throw new IOException("Bad header");
            headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
            if (i == 39) throw new IOException("Too many headers");
        }
        int length;
        try { length = Integer.parseInt(headers.getOrDefault("content-length", "0")); }
        catch (NumberFormatException e) { throw new IOException("Bad content length", e); }
        if (length < 0 || length > 16384) throw new IOException("Body too large");
        byte[] body = readExactly(input, length);
        return new Request(parts[0], parts[1], headers, body);
    }

    private static void writeResponse(OutputStream output, Response response) throws IOException {
        String status = response.code == 200 ? "OK" : response.code == 401 ? "Unauthorized" : response.code == 404 ? "Not Found" : response.code == 429 ? "Too Many Requests" : "Bad Request";
        String header = "HTTP/1.1 " + response.code + " " + status + "\r\n" +
            "Content-Type: " + response.contentType + "\r\n" +
            "Content-Length: " + response.body.length + "\r\n" +
            "Cache-Control: no-store\r\n" +
            "X-Content-Type-Options: nosniff\r\n" +
            "Connection: close\r\n\r\n";
        output.write(header.getBytes(StandardCharsets.US_ASCII));
        output.write(response.body);
        output.flush();
    }

    /** Client request over the Wi-Fi Network returned by WifiNetworkSpecifier. */
    static byte[] call(Network wifiNetwork, String host, int port, String method, String path,
                       JSONObject json, String token, int maxResponse) throws IOException {
        Socket raw = wifiNetwork != null ? wifiNetwork.getSocketFactory().createSocket() : new Socket();
        try (Socket s = raw) {
            s.connect(new InetSocketAddress(host, port), 4000);
            s.setSoTimeout(maxResponse > 32768 ? 22000 : 6000);
            byte[] body = json == null ? new byte[0] : json.toString().getBytes(StandardCharsets.UTF_8);
            if (body.length > 16384) throw new IOException("Request too large");
            String head = method + " " + path + " HTTP/1.1\r\n" +
                "Host: " + host + "\r\n" +
                "X-Mesh-Token: " + token + "\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: " + body.length + "\r\n" +
                "Connection: close\r\n\r\n";
            OutputStream out = s.getOutputStream();
            out.write(head.getBytes(StandardCharsets.US_ASCII));
            out.write(body);
            out.flush();
            InputStream in = s.getInputStream();
            String status = readLine(in, 2048);
            if (status == null || !status.startsWith("HTTP/1.")) throw new IOException("Invalid server response");
            String[] parts = status.split(" ");
            if (parts.length < 2 || !"200".equals(parts[1])) throw new IOException("Mesh server replied: " + status);
            int contentLength = -1;
            for (int i = 0; i < 40; i++) {
                String line = readLine(in, 4096);
                if (line == null) throw new IOException("Truncated response");
                if (line.isEmpty()) break;
                int colon = line.indexOf(':');
                if (colon > 0 && "content-length".equalsIgnoreCase(line.substring(0, colon).trim())) {
                    try { contentLength = Integer.parseInt(line.substring(colon + 1).trim()); }
                    catch (NumberFormatException e) { throw new IOException("Bad response length", e); }
                }
                if (i == 39) throw new IOException("Too many response headers");
            }
            if (contentLength < 0 || contentLength > maxResponse) throw new IOException("Unexpected response size");
            return readExactly(in, contentLength);
        }
    }

    static JSONObject callJson(Network network, String host, int port, String method, String path,
                               JSONObject payload, String token) throws IOException, JSONException {
        return new JSONObject(new String(call(network, host, port, method, path, payload, token, 32768), StandardCharsets.UTF_8));
    }

    private static String readLine(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        while (buf.size() < limit) {
            int b = in.read();
            if (b < 0) return buf.size() == 0 ? null : throwTruncated();
            if (b == '\n') {
                byte[] bytes = buf.toByteArray();
                int end = bytes.length > 0 && bytes[bytes.length - 1] == '\r' ? bytes.length - 1 : bytes.length;
                return new String(bytes, 0, end, StandardCharsets.US_ASCII);
            }
            buf.write(b);
        }
        throw new IOException("Header line too long");
    }

    private static String throwTruncated() throws IOException { throw new IOException("Truncated line"); }

    private static byte[] readExactly(InputStream in, int len) throws IOException {
        byte[] out = new byte[len];
        int pos = 0;
        while (pos < len) {
            int n = in.read(out, pos, len - pos);
            if (n < 0) throw new IOException("Truncated body");
            pos += n;
        }
        return out;
    }

    static JSONObject error(String message) {
        JSONObject obj = new JSONObject();
        try { obj.put("error", message); } catch (JSONException ignored) {}
        return obj;
    }
}
