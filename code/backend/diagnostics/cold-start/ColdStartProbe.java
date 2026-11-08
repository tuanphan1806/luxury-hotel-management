import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;

/** Isolated Render control: no Spring, JDBC, provider SDKs or application data. */
public final class ColdStartProbe {
    private static final String BOOT_ID = UUID.randomUUID().toString();
    private static final Instant STARTED_AT = Instant.now();
    private static final long STARTED_NANOS = System.nanoTime();

    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "10000"));
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid PORT");
        var executor = Executors.newFixedThreadPool(2);
        var server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        server.setExecutor(executor);
        server.createContext("/", ColdStartProbe::respond);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("cold_start_control_stopping bootId=" + BOOT_ID);
            server.stop(0);
            executor.shutdownNow();
        }));
        server.start();
        System.out.println("cold_start_control_listening bootId=" + BOOT_ID
                + " startedAt=" + STARTED_AT + " port=" + port
                + " processors=" + Runtime.getRuntime().availableProcessors()
                + " maxHeapBytes=" + Runtime.getRuntime().maxMemory());
    }

    private static void respond(HttpExchange request) throws IOException {
        try (request) {
            String method = request.getRequestMethod();
            String path = request.getRequestURI().getPath();
            int status;
            String body;
            if (!method.equals("GET") && !method.equals("HEAD")) {
                status = 405;
                body = "{\"error\":\"method_not_allowed\"}";
                request.getResponseHeaders().set("Allow", "GET, HEAD");
            } else if (path.equals("/") || path.equals("/actuator/health/readiness")) {
                status = 200;
                body = "{\"status\":\"UP\",\"mode\":\"cold-start-control\",\"bootId\":\""
                        + BOOT_ID + "\",\"startedAt\":\"" + STARTED_AT
                        + "\",\"uptimeMs\":" + (System.nanoTime() - STARTED_NANOS) / 1_000_000 + "}";
            } else {
                status = 404;
                body = "{\"error\":\"not_found\"}";
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            request.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            request.getResponseHeaders().set("Cache-Control", "no-store");
            request.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            request.sendResponseHeaders(status, method.equals("HEAD") ? -1 : bytes.length);
            if (!method.equals("HEAD")) request.getResponseBody().write(bytes);
        }
    }
}
