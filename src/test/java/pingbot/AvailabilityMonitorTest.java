package pingbot;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AvailabilityMonitorTest {
    @Test
    void establishesBaselineWithoutBroadcastAndNotifiesOnlyOnTransitions() {
        AtomicBoolean available = new AtomicBoolean(true);
        List<Boolean> changes = new ArrayList<>();
        try (AvailabilityMonitor monitor = new AvailabilityMonitor(available::get, changes::add, 60000)) {
            monitor.start();
            assertTrue(monitor.isAvailable());
            assertTrue(changes.isEmpty());
            monitor.check();
            available.set(false);
            monitor.check();
            monitor.check();
            available.set(true);
            monitor.check();

            assertTrue(monitor.isAvailable());
            assertEquals(List.of(false, true), changes);
        }
    }

    @Test
    void retainsPreviousStatusAfterUnexpectedCheckFailureAndRecovers() {
        AtomicBoolean fail = new AtomicBoolean(false);
        AtomicBoolean available = new AtomicBoolean(true);
        List<Boolean> changes = new ArrayList<>();
        try (AvailabilityMonitor monitor = new AvailabilityMonitor(() -> {
            if (fail.get()) {
                throw new IllegalStateException("test failure");
            }
            return available.get();
        }, changes::add, 60000)) {
            monitor.start();
            fail.set(true);
            assertDoesNotThrow(monitor::check);
            assertTrue(monitor.isAvailable());
            fail.set(false);
            available.set(false);
            monitor.check();
            assertFalse(monitor.isAvailable());
            assertEquals(List.of(false), changes);
        }
    }

    @Test
    void continuesCheckingAfterNotificationFailure() {
        AtomicBoolean available = new AtomicBoolean(false);
        List<Boolean> changes = new ArrayList<>();
        try (AvailabilityMonitor monitor = new AvailabilityMonitor(available::get, status -> {
            changes.add(status);
            throw new IllegalStateException("test notification failure");
        }, 60000)) {
            monitor.start();
            available.set(true);
            assertDoesNotThrow(monitor::check);
            assertTrue(monitor.isAvailable());
            available.set(false);
            assertDoesNotThrow(monitor::check);
            assertEquals(List.of(true, false), changes);
        }
    }

    @Test
    void schedulesChecksAndRejectsDuplicateStarts() throws InterruptedException {
        AtomicInteger checks = new AtomicInteger();
        CountDownLatch checkedAgain = new CountDownLatch(1);
        try (AvailabilityMonitor monitor = new AvailabilityMonitor(() -> {
            if (checks.incrementAndGet() > 1) {
                checkedAgain.countDown();
            }
            return true;
        }, status -> fail("No transition expected"), 10)) {
            monitor.start();
            assertThrows(IllegalStateException.class, monitor::start);
            assertTrue(checkedAgain.await(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void closedMonitorCannotStartScheduling() {
        AvailabilityMonitor monitor = new AvailabilityMonitor(() -> true, status -> {}, 60000);
        monitor.close();
        assertThrows(RejectedExecutionException.class, monitor::start);
    }
}
