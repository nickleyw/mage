package mage.webbridge;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Fans bridge events out to browser clients using Server-Sent Events. */
final class EventBroker {

    private static final int CLIENT_QUEUE_LIMIT = 256;
    private final CopyOnWriteArrayList<BlockingQueue<String>> clients = new CopyOnWriteArrayList<>();
    private final AtomicLong sequence = new AtomicLong();
    private final Gson gson;

    EventBroker(Gson gson) {
        this.gson = gson;
    }

    void publish(String type, Object payload) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("sequence", sequence.incrementAndGet());
        event.put("timestamp", Instant.now().toString());
        event.put("type", type);
        event.put("payload", payload);
        String encoded = "event: bridge\ndata: " + gson.toJson(event) + "\n\n";

        for (BlockingQueue<String> client : clients) {
            if (!client.offer(encoded)) {
                client.poll();
                client.offer(encoded);
            }
        }
    }

    void stream(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache, no-store");
        exchange.getResponseHeaders().set("Connection", "keep-alive");
        exchange.sendResponseHeaders(200, 0);

        BlockingQueue<String> queue = new LinkedBlockingQueue<>(CLIENT_QUEUE_LIMIT);
        clients.add(queue);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(": connected\n\n".getBytes(StandardCharsets.UTF_8));
            output.flush();
            while (true) {
                String event;
                try {
                    event = queue.poll(15, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (event == null) {
                    event = ": keepalive\n\n";
                }
                output.write(event.getBytes(StandardCharsets.UTF_8));
                output.flush();
            }
        } catch (IOException clientClosed) {
            // A browser closing or refreshing its event stream is expected.
        } finally {
            clients.remove(queue);
            exchange.close();
        }
    }
}
