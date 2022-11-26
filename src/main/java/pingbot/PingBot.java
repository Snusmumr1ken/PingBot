package pingbot;

import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.UpdatesListener;
import com.pengrad.telegrambot.model.Update;
import com.pengrad.telegrambot.request.SendMessage;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

import static java.lang.Thread.sleep;

/**
 * pingbot.Main class with all business logic.
 * Everything could be divided in two categories:
 *      1) Telegram bot
 *      2) Ping
 * Telegram bot sends messages and listens for updates from users.
 * Ping infinite loop checks if host is available and calls telegram bot if status changes.
 */
class PingBot extends Configuration implements Runnable {
    // Bot related staff
    TelegramBot bot;
    private final List<Long> chatIds = new ArrayList<>();

    // File for ids
    File chatIdsFile = new File(FILE_NAME_FOR_CHAT_IDS);
    FileOutputStream chatIdsOutputFile;

    // File for logging
    File logFile = new File(FILE_NAME_FOR_LOGS);
    FileOutputStream logsOutputFile;

    // multithreading staff
    Thread t;
    boolean currentStatus;
    boolean prevStatus;

    /**
     * The only constructor. It runs everything.
     * For details, please see comments above each method.
     */
    PingBot() {
        initLogging();
        chatIdsFileRoutine();
        setBotListener();
        pingRoutine();
    }

    /**
     * Initializes logging. If there is no logging file, creates it.
     * Writes first message in a log file.
     */
    private void initLogging() {
        try {
            if (!logFile.exists()) logFile.createNewFile();
            logsOutputFile = new FileOutputStream(logFile, true);
        } catch (Exception e) {
            e.printStackTrace();
        }
        loggerWrite("Bot started.");
    }

    /**
     * Initializes bot updates listener.
     */
    private void setBotListener() {
        bot = new TelegramBot(BOT_TOKEN);

        // bot updates listener
        bot.setUpdatesListener(updates -> {
            updates
                    .forEach( update -> {
                        if (update.message() == null) return;
                        processUpdate(update);
                    });
            return UpdatesListener.CONFIRMED_UPDATES_ALL;
        });

        loggerWrite("Bot listener initialized.");
    }

    /**
     * Gets host status.
     * Creates new thread and runs it.
     */
    private void pingRoutine() {
        currentStatus = pingHost(HOST, 80, 5000);
        prevStatus = currentStatus;
        t = new Thread(this, "PingThread");
        t.start();
    }

    /**
     * On each message from user, we provide them with a current status of a host.
     * If it is a new user, we also:
     *      1) save chatId in a list of chatIds
     *      2) write chatId in a chatIdsFile
     *      3) send them acceptUserMessage
     * @param update telegrambot.model.Update. Actually, we take a message from it to get a username and a chatId.
     */
    private void processUpdate(Update update) {
        long chatId = update.message().chat().id();

        loggerWrite("Request from chat id " + chatId + ", from user " + update.message().chat().username());
        // if it is new user
        if (!chatIds.contains(chatId)) {
            chatIds.add(chatId);
            writeInFileId(chatId);
            bot.execute(new SendMessage(chatId, ACCEPT_USER_MESSAGE));
        }
        bot.execute(new SendMessage(chatId, currentStatus ? AVAILABLE_HOST_MESSAGE : DEAD_HOST_MESSAGE));
    }

    /**
     * Scans a chatIdsFile for chatIds, saves them in a chatIds list.
     */
    private void parseChatIdsFile() {
        try {
            Scanner scanner = new Scanner(chatIdsFile);
            while (scanner.hasNext()) {
                long id = scanner.nextLong();
                chatIds.add(id);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * try-catch wrapper for writing chatIds in a chatIdsFile
     * @param chatId Long stores chatId
     */
    private void writeInFileId(Long chatId) {
        try {
            chatIdsOutputFile.write(String.valueOf(chatId).getBytes());
            chatIdsOutputFile.write("\n".getBytes());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Takes a message, adds formatted date and the end line char, writes it in a log file.
     * @param m message to write in a logger.
     */
    private synchronized void loggerWrite(String m) {
        LocalDateTime myDateObj = LocalDateTime.now();
        String formattedDate = myDateObj.format(DATE_TIME_FORMATTER);

        m += " - " + formattedDate + "\n";
        try {
            logsOutputFile.write(m.getBytes());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Initializes file with chatIds. If there is no file to store chatIds, creates it.
     * Parses chatIds.
     */
    private void chatIdsFileRoutine() {
        try {
            if (!chatIdsFile.exists()) chatIdsFile.createNewFile();
            chatIdsOutputFile = new FileOutputStream(chatIdsFile, true);
        } catch (Exception e) {
            e.printStackTrace();
        }

        parseChatIdsFile();

        loggerWrite("File parsed. Chat Ids found:");
        if (chatIds.isEmpty()) loggerWrite("No ids.");

        chatIds.forEach( id -> {
            loggerWrite(String.valueOf(id));
        });
    }

    /**
     * Takes a boolean and sends message about it in each chatId.
     * @param availability boolean indicates if host is available
     */
    private synchronized void sendUpdateEveryone(boolean availability) {
        String message = availability ? AVAILABLE_HOST_MESSAGE : DEAD_HOST_MESSAGE;

        for (Long chatId: chatIds) {
            bot.execute(new SendMessage(chatId, message));
        }
    }

    /**
     * Initializes ping loop.
     * Each sleepTimeMillis checks if host is available. If status changes, calls sendUpdateEveryone
     */
    @Override
    public void run() {
        loggerWrite("New thread: " + Thread.currentThread() + " works.");
        while (true) {
            currentStatus = pingHost(HOST, PORT, 5000);

            if (currentStatus != prevStatus) {
                loggerWrite("Host status changed: " + currentStatus);
                sendUpdateEveryone(currentStatus);
                prevStatus = currentStatus;
            }

            try {
                sleep(SLEEP_TIME_MILLIS);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * Method to ping a host.
     * @param host host to ping
     * @param port which port to check
     * @param timeout the timeout value
     * @return boolean based on host availability
     */
    private boolean pingHost(String host, int port, int timeout) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeout);
            return true;
        } catch (IOException e) {
            return false; // Either timeout or unreachable or failed DNS lookup.
        }
    }
}
