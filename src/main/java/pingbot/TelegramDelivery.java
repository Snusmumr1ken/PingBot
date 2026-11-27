package pingbot;

import com.pengrad.telegrambot.Callback;
import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.request.SendMessage;
import com.pengrad.telegrambot.response.SendResponse;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongConsumer;
import java.util.logging.Logger;

final class TelegramDelivery implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(TelegramDelivery.class.getName());
    private static final int MAX_ATTEMPTS = 3;
    private static final long MESSAGE_DELAY_MILLIS = 50;

    private final TelegramBot bot;
    private final LongConsumer onBlocked;
    private final long retryDelayMillis;
    private final long shutdownTimeoutMillis;
    private final Deque<Message> messages = new ArrayDeque<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            task -> new Thread(task, "telegram-delivery"));
    private boolean closing;
    private boolean closed;

    TelegramDelivery(TelegramBot bot, LongConsumer onBlocked) {
        this(bot, onBlocked, 1000, 5000);
    }

    TelegramDelivery(TelegramBot bot, long retryDelayMillis, long shutdownTimeoutMillis) {
        this(bot, chatId -> {}, retryDelayMillis, shutdownTimeoutMillis);
    }

    TelegramDelivery(TelegramBot bot, LongConsumer onBlocked, long retryDelayMillis, long shutdownTimeoutMillis) {
        this.bot = bot;
        this.onBlocked = onBlocked;
        this.retryDelayMillis = retryDelayMillis;
        this.shutdownTimeoutMillis = shutdownTimeoutMillis;
    }

    synchronized void send(long chatId, String text) {
        if (closing) {
            LOGGER.warning("Ignoring message to chat " + chatId + " during shutdown");
            return;
        }
        Message message = new Message(chatId, text);
        messages.addLast(message);
        if (messages.size() == 1) {
            schedule(message, 1, 0);
        }
    }

    private void schedule(Message message, int attempt, long delayMillis) {
        scheduler.schedule(() -> deliver(message, attempt), delayMillis, TimeUnit.MILLISECONDS);
    }

    private void deliver(Message message, int attempt) {
        synchronized (this) {
            if (closed) {
                return;
            }
        }
        try {
            bot.execute(new SendMessage(message.chatId(), message.text()), new Callback<SendMessage, SendResponse>() {
                @Override
                public void onResponse(SendMessage request, SendResponse response) {
                    if (response.isOk()) {
                        complete(message);
                        return;
                    }
                    int code = response.errorCode();
                    if (code == 403 && "Forbidden: bot was blocked by the user".equalsIgnoreCase(response.description())) {
                        blocked(message);
                        return;
                    }
                    Integer retryAfter = response.parameters() == null ? null : response.parameters().retryAfter();
                    long delay = retryAfter != null && retryAfter > 0
                            ? TimeUnit.SECONDS.toMillis(retryAfter) : retryDelayMillis * attempt;
                    failed(message, attempt, code == 429 || (code >= 500 && code < 600), delay,
                            code + ": " + response.description());
                }

                @Override
                public void onFailure(SendMessage request, IOException exception) {
                    failed(message, attempt, true, retryDelayMillis * attempt,
                            exception.getClass().getSimpleName() + ": " + exception.getMessage());
                }
            });
        } catch (RuntimeException e) {
            failed(message, attempt, false, 0, "Could not queue request: " + e.getClass().getSimpleName());
        }
    }

    private synchronized void blocked(Message message) {
        if (closed || messages.peekFirst() != message) {
            return;
        }
        try {
            onBlocked.accept(message.chatId());
        } catch (RuntimeException e) {
            LOGGER.warning("Could not remove blocked chat " + message.chatId() + ": " + e.getClass().getSimpleName());
        }
        messages.removeIf(queued -> queued != message && queued.chatId() == message.chatId());
        complete(message);
    }

    private synchronized void failed(Message message, int attempt, boolean retryable, long delayMillis, String detail) {
        if (closed || messages.peekFirst() != message) {
            return;
        }
        String reason = detail.replace(bot.getToken(), "[redacted]").replace('\n', ' ').replace('\r', ' ');
        String warning = "Telegram delivery to chat " + message.chatId() + " failed on attempt "
                + attempt + "/" + MAX_ATTEMPTS + ": " + reason;
        if (retryable && attempt < MAX_ATTEMPTS) {
            LOGGER.warning(warning + "; retrying in " + delayMillis + " ms");
            schedule(message, attempt + 1, delayMillis);
        } else {
            LOGGER.warning(warning + "; giving up");
            complete(message);
        }
    }

    private synchronized void complete(Message message) {
        if (closed || messages.peekFirst() != message) {
            return;
        }
        messages.removeFirst();
        notifyAll();
        if (!messages.isEmpty()) {
            schedule(messages.peekFirst(), 1, MESSAGE_DELAY_MILLIS);
        }
    }

    @Override
    public synchronized void close() {
        if (closing) {
            return;
        }
        closing = true;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(shutdownTimeoutMillis);
        try {
            while (!messages.isEmpty()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    break;
                }
                TimeUnit.NANOSECONDS.timedWait(this, remaining);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            closed = true;
            if (!messages.isEmpty()) {
                LOGGER.warning("Stopping Telegram delivery with " + messages.size() + " unfinished messages");
            }
            messages.clear();
            scheduler.shutdownNow();
        }
    }

    private record Message(long chatId, String text) {
    }
}
