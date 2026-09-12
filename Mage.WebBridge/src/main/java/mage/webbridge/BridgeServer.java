package mage.webbridge;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;

/** Browser-facing HTTP and event-stream boundary for a single XMage session. */
public final class BridgeServer {

    private static final int DEFAULT_HTTP_PORT = 8080;
    private static final int MAX_REQUEST_BYTES = 64 * 1024;

    private final Gson gson = new Gson();
    private final EventBroker events = new EventBroker(gson);
    private final BridgeSession bridgeSession = new BridgeSession(events);
    private final DeckImportService deckImports = new DeckImportService(gson);
    private final String accessToken;
    private final HttpServer server;

    private BridgeServer(String listenHost, int listenPort, String accessToken) throws IOException {
        this.accessToken = accessToken == null ? "" : accessToken;
        this.server = HttpServer.create(new InetSocketAddress(listenHost, listenPort), 0);
        this.server.setExecutor(Executors.newCachedThreadPool());
        this.server.createContext("/api/health", this::handleHealth);
        this.server.createContext("/api/session/connect", this::handleConnect);
        this.server.createContext("/api/session", this::handleSession);
        this.server.createContext("/api/events", this::handleEvents);
        this.server.createContext("/api/decks/import-url", this::handleDeckImport);
        this.server.createContext("/api/decks/validate", this::handleDeckValidation);
        this.server.createContext("/api/tables/join", this::handleTableJoin);
        this.server.createContext("/api/tables/leave", this::handleTableLeave);
        this.server.createContext("/api/game", this::handleGame);
        this.server.createContext("/api/game/respond", this::handleGameResponse);
        this.server.createContext("/api/game/action", this::handleGameAction);
        this.server.createContext("/api/sideboard", this::handleSideboard);
        this.server.createContext("/api/sideboard/submit", this::handleSideboardSubmit);
        this.server.createContext("/", new StaticHandler());
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 2) {
            System.err.println("Usage: BridgeServer [listen-host] [http-port]");
            System.exit(2);
        }
        String listenHost = args.length >= 1 ? args[0] : "127.0.0.1";
        int listenPort = args.length == 2 ? Integer.parseInt(args[1]) : DEFAULT_HTTP_PORT;
        String token = System.getenv("XMAGE_BRIDGE_TOKEN");

        if (!isLoopback(listenHost) && (token == null || token.trim().isEmpty())) {
            System.err.println("XMAGE_BRIDGE_TOKEN is required when listening beyond localhost.");
            System.exit(2);
        }

        BridgeServer bridge = new BridgeServer(listenHost, listenPort, token);
        Runtime.getRuntime().addShutdownHook(new Thread(bridge::stop));
        bridge.server.start();
        System.out.println("XMage Web Bridge listening at http://" + listenHost + ':' + listenPort);
    }

    private static boolean isLoopback(String host) throws IOException {
        return InetAddress.getByName(host).isLoopbackAddress();
    }

    private void stop() {
        bridgeSession.disconnect();
        server.stop(1);
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        if (!method(exchange, "GET")) {
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "ok");
        body.put("authenticated", !accessToken.isEmpty());
        sendJson(exchange, 200, body);
    }

    private void handleConnect(HttpExchange exchange) throws IOException {
        if (!authorize(exchange) || !method(exchange, "POST")) {
            return;
        }
        try {
            ConnectRequest request = gson.fromJson(readBody(exchange), ConnectRequest.class);
            validate(request);
            sendJson(exchange, 200, bridgeSession.connect(
                    request.host.trim(), request.port, request.username.trim(), request.password));
        } catch (JsonParseException | IllegalArgumentException error) {
            sendError(exchange, 400, error.getMessage());
        } catch (IllegalStateException error) {
            sendError(exchange, 502, error.getMessage());
        }
    }

    private void handleSession(HttpExchange exchange) throws IOException {
        if (!authorize(exchange)) {
            return;
        }
        if ("GET".equals(exchange.getRequestMethod())) {
            sendJson(exchange, 200, bridgeSession.snapshot());
        } else if ("DELETE".equals(exchange.getRequestMethod())) {
            sendJson(exchange, 200, bridgeSession.disconnect());
        } else {
            methodNotAllowed(exchange, "GET, DELETE");
        }
    }

    private void handleEvents(HttpExchange exchange) throws IOException {
        if (!authorize(exchange) || !method(exchange, "GET")) {
            return;
        }
        events.stream(exchange);
    }

    private void handleDeckImport(HttpExchange exchange) throws IOException {
        if (!authorize(exchange) || !method(exchange, "POST")) {
            return;
        }
        try {
            DeckUrlRequest request = gson.fromJson(readBody(exchange), DeckUrlRequest.class);
            sendJson(exchange, 200, deckImports.importUrl(request == null ? null : request.url));
        } catch (JsonParseException | IllegalArgumentException error) {
            sendError(exchange, 400, error.getMessage());
        } catch (IOException error) {
            sendError(exchange, 502, error.getMessage());
        }
    }

    private void handleDeckValidation(HttpExchange exchange) throws IOException {
        if (!authorize(exchange) || !method(exchange, "POST")) {
            return;
        }
        try {
            DeckRequest request = gson.fromJson(readBody(exchange), DeckRequest.class);
            requireDeckRequest(request);
            sendJson(exchange, 200, bridgeSession.validateDeck(request.deckName, request.deckText));
        } catch (JsonParseException | IllegalArgumentException error) {
            sendError(exchange, 400, error.getMessage());
        } catch (RuntimeException error) {
            sendError(exchange, 500, error.getMessage());
        }
    }

    private void handleTableJoin(HttpExchange exchange) throws IOException {
        if (!authorize(exchange) || !method(exchange, "POST")) {
            return;
        }
        try {
            JoinTableRequest request = gson.fromJson(readBody(exchange), JoinTableRequest.class);
            requireDeckRequest(request);
            sendJson(exchange, 200, bridgeSession.joinTable(
                    request.tableId, request.deckName, request.deckText, request.password));
        } catch (JsonParseException | IllegalArgumentException error) {
            sendError(exchange, 400, error.getMessage());
        } catch (IllegalStateException error) {
            sendError(exchange, 502, error.getMessage());
        }
    }

    private void handleTableLeave(HttpExchange exchange) throws IOException {
        if (!authorize(exchange) || !method(exchange, "POST")) {
            return;
        }
        try {
            LeaveTableRequest request = gson.fromJson(readBody(exchange), LeaveTableRequest.class);
            sendJson(exchange, 200, bridgeSession.leaveTable(request == null ? null : request.tableId));
        } catch (JsonParseException | IllegalArgumentException error) {
            sendError(exchange, 400, error.getMessage());
        } catch (IllegalStateException error) {
            sendError(exchange, 502, error.getMessage());
        }
    }

    private void handleGame(HttpExchange exchange) throws IOException {
        if (!authorize(exchange) || !method(exchange, "GET")) {
            return;
        }
        sendJson(exchange, 200, bridgeSession.gameSnapshot());
    }

    private void handleGameResponse(HttpExchange exchange) throws IOException {
        if (!authorize(exchange) || !method(exchange, "POST")) {
            return;
        }
        try {
            GameResponseRequest request = gson.fromJson(readBody(exchange), GameResponseRequest.class);
            if (request == null) {
                throw new IllegalArgumentException("A JSON request body is required.");
            }
            sendJson(exchange, 200, bridgeSession.respondToGame(
                    request.messageId, request.action, request.value));
        } catch (JsonParseException | IllegalArgumentException error) {
            sendError(exchange, 400, error.getMessage());
        } catch (IllegalStateException error) {
            sendError(exchange, 409, error.getMessage());
        }
    }

    private void handleSideboard(HttpExchange exchange) throws IOException {
        if (!authorize(exchange) || !method(exchange, "GET")) {
            return;
        }
        Map<String, Object> state = bridgeSession.sideboardSnapshot();
        if (state == null) {
            state = new LinkedHashMap<>();
            state.put("active", false);
        }
        sendJson(exchange, 200, state);
    }

    private void handleGameAction(HttpExchange exchange) throws IOException {
        if (!authorize(exchange) || !method(exchange, "POST")) {
            return;
        }
        try {
            GameActionRequest request = gson.fromJson(readBody(exchange), GameActionRequest.class);
            if (request == null || request.action == null) {
                throw new IllegalArgumentException("A game action is required.");
            }
            sendJson(exchange, 200, bridgeSession.performGameAction(request.action));
        } catch (JsonParseException | IllegalArgumentException error) {
            sendError(exchange, 400, error.getMessage());
        } catch (IllegalStateException error) {
            sendError(exchange, 409, error.getMessage());
        }
    }

    private void handleSideboardSubmit(HttpExchange exchange) throws IOException {
        if (!authorize(exchange) || !method(exchange, "POST")) {
            return;
        }
        try {
            SideboardRequest request = gson.fromJson(readBody(exchange), SideboardRequest.class);
            if (request == null) {
                throw new IllegalArgumentException("A JSON request body is required.");
            }
            sendJson(exchange, 200, bridgeSession.submitSideboard(request.mainIds, request.sideboardIds));
        } catch (JsonParseException | IllegalArgumentException error) {
            sendError(exchange, 400, error.getMessage());
        } catch (IllegalStateException error) {
            sendError(exchange, 409, error.getMessage());
        }
    }

    private void requireDeckRequest(DeckRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("A JSON request body is required.");
        }
        if (request.deckText == null || request.deckText.trim().isEmpty()) {
            throw new IllegalArgumentException("Decklist text is required.");
        }
    }

    private void validate(ConnectRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("A JSON request body is required.");
        }
        if (request.host == null || request.host.trim().isEmpty()) {
            throw new IllegalArgumentException("XMage server host is required.");
        }
        if (request.username == null || request.username.trim().isEmpty()) {
            throw new IllegalArgumentException("XMage username is required.");
        }
        if (request.port < 1 || request.port > 65535) {
            throw new IllegalArgumentException("XMage server port must be between 1 and 65535.");
        }
    }

    private boolean authorize(HttpExchange exchange) throws IOException {
        if (accessToken.isEmpty()) {
            return true;
        }
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        String supplied = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring(7) : "";
        if (!MessageDigest.isEqual(accessToken.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8))) {
            sendError(exchange, 401, "A valid bridge access token is required.");
            return false;
        }
        return true;
    }

    private boolean method(HttpExchange exchange, String expected) throws IOException {
        if (expected.equals(exchange.getRequestMethod())) {
            return true;
        }
        methodNotAllowed(exchange, expected);
        return false;
    }

    private void methodNotAllowed(HttpExchange exchange, String allowed) throws IOException {
        exchange.getResponseHeaders().set("Allow", allowed);
        sendError(exchange, 405, "Method not allowed.");
    }

    private String readBody(HttpExchange exchange) throws IOException {
        try (InputStream input = exchange.getRequestBody();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > MAX_REQUEST_BYTES) {
                    throw new IllegalArgumentException("Request body is too large.");
                }
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private void sendError(HttpExchange exchange, int status, String message) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message == null || message.isEmpty() ? "Request failed." : message);
        sendJson(exchange, status, body);
    }

    private void sendJson(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] bytes = gson.toJson(value).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        } finally {
            exchange.close();
        }
    }

    private static final class ConnectRequest {
        private String host;
        private int port = 17171;
        private String username;
        private String password;
    }

    private static final class DeckUrlRequest {
        private String url;
    }

    private static class DeckRequest {
        String deckName;
        String deckText;
    }

    private static final class JoinTableRequest extends DeckRequest {
        private String tableId;
        private String password;
    }

    private static final class LeaveTableRequest {
        private String tableId;
    }

    private static final class GameResponseRequest {
        private long messageId;
        private String action;
        private String value;
    }

    private static final class SideboardRequest {
        private java.util.List<String> mainIds;
        private java.util.List<String> sideboardIds;
    }

    private static final class GameActionRequest {
        private String action;
    }

    private static final class StaticHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
                return;
            }
            String path = exchange.getRequestURI().getPath();
            String resource;
            String contentType;
            if ("/".equals(path) || "/index.html".equals(path)) {
                resource = "/web/index.html";
                contentType = "text/html; charset=utf-8";
            } else if ("/app.js".equals(path)) {
                resource = "/web/app.js";
                contentType = "text/javascript; charset=utf-8";
            } else if ("/styles.css".equals(path)) {
                resource = "/web/styles.css";
                contentType = "text/css; charset=utf-8";
            } else if ("/manifest.webmanifest".equals(path)) {
                resource = "/web/manifest.webmanifest";
                contentType = "application/manifest+json; charset=utf-8";
            } else if ("/service-worker.js".equals(path)) {
                resource = "/web/service-worker.js";
                contentType = "text/javascript; charset=utf-8";
            } else if ("/app-icon.svg".equals(path)) {
                resource = "/web/app-icon.svg";
                contentType = "image/svg+xml";
            } else if ("/apple-touch-icon.png".equals(path)) {
                resource = "/web/app-icon-180.png.base64";
                contentType = "image/png";
            } else {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }

            byte[] bytes;
            try (InputStream input = BridgeServer.class.getResourceAsStream(resource)) {
                if (input == null) {
                    exchange.sendResponseHeaders(404, -1);
                    exchange.close();
                    return;
                }
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
                bytes = output.toByteArray();
            }
            if ("/apple-touch-icon.png".equals(path)) {
                bytes = Base64.getMimeDecoder().decode(bytes);
            }
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream response = exchange.getResponseBody()) {
                response.write(bytes);
            } finally {
                exchange.close();
            }
        }
    }
}
