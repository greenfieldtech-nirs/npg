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
 * correctness. Memory is bounded at {@code maxKeys}: aged-out and below-limit entries are evicted
 * first, but a key that is currently locked is never evicted. If the table is full of locked keys,
 * new keys are refused (fail closed) rather than letting a flood erase existing locks.
 */
public class LoginThrottle {

    static final int DEFAULT_MAX_KEYS = 50_000;

    private final int maxAttempts;
    private final Duration window;
    private final Clock clock;
    private final int maxKeys;
    // access-ordered so eviction considers the least recently touched keys first
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
     * already used {@code maxAttempts} within the window, or when it is a new key and the table is
     * saturated with locked keys.
     */
    public synchronized boolean tryAcquire(String key) {
        Deque<Long> recent = attempts.get(key);
        if (recent == null) {
            if (attempts.size() >= maxKeys && !makeRoom()) {
                return false;
            }
            recent = new ArrayDeque<>();
            attempts.put(key, recent);
        }
        prune(recent);
        if (recent.size() >= maxAttempts) {
            return false;
        }
        recent.addLast(clock.millis());
        return true;
    }

    /** Clears the key after a successful login, returning its reserved slots. */
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

    /** Evicts one unlocked entry (least recently touched first); false if every entry is locked. */
    private boolean makeRoom() {
        Iterator<Map.Entry<String, Deque<Long>>> it = attempts.entrySet().iterator();
        while (it.hasNext()) {
            Deque<Long> recent = it.next().getValue();
            prune(recent);
            if (recent.size() < maxAttempts) {
                it.remove();
                return true;
            }
        }
        return false;
    }
}
