package pingbot;

import com.pengrad.telegrambot.BotUtils;
import com.pengrad.telegrambot.TelegramBot;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class TelegramDeliveryHttpTest {
    @TempDir
    Path directory;

    @Test
    void pollsWithTimeoutAndSendsOneCombinedReplyThroughTheRealClient() throws Exception {
        CountDownLatch releasePolling = new CountDownLatch(1);
        AtomicInteger polls = new AtomicInteger();
        try (TestServer server = new TestServer(request -> {
            if (request.method().equals("getUpdates")) {
                if (polls.incrementAndGet() == 1) {
                    return new Response(200, """
                            {"ok":true,"result":[{"update_id":1,"message":{"message_id":1,
                            "chat":{"id":123,"type":"private"},"text":"hello"}}]}
                            """);
                }
                releasePolling.await(5, TimeUnit.SECONDS);
                return new Response(200, "{\"ok\":true,\"result\":[]}");
            }
            return success();
        })) {
            SubscriberStore subscribers = new SubscriberStore(directory.resolve("ids.txt"));
            try (PingBot bot = new PingBot(server.bot, subscribers, "example.com")) {
                bot.start(() -> true);
                Request poll = server.nextRequest();
                assertEquals("getUpdates", poll.method());
                assertEquals("30", poll.parameters().get("timeout"));
                assertEquals("[\"message\"]", poll.parameters().get("allowed_updates"));
                Request reply;
                do {
                    reply = server.nextRequest();
                } while (!reply.method().equals("sendMessage"));
                assertEquals("123", reply.parameters().get("chat_id"));
                assertTrue(reply.parameters().get("text").contains("successfully subscribed"));
                assertTrue(reply.parameters().get("text").contains("🟢 - It's alive! - example.com"));
                assertEquals(List.of(123L), subscribers.snapshot());
            } finally {
                releasePolling.countDown();
            }
            assertTrue(server.requests.stream().noneMatch(request -> request.method().equals("sendMessage")));
        }
    }

    @Test
    void respectsRetryAfterAndPreservesMessageOrder() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (TestServer server = new TestServer(request -> calls.incrementAndGet() == 1
                ? new Response(429, "{\"ok\":false,\"error_code\":429,\"description\":\"Too Many Requests\","
                        + "\"parameters\":{\"retry_after\":1}}") : success())) {
            try (TelegramDelivery delivery = new TelegramDelivery(server.bot, 10, 3000)) {
                delivery.send(123L, "outage");
                delivery.send(123L, "recovered");
            }
            Request first = server.nextRequest();
            Request retry = server.nextRequest();
            Request recovery = server.nextRequest();
            assertEquals("outage", first.parameters().get("text"));
            assertEquals("outage", retry.parameters().get("text"));
            assertEquals("recovered", recovery.parameters().get("text"));
            assertTrue(retry.receivedNanos() - first.receivedNanos() >= TimeUnit.SECONDS.toNanos(1));
            assertTrue(server.requests.isEmpty());
        }
    }

    @Test
    void stopsAfterThreeServerFailuresAndContinuesToTheNextMessage() throws Exception {
        try (TestServer server = new TestServer(request -> request.parameters().get("text").equals("outage")
                ? new Response(503, "{\"ok\":false,\"error_code\":503,\"description\":\"Unavailable\"}") : success())) {
            try (TelegramDelivery delivery = new TelegramDelivery(server.bot, 10, 2000)) {
                delivery.send(123L, "outage");
                delivery.send(456L, "next chat");
            }
            List<String> messages = new ArrayList<>();
            for (int index = 0; index < 4; index++) {
                messages.add(server.nextRequest().parameters().get("text"));
            }
            assertEquals(List.of("outage", "outage", "outage", "next chat"), messages);
            assertTrue(server.requests.isEmpty());
        }
    }

    @Test
    void retriesTransportResponseFailures() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (TestServer server = new TestServer(request -> calls.incrementAndGet() == 1
                ? new Response(502, "upstream returned invalid JSON") : success())) {
            try (TelegramDelivery delivery = new TelegramDelivery(server.bot, 10, 1000)) {
                delivery.send(123L, "status");
            }
            assertEquals("status", server.nextRequest().parameters().get("text"));
            assertEquals("status", server.nextRequest().parameters().get("text"));
            assertTrue(server.requests.isEmpty());
        }
    }

    @Test
    void permanentFailuresAreNotRetriedAndDiagnosticsRedactTokens() throws Exception {
        List<String> warnings = new ArrayList<>();
        Logger logger = Logger.getLogger(TelegramDelivery.class.getName());
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                warnings.add(record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(capture);
        try (TestServer server = new TestServer(request -> new Response(403,
                "{\"ok\":false,\"error_code\":403,\"description\":\"Forbidden test-token\\nblocked\"}"))) {
            try (TelegramDelivery delivery = new TelegramDelivery(server.bot, 10, 1000)) {
                delivery.send(123L, "status");
            }
            assertNotNull(server.nextRequest());
            assertTrue(server.requests.isEmpty());
            assertEquals(1, warnings.size());
            assertTrue(warnings.get(0).contains("403: Forbidden [redacted] blocked"));
            assertFalse(warnings.get(0).contains("test-token"));
        } finally {
            logger.removeHandler(capture);
        }
    }

    @Test
    void blockedUsersAreRemovedFromDiskAndQueuedBroadcastsAndCanSubscribeAgain() throws Exception {
        CountDownLatch releaseBlockedResponse = new CountDownLatch(1);
        AtomicBoolean blocked = new AtomicBoolean(true);
        Path file = directory.resolve("ids.txt");
        SubscriberStore subscribers = new SubscriberStore(file);
        subscribers.subscribe(123L);
        subscribers.subscribe(456L);
        try (TestServer server = new TestServer(request -> {
            if (request.parameters().get("chat_id").equals("123") && blocked.get()) {
                releaseBlockedResponse.await(5, TimeUnit.SECONDS);
                return new Response(403,
                        "{\"ok\":false,\"error_code\":403,\"description\":\"Forbidden: Bot was blocked by the user\"}");
            }
            return success();
        }); PingBot bot = new PingBot(server.bot, subscribers, "example.com")) {
            bot.notifySubscribers(false);
            assertEquals("123", server.nextRequest().parameters().get("chat_id"));
            bot.notifySubscribers(true);
            releaseBlockedResponse.countDown();

            Request outage = server.nextRequest();
            Request recovery = server.nextRequest();
            assertEquals("456", outage.parameters().get("chat_id"));
            assertTrue(outage.parameters().get("text").contains("🔴"));
            assertEquals("456", recovery.parameters().get("chat_id"));
            assertTrue(recovery.parameters().get("text").contains("🟢"));
            assertEquals(List.of(456L), subscribers.snapshot());
            assertEquals(List.of(456L), new SubscriberStore(file).snapshot());

            bot.notifySubscribers(false);
            assertEquals("456", server.nextRequest().parameters().get("chat_id"));
            blocked.set(false);
            bot.processUpdate(BotUtils.parseUpdate("""
                    {"update_id":2,"message":{"message_id":2,"chat":{"id":123,"type":"private"},"text":"hello"}}
                    """), true);
            Request welcome = server.nextRequest();
            assertEquals("123", welcome.parameters().get("chat_id"));
            assertTrue(welcome.parameters().get("text").contains("successfully subscribed"));
            assertEquals(List.of(456L, 123L), new SubscriberStore(file).snapshot());
        } finally {
            releaseBlockedResponse.countDown();
        }
    }

    @Test
    void unrelatedForbiddenErrorsAndOtherCodesPreserveSubscribers() throws Exception {
        List<Response> failures = List.of(
                new Response(403, "{\"ok\":false,\"error_code\":403,\"description\":\"Forbidden: not enough rights\"}"),
                new Response(403, "{\"ok\":false,\"error_code\":403}"),
                new Response(400, "{\"ok\":false,\"error_code\":400,\"description\":\"Forbidden: bot was blocked by the user\"}"));
        for (int index = 0; index < failures.size(); index++) {
            Response failure = failures.get(index);
            Path file = directory.resolve("ids-" + index + ".txt");
            SubscriberStore subscribers = new SubscriberStore(file);
            subscribers.subscribe(123L);
            subscribers.subscribe(456L);
            try (TestServer server = new TestServer(request -> request.parameters().get("chat_id").equals("123")
                    ? failure : success()); PingBot bot = new PingBot(server.bot, subscribers, "example.com")) {
                bot.notifySubscribers(false);
                assertEquals("123", server.nextRequest().parameters().get("chat_id"));
                assertEquals("456", server.nextRequest().parameters().get("chat_id"));
                assertEquals(List.of(123L, 456L), subscribers.snapshot());
                assertEquals(subscribers.snapshot(), new SubscriberStore(file).snapshot());
            }
        }
    }

    @Test
    void failedRemovalPreservesSubscribersAndContinuesDeliveryToOtherChats() throws Exception {
        Path file = directory.resolve("ids.txt");
        SubscriberStore subscribers = new SubscriberStore(file);
        subscribers.subscribe(123L);
        subscribers.subscribe(456L);
        Path backup = directory.resolve("backup.txt");
        Files.move(file, backup);
        Files.createDirectory(file);
        try (TestServer server = new TestServer(request -> request.parameters().get("chat_id").equals("123")
                ? new Response(403, "{\"ok\":false,\"error_code\":403,\"description\":\"Forbidden: bot was blocked by the user\"}")
                : success()); PingBot bot = new PingBot(server.bot, subscribers, "example.com")) {
            bot.notifySubscribers(false);
            assertEquals("123", server.nextRequest().parameters().get("chat_id"));
            assertEquals("456", server.nextRequest().parameters().get("chat_id"));
            assertEquals(List.of(123L, 456L), subscribers.snapshot());
            assertEquals("123\n456\n", Files.readString(backup));
            assertTrue(Files.isDirectory(file));
        }
    }

    @Test
    void shutdownWaitsForAnInFlightMessage() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService closer = Executors.newSingleThreadExecutor();
        try (TestServer server = new TestServer(request -> {
            release.await(5, TimeUnit.SECONDS);
            return success();
        }); TelegramDelivery delivery = new TelegramDelivery(server.bot, 10, 2000)) {
            delivery.send(123L, "status");
            server.nextRequest();
            var closing = closer.submit(delivery::close);
            assertThrows(TimeoutException.class, () -> closing.get(100, TimeUnit.MILLISECONDS));
            release.countDown();
            closing.get(2, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            closer.shutdownNow();
        }
    }

    @Test
    void shutdownIsBoundedAndCancelsDelayedRetriesAndQueuedMessages() throws Exception {
        try (TestServer server = new TestServer(request -> new Response(429,
                "{\"ok\":false,\"error_code\":429,\"parameters\":{\"retry_after\":60}}"))) {
            TelegramDelivery delivery = new TelegramDelivery(server.bot, 10, 100);
            try {
                delivery.send(123L, "outage");
                delivery.send(456L, "queued");
                server.nextRequest();
                long started = System.nanoTime();
                delivery.close();
                assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(1));
                delivery.send(789L, "after shutdown");
                assertNull(server.requests.poll(200, TimeUnit.MILLISECONDS));
            } finally {
                delivery.close();
            }
        }
    }

    private static Response success() {
        return new Response(200, "{\"ok\":true,\"result\":{\"message_id\":1,\"chat\":{\"id\":123,\"type\":\"private\"}}}");
    }

    private record Request(String method, Map<String, String> parameters, long receivedNanos) {
    }

    private record Response(int status, String body) {
    }

    @FunctionalInterface
    private interface Responder {
        Response respond(Request request) throws InterruptedException;
    }

    private static final class TestServer implements AutoCloseable {
        private final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        private final ExecutorService workers = Executors.newCachedThreadPool();
        private final OkHttpClient client = new OkHttpClient.Builder().callTimeout(3, TimeUnit.SECONDS).build();
        private final BlockingQueue<Request> requests = new LinkedBlockingQueue<>();
        private final TelegramBot bot;

        TestServer(Responder responder) throws IOException {
            server.createContext("/", exchange -> handle(exchange, responder));
            server.setExecutor(workers);
            server.start();
            bot = new TelegramBot.Builder("test-token")
                    .apiUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/bot")
                    .okHttpClient(client).build();
        }

        Request nextRequest() throws InterruptedException {
            Request request = requests.poll(5, TimeUnit.SECONDS);
            assertNotNull(request, "Timed out waiting for an HTTP request");
            return request;
        }

        private void handle(HttpExchange exchange, Responder responder) throws IOException {
            try (exchange) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                Map<String, String> parameters = new LinkedHashMap<>();
                if (!body.isEmpty()) {
                    for (String pair : body.split("&")) {
                        String[] entry = pair.split("=", 2);
                        parameters.put(URLDecoder.decode(entry[0], StandardCharsets.UTF_8),
                                URLDecoder.decode(entry[1], StandardCharsets.UTF_8));
                    }
                }
                String path = exchange.getRequestURI().getPath();
                Request request = new Request(path.substring(path.lastIndexOf('/') + 1), parameters, System.nanoTime());
                requests.add(request);
                Response response = responder.respond(request);
                byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(response.status(), bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void close() {
            bot.removeGetUpdatesListener();
            client.dispatcher().cancelAll();
            bot.shutdown();
            client.dispatcher().executorService().shutdownNow();
            client.connectionPool().evictAll();
            server.stop(0);
            workers.shutdownNow();
        }
    }
}
