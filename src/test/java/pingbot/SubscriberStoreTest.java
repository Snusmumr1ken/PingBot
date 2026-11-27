package pingbot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SubscriberStoreTest {
    @TempDir
    Path directory;

    @Test
    void createsFileAndPersistsUniqueSubscriptionsAcrossRestarts() throws IOException {
        Path file = directory.resolve("chat_ids.txt");
        SubscriberStore store = new SubscriberStore(file);

        assertTrue(store.subscribe(123L));
        assertFalse(store.subscribe(123L));
        assertTrue(store.subscribe(-1001234567890L));
        assertEquals(List.of(123L, -1001234567890L), new SubscriberStore(file).snapshot());
        assertEquals("123\n-1001234567890\n", Files.readString(file));
    }

    @Test
    void readsLegacyWhitespaceAndDuplicatesAndAppendsWithoutMergingIds() throws IOException {
        Path file = directory.resolve("chat_ids.txt");
        Files.writeString(file, "123\n\n123\t-456");
        SubscriberStore store = new SubscriberStore(file);

        assertEquals(List.of(123L, -456L), store.snapshot());
        assertTrue(store.subscribe(789L));
        assertEquals(List.of(123L, -456L, 789L), new SubscriberStore(file).snapshot());
    }

    @Test
    void rejectsCorruptFilesWithoutChangingThem() throws IOException {
        Path file = directory.resolve("chat_ids.txt");
        String content = "123\ninvalid\n456\n";
        Files.writeString(file, content);

        assertThrows(IOException.class, () -> new SubscriberStore(file));
        assertEquals(content, Files.readString(file));
    }

    @Test
    void leavesMemoryUnchangedWhenPersistenceFails() throws IOException {
        Path file = directory.resolve("chat_ids.txt");
        SubscriberStore store = new SubscriberStore(file);
        store.subscribe(123L);
        Files.delete(file);
        Files.createDirectory(file);

        assertThrows(IOException.class, () -> store.subscribe(456L));
        assertEquals(List.of(123L), store.snapshot());
    }

    @Test
    void snapshotsRemainStableWhileSubscriptionsChange() throws IOException {
        SubscriberStore store = new SubscriberStore(directory.resolve("chat_ids.txt"));
        store.subscribe(123L);
        List<Long> snapshot = store.snapshot();
        store.subscribe(456L);

        assertEquals(List.of(123L), snapshot);
        assertThrows(UnsupportedOperationException.class, () -> snapshot.add(789L));
    }

    @Test
    void concurrentSubscriptionsRemainUniqueAndPersisted() throws Exception {
        Path file = directory.resolve("chat_ids.txt");
        SubscriberStore store = new SubscriberStore(file);
        var executor = Executors.newFixedThreadPool(4);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int index = 0; index < 100; index++) {
                long chatId = index % 10;
                results.add(executor.submit(() -> {
                    boolean added = store.subscribe(chatId);
                    store.snapshot().forEach(id -> assertNotNull(id));
                    return added;
                }));
            }
            int subscriptions = 0;
            for (Future<Boolean> result : results) {
                if (result.get(5, TimeUnit.SECONDS)) {
                    subscriptions++;
                }
            }
            assertEquals(10, subscriptions);
            assertEquals(store.snapshot(), new SubscriberStore(file).snapshot());
        } finally {
            executor.shutdownNow();
        }
    }
}
