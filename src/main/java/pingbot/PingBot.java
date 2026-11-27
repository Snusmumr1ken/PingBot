package pingbot;

import com.pengrad.telegrambot.Callback;
import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.TelegramException;
import com.pengrad.telegrambot.UpdatesListener;
import com.pengrad.telegrambot.model.Update;
import com.pengrad.telegrambot.request.GetUpdates;
import com.pengrad.telegrambot.request.SendMessage;
import com.pengrad.telegrambot.response.SendResponse;

import java.io.IOException;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

final class PingBot implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(PingBot.class.getName());

    private final TelegramBot bot;
    private final SubscriberStore subscribers;
    private final String host;

    PingBot(TelegramBot bot, SubscriberStore subscribers, String host) {
        this.bot = bot;
        this.subscribers = subscribers;
        this.host = host;
    }

    void start(BooleanSupplier availability) {
        bot.setUpdatesListener(updates -> {
            for (Update update : updates) {
                processUpdate(update, availability.getAsBoolean());
            }
            return UpdatesListener.CONFIRMED_UPDATES_ALL;
        }, exception -> LOGGER.warning(pollingFailure(exception)),
                new GetUpdates().timeout(30).allowedUpdates("message"));
    }

    void processUpdate(Update update, boolean available) {
        if (update.message() == null) {
            return;
        }
        long chatId = update.message().chat().id();
        try {
            String message = statusMessage(available);
            if (subscribers.subscribe(chatId)) {
                message = "✅ - Ok, you are successfully subscribed for notifications about availability of "
                        + host + "\n" + message;
            }
            send(chatId, message);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Could not save subscription for chat " + chatId, e);
            send(chatId, "Could not save your subscription. Please try again later.");
        }
    }

    void notifySubscribers(boolean available) {
        String message = statusMessage(available);
        for (long chatId : subscribers.snapshot()) {
            send(chatId, message);
        }
    }

    private String statusMessage(boolean available) {
        return available ? "🟢 - It's alive! - " + host : "🔴 - What is dead may never die...";
    }

    private String pollingFailure(TelegramException exception) {
        String detail;
        if (exception.response() != null) {
            detail = exception.response().errorCode() + ": " + exception.response().description();
        } else {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            detail = cause.getClass().getSimpleName() + ": " + cause.getMessage();
        }
        return ("Telegram polling failed; the client will retry: " + detail)
                .replace(bot.getToken(), "[redacted]").replace('\n', ' ').replace('\r', ' ');
    }

    private void send(long chatId, String message) {
        try {
            bot.execute(new SendMessage(chatId, message), new Callback<SendMessage, SendResponse>() {
                @Override
                public void onResponse(SendMessage request, SendResponse response) {
                    if (!response.isOk()) {
                        LOGGER.warning("Telegram rejected message to chat " + chatId + ": " + response.errorCode());
                    }
                }

                @Override
                public void onFailure(SendMessage request, IOException exception) {
                    LOGGER.warning("Telegram message delivery failed for chat " + chatId);
                }
            });
        } catch (RuntimeException e) {
            LOGGER.warning("Could not queue Telegram message for chat " + chatId);
        }
    }

    @Override
    public void close() {
        bot.removeGetUpdatesListener();
        bot.shutdown();
    }
}
