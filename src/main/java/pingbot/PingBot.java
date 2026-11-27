package pingbot;

import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.TelegramException;
import com.pengrad.telegrambot.UpdatesListener;
import com.pengrad.telegrambot.model.Update;
import com.pengrad.telegrambot.request.GetUpdates;

import java.io.IOException;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

final class PingBot implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(PingBot.class.getName());

    private final TelegramBot bot;
    private final TelegramDelivery delivery;
    private final SubscriberStore subscribers;
    private final String host;

    PingBot(TelegramBot bot, SubscriberStore subscribers, String host) {
        this.bot = bot;
        this.subscribers = subscribers;
        this.delivery = new TelegramDelivery(bot, this::removeBlockedSubscriber);
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
            delivery.send(chatId, message);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Could not save subscription for chat " + chatId, e);
            delivery.send(chatId, "Could not save your subscription. Please try again later.");
        }
    }

    void notifySubscribers(boolean available) {
        String message = statusMessage(available);
        for (long chatId : subscribers.snapshot()) {
            delivery.send(chatId, message);
        }
    }

    private String statusMessage(boolean available) {
        return available ? "🟢 - It's alive! - " + host : "🔴 - What is dead may never die...";
    }

    private void removeBlockedSubscriber(long chatId) {
        try {
            if (subscribers.unsubscribe(chatId)) {
                LOGGER.info("Removed blocked subscriber " + chatId);
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Could not persist removal of blocked subscriber " + chatId, e);
        }
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

    @Override
    public void close() {
        bot.removeGetUpdatesListener();
        delivery.close();
        bot.shutdown();
    }
}
