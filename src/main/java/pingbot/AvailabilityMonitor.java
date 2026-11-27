package pingbot;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

final class AvailabilityMonitor implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(AvailabilityMonitor.class.getName());

    private final BooleanSupplier checker;
    private final Consumer<Boolean> onChange;
    private final long intervalMillis;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            task -> new Thread(task, "availability-monitor"));
    private volatile boolean available;
    private boolean started;

    AvailabilityMonitor(BooleanSupplier checker, Consumer<Boolean> onChange, long intervalMillis) {
        this.checker = checker;
        this.onChange = onChange;
        this.intervalMillis = intervalMillis;
    }

    void start() {
        if (started) {
            throw new IllegalStateException("Monitor has already started");
        }
        available = checker.getAsBoolean();
        scheduler.scheduleWithFixedDelay(this::check, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
        started = true;
    }

    boolean isAvailable() {
        return available;
    }

    void check() {
        try {
            boolean next = checker.getAsBoolean();
            if (next != available) {
                available = next;
                LOGGER.info("Host is " + (next ? "available" : "unavailable"));
                onChange.accept(next);
            }
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Availability check or notification failed", e);
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
