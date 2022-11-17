import java.time.format.DateTimeFormatter;

public class Configuration {
    protected final String FILE_NAME_FOR_LOGS = "log.txt";
    protected final String FILE_NAME_FOR_CHAT_IDS = "chat_ids.txt";
    protected final String HOST = "website e.g. google.com";
    protected final String BOT_TOKEN = "telegram bot token from BotFather";
    // white check mark emoji used
    protected final String ACCEPT_USER_MESSAGE = "✅ - Ok, you are successfully subscribed for notifications about availability of " + HOST;
    // green circle emoji used
    protected final String AVAILABLE_HOST_MESSAGE = "\uD83D\uDFE2 - It's alive! - " + HOST;
    // reg circle emoji used
    protected final String DEAD_HOST_MESSAGE = "\uD83D\uDD34 - What is dead may never die...";

    protected final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("dd-MM HH:mm:ss");

    protected final int PORT = 80;

    /*  1000 - 1 second
        5000 - 5 seconds
       20000 - 20 seconds
       60000 - 60 seconds
     */
    protected final Long SLEEP_TIME_MILLIS = 60000L;
}
