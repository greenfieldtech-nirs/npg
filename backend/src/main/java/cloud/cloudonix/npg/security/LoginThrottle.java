package cloud.cloudonix.npg.security;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Brute-force brake for the login endpoint: after {@code maxFailures} failed attempts for the
 * same key (username|ip) within {@code window}, further attempts are refused until the oldest
 * failure ages out. Per-process and non-durable by design — losing it on restart only resets
 * the brake, it never affects correctness.
 */
public class LoginThrottle {

    private final int maxFailures;
    private final Duration window;
    private final Clock clock;
    private final Map<String, Deque<Long>> failures = new ConcurrentHashMap<>();

    public LoginThrottle(int maxFailures, Duration window, Clock clock) {
        this.maxFailures = maxFailures;
        this.window = window;
        this.clock = clock;
    }

    public LoginThrottle() {
        this(5, Duration.ofMinutes(5), Clock.systemUTC());
    }

    public boolean isLocked(String key) {
        Deque<Long> attempts = failures.get(key);
        if (attempts == null) {
            return false;
        }
        synchronized (attempts) {
            prune(attempts);
            return attempts.size() >= maxFailures;
        }
    }

    public void recordFailure(String key) {
        Deque<Long> attempts = failures.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (attempts) {
            prune(attempts);
            attempts.addLast(clock.millis());
        }
    }

    public void reset(String key) {
        failures.remove(key);
    }

    private void prune(Deque<Long> attempts) {
        long cutoff = clock.millis() - window.toMillis();
        while (!attempts.isEmpty() && attempts.peekFirst() < cutoff) {
            attempts.removeFirst();
        }
    }
}
