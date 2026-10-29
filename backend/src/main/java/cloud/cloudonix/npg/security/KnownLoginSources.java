package cloud.cloudonix.npg.security;

import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Remembers (account, source) pairs that recently logged in successfully. Such sources are exempt
 * from the per-account limit, so an attacker spraying an account from elsewhere cannot lock its
 * owner out. Bounded LRU, per-process and non-durable (losing it only removes the exemption).
 */
public class KnownLoginSources {

    private final Duration ttl;
    private final Clock clock;
    private final int maxEntries;
    private final LinkedHashMap<String, Long> lastSuccess;

    public KnownLoginSources(Duration ttl, Clock clock, int maxEntries) {
        this.ttl = ttl;
        this.clock = clock;
        this.maxEntries = maxEntries;
        this.lastSuccess = new LinkedHashMap<>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                return size() > KnownLoginSources.this.maxEntries;
            }
        };
    }

    public synchronized void remember(String accountKey, String source) {
        lastSuccess.put(accountKey + "|" + source, clock.millis());
    }

    public synchronized boolean isKnown(String accountKey, String source) {
        Long at = lastSuccess.get(accountKey + "|" + source);
        return at != null && clock.millis() - at <= ttl.toMillis();
    }
}
