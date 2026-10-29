package cloud.cloudonix.npg.security;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Sliding-window attempt limiter for the login endpoint. Each attempt must first reserve a slot
 * with {@link #tryAcquire}; slots are only given back by {@link #release} (one successful attempt)
 * or {@link #reset}, so concurrent in-flight guesses count against the limit (no check-then-record
 * race).
 *
 * <p>Per-process and non-durable by design — a restart only resets the brake, never affects
 * correctness. Memory is bounded at {@code maxKeys}: when full, keys below the limit are evicted
 * first (least recently touched), and only if every key is locked is the oldest locked key
 * dropped. That last resort is acceptable because callers key tables so that saturating them is
 * either impossible (one entry per real account) or requires more distinct client addresses than
 * an IP-based limit can stop anyway.
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

    /** Reserves an attempt for {@code key}; false (nothing reserved) when the window is full. */
    public synchronized boolean tryAcquire(String key) {
        Deque<Long> recent = attempts.get(key);
        if (recent == null) {
            if (attempts.size() >= maxKeys) {
                makeRoom();
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

    /** Gives back the most recent reservation (the attempt turned out to be legitimate). */
    public synchronized void release(String key) {
        Deque<Long> recent = attempts.get(key);
        if (recent != null && !recent.isEmpty()) {
            recent.removeLast();
        }
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

    /** Evicts the least recently touched unlocked key, or failing that the least recently touched key. */
    private void makeRoom() {
        Iterator<Map.Entry<String, Deque<Long>>> it = attempts.entrySet().iterator();
        while (it.hasNext()) {
            Deque<Long> recent = it.next().getValue();
            prune(recent);
            if (recent.size() < maxAttempts) {
                it.remove();
                return;
            }
        }
        it = attempts.entrySet().iterator();
        if (it.hasNext()) {
            it.next();
            it.remove();
        }
    }
}
