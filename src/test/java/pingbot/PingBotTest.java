package pingbot;

import com.pengrad.telegrambot.BotUtils;
import com.pengrad.telegrambot.Callback;
import com.pengrad.telegrambot.ExceptionHandler;
import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.UpdatesListener;
import com.pengrad.telegrambot.model.Update;
import com.pengrad.telegrambot.request.BaseRequest;
import com.pengrad.telegrambot.response.BaseResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PingBotTest {
    @TempDir
    Path directory;

    @Test
    void subscribesOnceAndRepliesWithCurrentStatusOnEveryMessage() throws IOException {
        SubscriberStore store = new SubscriberStore(directory.resolve("chat_ids.txt"));
        RecordingTelegramBot telegram = new RecordingTelegramBot();
        try (PingBot bot = new PingBot(telegram, store, "example.com")) {
            bot.processUpdate(message(123L), true);
            bot.processUpdate(message(123L), false);

            assertEquals(List.of(123L), store.snapshot());
            assertEquals(3, telegram.messages.size());
            assertTrue(text(telegram, 0).contains("successfully subscribed"));
            assertEquals("🟢 - It's alive! - example.com", text(telegram, 1));
            assertEquals("🔴 - What is dead may never die...", text(telegram, 2));
            assertTrue(telegram.messages.stream().allMatch(request -> request.get("chat_id").equals(123L)));
        }
        assertTrue(telegram.listenerRemoved);
        assertTrue(telegram.closed);
    }

    @Test
    void ignoresUpdatesWithoutMessagesAndAcknowledgesTheBatch() throws IOException {
        SubscriberStore store = new SubscriberStore(directory.resolve("chat_ids.txt"));
        RecordingTelegramBot telegram = new RecordingTelegramBot();
        try (PingBot bot = new PingBot(telegram, store, "example.com")) {
            bot.start(() -> true);
            int confirmation = telegram.listener.process(List.of(
                    BotUtils.parseUpdate("{\"update_id\":1}"), message(123L)));

            assertEquals(UpdatesListener.CONFIRMED_UPDATES_ALL, confirmation);
            assertEquals(List.of(123L), store.snapshot());
            assertEquals(2, telegram.messages.size());
            assertTrue(text(telegram, 1).contains("It's alive!"));
        }
    }

    @Test
    void reportsPersistenceFailureWithoutClaimingSubscriptionSucceeded() throws IOException {
        Path file = directory.resolve("chat_ids.txt");
        SubscriberStore store = new SubscriberStore(file);
        Files.delete(file);
        Files.createDirectory(file);
        RecordingTelegramBot telegram = new RecordingTelegramBot();
        try (PingBot bot = new PingBot(telegram, store, "example.com")) {
            bot.processUpdate(message(123L), true);

            assertTrue(store.snapshot().isEmpty());
            assertEquals(1, telegram.messages.size());
            assertTrue(text(telegram, 0).contains("Could not save your subscription"));
        }
    }

    @Test
    void broadcastsToAllSubscribersDespiteRejectedOrFailedDeliveries() throws IOException {
        SubscriberStore store = new SubscriberStore(directory.resolve("chat_ids.txt"));
        store.subscribe(1L);
        store.subscribe(2L);
        store.subscribe(3L);
        RecordingTelegramBot telegram = new RecordingTelegramBot();
        try (PingBot bot = new PingBot(telegram, store, "example.com")) {
            bot.notifySubscribers(false);

            assertEquals(List.of(1L, 2L, 3L),
                    telegram.messages.stream().map(request -> request.get("chat_id")).toList());
            assertTrue(telegram.messages.stream().allMatch(request -> request.get("text").toString().contains("🔴")));
        }
    }

    @Test
    void continuesBroadcastWhenARequestCannotBeQueued() throws IOException {
        SubscriberStore store = new SubscriberStore(directory.resolve("chat_ids.txt"));
        store.subscribe(4L);
        store.subscribe(5L);
        RecordingTelegramBot telegram = new RecordingTelegramBot();
        try (PingBot bot = new PingBot(telegram, store, "example.com")) {
            assertDoesNotThrow(() -> bot.notifySubscribers(true));
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
        private final List<Map<String, Object>> messages = new ArrayList<>();
        private UpdatesListener listener;
        private boolean listenerRemoved;
        private boolean closed;

        RecordingTelegramBot() {
            super("test-token");
        }

        @Override
        public void setUpdatesListener(UpdatesListener listener, ExceptionHandler exceptionHandler) {
            this.listener = listener;
        }

        @Override
        public <T extends BaseRequest<T, R>, R extends BaseResponse> void execute(T request, Callback<T, R> callback) {
            messages.add(Map.copyOf(request.getParameters()));
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
