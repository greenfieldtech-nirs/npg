package cloud.cloudonix.npg.security;

import cloud.cloudonix.npg.acknack.NackReason;
import cloud.cloudonix.npg.acknack.TechnicalAck;
import cloud.cloudonix.npg.config.AuthSeederAccess;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Secure-by-default configuration: production refuses well-known secrets. */
class SecurityConfigTest {

    @AfterEach
    void restoreTestMode() {
        System.setProperty("NPG_ENV", "test");
        System.clearProperty("NPG_JWT_SECRET");
    }

    @Test
    void productionRequiresJwtSecret() {
        System.setProperty("NPG_ENV", "production");
        System.clearProperty("NPG_JWT_SECRET");
        assertThrows(IllegalStateException.class, JwtService::fromEnvironment);
    }

    @Test
    void productionRejectsShortJwtSecret() {
        System.setProperty("NPG_ENV", "production");
        System.setProperty("NPG_JWT_SECRET", "too-short");
        assertThrows(IllegalStateException.class, JwtService::fromEnvironment);
    }

    @Test
    void productionAcceptsStrongJwtSecret() {
        System.setProperty("NPG_ENV", "production");
        System.setProperty("NPG_JWT_SECRET", "x".repeat(JwtService.MIN_SECRET_LENGTH));
        JwtService jwt = JwtService.fromEnvironment();
        String token = jwt.issue("alice", List.of("USER"), Duration.ofMinutes(1));
        assertEquals("alice", jwt.verify(token).orElseThrow().username());
    }

    @Test
    void unknownEnvironmentIsTreatedAsProduction() {
        System.setProperty("NPG_ENV", "staging");
        System.clearProperty("NPG_JWT_SECRET");
        assertThrows(IllegalStateException.class, JwtService::fromEnvironment);
    }

    @Test
    void devModeFallsBackToBuiltInSecret() {
        System.setProperty("NPG_ENV", "dev");
        System.clearProperty("NPG_JWT_SECRET");
        assertNotNull(JwtService.fromEnvironment());
    }

    @Test
    void productionRefusesDefaultAdminPassword() {
        System.setProperty("NPG_ENV", "production");
        assertThrows(IllegalStateException.class, () -> AuthSeederAccess.initialAdminPassword(null));
        assertThrows(IllegalStateException.class, () -> AuthSeederAccess.initialAdminPassword("short"));
        assertEquals("a-long-enough-password", AuthSeederAccess.initialAdminPassword("a-long-enough-password"));
    }

    @Test
    void devModeSeedsDefaultAdminPassword() {
        System.setProperty("NPG_ENV", "test");
        assertEquals("admin123", AuthSeederAccess.initialAdminPassword(null));
    }

    @Test
    void expiredTokenRejected() {
        JwtService jwt = new JwtService("y".repeat(40));
        String token = jwt.issue("bob", List.of("ADMIN"), Duration.ofSeconds(-5));
        assertTrue(jwt.verify(token).isEmpty());
    }

    @Test
    void loginThrottleLocksAfterMaxAttemptsAndExpires() {
        MutableClock clock = new MutableClock();
        LoginThrottle throttle = new LoginThrottle(3, Duration.ofMinutes(5), clock);
        for (int i = 0; i < 3; i++) {
            assertTrue(throttle.tryAcquire("u|ip"));
        }
        assertFalse(throttle.tryAcquire("u|ip"));
        assertTrue(throttle.tryAcquire("other|ip"));
        clock.advance(Duration.ofMinutes(6));
        assertTrue(throttle.tryAcquire("u|ip"));
    }

    @Test
    void loginThrottleResetReturnsSlots() {
        LoginThrottle throttle = new LoginThrottle(2, Duration.ofMinutes(5), new MutableClock());
        assertTrue(throttle.tryAcquire("k"));
        assertTrue(throttle.tryAcquire("k"));
        assertFalse(throttle.tryAcquire("k"));
        throttle.reset("k");
        assertTrue(throttle.tryAcquire("k"));
    }

    @Test
    void loginThrottleCountsConcurrentInFlightAttempts() throws Exception {
        LoginThrottle throttle = new LoginThrottle(5, Duration.ofMinutes(5), Clock.systemUTC());
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(16);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger granted = new java.util.concurrent.atomic.AtomicInteger();
        java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < 100; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                if (throttle.tryAcquire("victim|ip")) {
                    granted.incrementAndGet();
                }
                return null;
            }));
        }
        start.countDown();
        for (java.util.concurrent.Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();
        assertEquals(5, granted.get());
    }

    @Test
    void loginThrottleMemoryIsBounded() {
        LoginThrottle throttle = new LoginThrottle(5, Duration.ofMinutes(5), new MutableClock(), 100);
        for (int i = 0; i < 10_000; i++) {
            throttle.tryAcquire("spray-" + i);
        }
        assertTrue(throttle.trackedKeys() <= 100, "tracked " + throttle.trackedKeys());
    }

    @Test
    void floodCannotEvictLockedKeys() {
        LoginThrottle throttle = new LoginThrottle(2, Duration.ofMinutes(5), new MutableClock(), 10);
        assertTrue(throttle.tryAcquire("victim"));
        assertTrue(throttle.tryAcquire("victim"));
        assertFalse(throttle.tryAcquire("victim"));
        for (int i = 0; i < 1_000; i++) {
            throttle.tryAcquire("spray-" + i);
        }
        assertFalse(throttle.tryAcquire("victim"), "a locked key must survive a key flood");
    }

    @Test
    void saturatedTableOfLockedKeysFailsClosedForNewKeys() {
        LoginThrottle throttle = new LoginThrottle(1, Duration.ofMinutes(5), new MutableClock(), 3);
        for (String k : new String[]{"a", "b", "c"}) {
            assertTrue(throttle.tryAcquire(k));
        }
        assertFalse(throttle.tryAcquire("d"));
        assertEquals(3, throttle.trackedKeys());
    }

    @Test
    void knownLoginSourcesExpire() {
        MutableClock clock = new MutableClock();
        KnownLoginSources known = new KnownLoginSources(Duration.ofHours(24), clock, 10);
        known.remember("id:1", "10.0.0.1");
        assertTrue(known.isKnown("id:1", "10.0.0.1"));
        assertFalse(known.isKnown("id:1", "10.0.0.2"));
        clock.advance(Duration.ofHours(25));
        assertFalse(known.isKnown("id:1", "10.0.0.1"));
    }

    @Test
    void ackXmlEscapesPeerControlledFields() {
        TechnicalAck nack = TechnicalAck.nack("</CorrelationId><Status>ACK</Status>", "tx&<1>",
            new NackReason("NACK_X", null, "bad <input>", "TRANSPORT", false));
        String xml = nack.toXml();
        assertFalse(xml.contains("<Status>ACK</Status>"), xml);
        assertTrue(xml.contains("&lt;/CorrelationId&gt;"), xml);
        assertTrue(xml.contains("tx&amp;&lt;1&gt;"), xml);
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
