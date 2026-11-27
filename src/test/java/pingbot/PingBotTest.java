package pingbot;

import com.pengrad.telegrambot.BotUtils;
import com.pengrad.telegrambot.Callback;
import com.pengrad.telegrambot.ExceptionHandler;
import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.TelegramException;
import com.pengrad.telegrambot.UpdatesListener;
import com.pengrad.telegrambot.model.Update;
import com.pengrad.telegrambot.request.BaseRequest;
import com.pengrad.telegrambot.request.GetUpdates;
import com.pengrad.telegrambot.response.BaseResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class PingBotTest {
    @TempDir
    Path directory;

    @Test
    void subscribesOnceAndRepliesWithCurrentStatusOnEveryMessage() throws Exception {
        SubscriberStore store = new SubscriberStore(directory.resolve("chat_ids.txt"));
        RecordingTelegramBot telegram = new RecordingTelegramBot();
        try (PingBot bot = new PingBot(telegram, store, "example.com")) {
            bot.processUpdate(message(123L), true);
            bot.processUpdate(message(123L), false);
            telegram.awaitMessages(2);

            assertEquals(List.of(123L), store.snapshot());
            assertEquals(2, telegram.messages.size());
            assertTrue(text(telegram, 0).contains("successfully subscribed"));
            assertTrue(text(telegram, 0).contains("🟢 - It's alive! - example.com"));
            assertEquals("🔴 - What is dead may never die...", text(telegram, 1));
            assertTrue(telegram.messages.stream().allMatch(request -> request.get("chat_id").equals(123L)));
        }
        assertTrue(telegram.listenerRemoved);
        assertTrue(telegram.closed);
    }

    @Test
    void ignoresUpdatesWithoutMessagesAndAcknowledgesTheBatch() throws Exception {
        SubscriberStore store = new SubscriberStore(directory.resolve("chat_ids.txt"));
        RecordingTelegramBot telegram = new RecordingTelegramBot();
        try (PingBot bot = new PingBot(telegram, store, "example.com")) {
            bot.start(() -> true);
            int confirmation = telegram.listener.process(List.of(
                    BotUtils.parseUpdate("{\"update_id\":1}"), message(123L)));
            telegram.awaitMessages(1);

            assertEquals(UpdatesListener.CONFIRMED_UPDATES_ALL, confirmation);
            assertEquals(List.of(123L), store.snapshot());
            assertEquals(1, telegram.messages.size());
            assertTrue(text(telegram, 0).contains("It's alive!"));
            assertEquals(30, telegram.pollingRequest.getTimeoutSeconds());
            assertArrayEquals(new String[]{"message"},
                    (String[]) telegram.pollingRequest.getParameters().get("allowed_updates"));
        }
    }

    @Test
    void pollingDiagnosticsExplainApiAndNetworkFailuresWithoutExposingTokens() throws IOException {
        List<String> warnings = new ArrayList<>();
        Logger logger = Logger.getLogger(PingBot.class.getName());
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
        RecordingTelegramBot telegram = new RecordingTelegramBot();
        try (PingBot bot = new PingBot(telegram,
                new SubscriberStore(directory.resolve("chat_ids.txt")), "example.com")) {
            bot.start(() -> true);
            telegram.exceptionHandler.onException(new TelegramException("API failure", BotUtils.fromJson(
                    "{\"ok\":false,\"error_code\":409,\"description\":\"Webhook conflict\"}", BaseResponse.class)));
            telegram.exceptionHandler.onException(new TelegramException(
                    new IOException("Connection failed at https://api.telegram.org/bottest-token/getUpdates\nretry")));

            assertTrue(warnings.get(0).contains("409: Webhook conflict"));
            assertTrue(warnings.get(1).contains("IOException: Connection failed"));
            assertTrue(warnings.get(1).contains("[redacted]"));
            assertTrue(warnings.stream().noneMatch(warning -> warning.contains("test-token") || warning.contains("\n")));
        } finally {
            logger.removeHandler(capture);
        }
    }

    @Test
    void reportsPersistenceFailureWithoutClaimingSubscriptionSucceeded() throws Exception {
        Path file = directory.resolve("chat_ids.txt");
        SubscriberStore store = new SubscriberStore(file);
        Files.delete(file);
        Files.createDirectory(file);
        RecordingTelegramBot telegram = new RecordingTelegramBot();
        try (PingBot bot = new PingBot(telegram, store, "example.com")) {
            bot.processUpdate(message(123L), true);
            telegram.awaitMessages(1);

            assertTrue(store.snapshot().isEmpty());
            assertEquals(1, telegram.messages.size());
            assertTrue(text(telegram, 0).contains("Could not save your subscription"));
        }
    }

    @Test
    void broadcastsToAllSubscribersDespiteRejectedOrFailedDeliveries() throws Exception {
        SubscriberStore store = new SubscriberStore(directory.resolve("chat_ids.txt"));
        store.subscribe(1L);
        store.subscribe(2L);
        store.subscribe(3L);
        RecordingTelegramBot telegram = new RecordingTelegramBot();
        try (PingBot bot = new PingBot(telegram, store, "example.com")) {
            bot.notifySubscribers(false);
            telegram.awaitMessages(5);

            assertEquals(List.of(1L, 2L, 2L, 2L, 3L),
                    telegram.messages.stream().map(request -> request.get("chat_id")).toList());
            assertTrue(telegram.messages.stream().allMatch(request -> request.get("text").toString().contains("🔴")));
            assertEquals(List.of(1L, 2L, 3L), store.snapshot());
        }
    }

    @Test
    void continuesBroadcastWhenARequestCannotBeQueued() throws Exception {
        SubscriberStore store = new SubscriberStore(directory.resolve("chat_ids.txt"));
        store.subscribe(4L);
        store.subscribe(5L);
        RecordingTelegramBot telegram = new RecordingTelegramBot();
        try (PingBot bot = new PingBot(telegram, store, "example.com")) {
            assertDoesNotThrow(() -> bot.notifySubscribers(true));
            telegram.awaitMessages(2);
            assertEquals(2, telegram.messages.size());
        }
    }

    private Update message(long chatId) {
        return BotUtils.parseUpdate("""
                {"update_id":1,"message":{"message_id":1,"chat":{"id":%d,"type":"private"},"text":"hello"}}
                """.formatted(chatId));
    }

    private String text(RecordingTelegramBot telegram, int index) {
        return telegram.messages.get(index).get("text").toString();
    }

    private static final class RecordingTelegramBot extends TelegramBot {
        private final List<Map<String, Object>> messages = new CopyOnWriteArrayList<>();
        private UpdatesListener listener;
        private ExceptionHandler exceptionHandler;
        private GetUpdates pollingRequest;
        private boolean listenerRemoved;
        private boolean closed;

        RecordingTelegramBot() {
            super("test-token");
        }

        synchronized void awaitMessages(int count) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (messages.size() < count) {
                long remaining = deadline - System.nanoTime();
                assertTrue(remaining > 0, "Timed out waiting for Telegram requests");
                TimeUnit.NANOSECONDS.timedWait(this, remaining);
            }
        }

        @Override
        public void setUpdatesListener(UpdatesListener listener, ExceptionHandler exceptionHandler, GetUpdates request) {
            this.listener = listener;
            this.exceptionHandler = exceptionHandler;
            this.pollingRequest = request;
        }

        @Override
        public synchronized <T extends BaseRequest<T, R>, R extends BaseResponse> void execute(T request, Callback<T, R> callback) {
            messages.add(Map.copyOf(request.getParameters()));
            notifyAll();
            long chatId = (Long) request.getParameters().get("chat_id");
            if (chatId == 4L) {
                throw new IllegalStateException("test queue failure");
            }
            if (chatId == 2L) {
                callback.onFailure(request, new IOException("test delivery failure"));
            } else {
                String response = chatId == 1L
                        ? "{\"ok\":false,\"error_code\":403,\"description\":\"Forbidden\"}"
                        : "{\"ok\":true,\"result\":{\"message_id\":1}}";
                callback.onResponse(request, BotUtils.fromJson(response, request.getResponseType()));
            }
        }

        @Override
        public void removeGetUpdatesListener() {
            listenerRemoved = true;
            super.removeGetUpdatesListener();
        }

        @Override
        public void shutdown() {
            closed = true;
            super.shutdown();
        }
    }
}
