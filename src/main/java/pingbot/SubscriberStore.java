package pingbot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

final class SubscriberStore {
    private final Path file;
    private final Set<Long> chatIds = new LinkedHashSet<>();
    private boolean needsSeparator;

    SubscriberStore(Path file) throws IOException {
        this.file = file;
        if (Files.notExists(file)) {
            Files.createFile(file);
        }
        String content = Files.readString(file);
        for (String entry : content.split("\\s+")) {
            if (!entry.isEmpty()) {
                try {
                    chatIds.add(Long.parseLong(entry));
                } catch (NumberFormatException e) {
                    throw new IOException("Invalid chat ID in " + file + ": " + entry, e);
                }
            }
        }
        needsSeparator = !content.isEmpty() && !Character.isWhitespace(content.charAt(content.length() - 1));
    }

    synchronized boolean subscribe(long chatId) throws IOException {
        if (chatIds.contains(chatId)) {
            return false;
        }
        String entry = (needsSeparator ? "\n" : "") + chatId + "\n";
        Files.writeString(file, entry, APPEND);
        needsSeparator = false;
        chatIds.add(chatId);
        return true;
    }

    synchronized boolean unsubscribe(long chatId) throws IOException {
        if (!chatIds.contains(chatId)) {
            return false;
        }
        StringBuilder content = new StringBuilder();
        for (long id : chatIds) {
            if (id != chatId) {
                content.append(id).append('\n');
            }
        }
        Path target = file.toAbsolutePath();
        Path temporary = Files.createTempFile(target.getParent(), ".chat-ids-", ".tmp");
        try {
            Files.writeString(temporary, content);
            Files.move(temporary, target, ATOMIC_MOVE, REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
        chatIds.remove(chatId);
        needsSeparator = false;
        return true;
    }

    synchronized List<Long> snapshot() {
        return List.copyOf(chatIds);
    }
}
