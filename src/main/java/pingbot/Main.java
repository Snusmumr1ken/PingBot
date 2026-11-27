package pingbot;

import com.pengrad.telegrambot.TelegramBot;

import java.io.IOException;
import java.util.logging.Logger;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws IOException {
        Configuration configuration = Configuration.fromEnvironment(System.getenv());
        SubscriberStore subscribers = new SubscriberStore(configuration.chatIdsFile());
        PingBot bot = new PingBot(new TelegramBot(configuration.botToken()), subscribers, configuration.host());
        AvailabilityMonitor monitor = new AvailabilityMonitor(
                new TcpAvailabilityChecker(configuration.host(), configuration.port(), configuration.connectTimeoutMillis()),
                bot::notifySubscribers, configuration.checkIntervalMillis());

        Runnable shutdown = () -> {
            monitor.close();
            bot.close();
        };
        Runtime.getRuntime().addShutdownHook(new Thread(shutdown, "pingbot-shutdown"));
        try {
            monitor.start();
            bot.start(monitor::isAvailable);
            Logger.getLogger(Main.class.getName()).info(
                    "Monitoring " + configuration.host() + ":" + configuration.port()
                            + " for " + subscribers.snapshot().size() + " subscribers");
        } catch (RuntimeException e) {
            shutdown.run();
            throw e;
        }
    }
}
