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
    void saturatedTableOfLockedKeysEvictsOldestInsteadOfRefusingEveryone() {
        LoginThrottle throttle = new LoginThrottle(1, Duration.ofMinutes(5), new MutableClock(), 3);
        for (String k : new String[]{"a", "b", "c"}) {
            assertTrue(throttle.tryAcquire(k));
        }
        assertTrue(throttle.tryAcquire("d"), "new keys must still be served when the table is full");
        assertEquals(3, throttle.trackedKeys());
    }

    @Test
    void releaseReturnsOnlyOneReservation() {
        LoginThrottle throttle = new LoginThrottle(2, Duration.ofMinutes(5), new MutableClock());
        assertTrue(throttle.tryAcquire("ip"));   // failed attempt, kept
        assertTrue(throttle.tryAcquire("ip"));   // successful attempt...
        throttle.release("ip");                  // ...gives its own slot back
        assertTrue(throttle.tryAcquire("ip"));
        assertFalse(throttle.tryAcquire("ip"));
    }

    @Test
    void deviceTrustIsBoundToAccountSignatureAndExpiry() {
        MutableClock clock = new MutableClock();
        JwtService signer = new JwtService("z".repeat(40));
        DeviceTrust trust = new DeviceTrust(signer, Duration.ofDays(30), clock);
        String hash = "$2a$10$storedhash";
        String token = trust.issue(7L, hash);
        assertTrue(trust.isTrusted(token, 7L, hash));
        assertFalse(trust.isTrusted(token, 8L, hash), "token for another account");
        assertFalse(trust.isTrusted(token.replace("v1.7.", "v1.8."), 8L, hash), "re-targeted token");
        assertFalse(new DeviceTrust(new JwtService("q".repeat(40)), Duration.ofDays(30), clock).isTrusted(token, 7L, hash),
            "signed with another secret");
        assertFalse(trust.isTrusted("garbage", 7L, hash));
        assertFalse(trust.isTrusted(null, 7L, hash));
        clock.advance(Duration.ofDays(31));
        assertFalse(trust.isTrusted(token, 7L, hash), "expired");
    }

    @Test
    void passwordChangeRevokesDeviceTokens() {
        DeviceTrust trust = new DeviceTrust(new JwtService("z".repeat(40)), Duration.ofDays(30), Clock.systemUTC());
        String token = trust.issue(7L, "$2a$10$oldhash");
        assertTrue(trust.isTrusted(token, 7L, "$2a$10$oldhash"));
        assertFalse(trust.isTrusted(token, 7L, "$2a$10$newhash"));
        assertFalse(token.contains(DeviceTrust.credentialFingerprint("$2a$10$oldhash")), "fingerprint is never disclosed");
    }

    @Test
    void deviceTrustSignatureCannotBeReplayedAsJwt() {
        JwtService signer = new JwtService("z".repeat(40));
        String token = new DeviceTrust(signer, Duration.ofDays(1), Clock.systemUTC()).issue(1L, "h");
        assertTrue(signer.verify(token).isEmpty());
    }

    @Test
    void forwardedHeadersOnlyHonouredFromTrustedProxies() {
        ClientAddress resolver = new ClientAddress(java.util.List.of("10.0.0.0/8"));
        assertEquals("203.0.113.9", resolver.resolve("10.1.2.3", "203.0.113.9", null));
        assertEquals("203.0.113.9", resolver.resolve("10.1.2.3", null, "1.1.1.1, 203.0.113.9"));
        // a direct client cannot pick its own address
        assertEquals("198.51.100.4", resolver.resolve("198.51.100.4", "10.9.9.9", "10.9.9.9"));
        assertEquals("10.1.2.3", resolver.resolve("10.1.2.3", null, null));
    }

    @Test
    void privateKeywordAndIpv6Ranges() {
        ClientAddress resolver = new ClientAddress(java.util.List.of("private"));
        assertTrue(resolver.isTrusted("172.20.0.5"));
        assertTrue(resolver.isTrusted("127.0.0.1"));
        assertTrue(resolver.isTrusted("::1"));
        assertTrue(resolver.isTrusted("fd00::1"));
        assertFalse(resolver.isTrusted("8.8.8.8"));
        assertFalse(resolver.isTrusted("172.32.0.1"));
        assertFalse(resolver.isTrusted("not-an-ip"), "hostnames are never resolved");
        assertFalse(new ClientAddress(java.util.List.of("")).isTrusted("127.0.0.1"), "trust nothing by default");
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
