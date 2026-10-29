package cloud.cloudonix.npg.security;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Brute-force brake for the login endpoint. Each attempt must first reserve a slot with
 * {@link #tryAcquire}; the slot is only given back by {@link #reset} on a successful login, so
 * concurrent in-flight guesses count against the limit too (no check-then-record race).
 *
 * <p>Per-process and non-durable by design — a restart only resets the brake, never affects
 * correctness. Memory is bounded: empty entries are dropped and, past {@code maxKeys}, the
 * least-recently-touched keys are evicted.
 */
public class LoginThrottle {

    static final int DEFAULT_MAX_KEYS = 50_000;

    private final int maxAttempts;
    private final Duration window;
    private final Clock clock;
    private final int maxKeys;
    // access-ordered so eviction removes the least recently touched key first
    private final LinkedHashMap<String, Deque<Long>> attempts = new LinkedHashMap<>(256, 0.75f, true);

    public LoginThrottle(int maxAttempts, Duration window, Clock clock, int maxKeys) {
        this.maxAttempts = maxAttempts;
        this.window = window;
        this.clock = clock;
        this.maxKeys = maxKeys;
    }

    public LoginThrottle(int maxAttempts, Duration window, Clock clock) {
        this(maxAttempts, window, clock, DEFAULT_MAX_KEYS);
    }

    /**
     * Reserves an attempt for {@code key}. Returns false (and reserves nothing) when the key
     * already used {@code maxAttempts} within the window.
     */
    public synchronized boolean tryAcquire(String key) {
        Deque<Long> recent = attempts.computeIfAbsent(key, k -> new ArrayDeque<>());
        prune(recent);
        if (recent.size() >= maxAttempts) {
            return false;
        }
        recent.addLast(clock.millis());
        evictIfNeeded();
        return true;
    }

    /** Clears the key after a successful login, returning its reserved slot. */
    public synchronized void reset(String key) {
        attempts.remove(key);
    }

    synchronized int trackedKeys() {
        return attempts.size();
    }

    private void prune(Deque<Long> recent) {
        long cutoff = clock.millis() - window.toMillis();
        while (!recent.isEmpty() && recent.peekFirst() < cutoff) {
            recent.removeFirst();
        }
    }

    private void evictIfNeeded() {
        if (attempts.size() <= maxKeys) {
            return;
        }
        // first drop entries whose attempts all aged out, then the least recently touched
        Iterator<Map.Entry<String, Deque<Long>>> it = attempts.entrySet().iterator();
        while (it.hasNext() && attempts.size() > maxKeys) {
            Map.Entry<String, Deque<Long>> entry = it.next();
            prune(entry.getValue());
            if (entry.getValue().isEmpty()) {
                it.remove();
            }
        }
        it = attempts.entrySet().iterator();
        while (it.hasNext() && attempts.size() > maxKeys) {
            it.next();
            it.remove();
        }
    }
}
