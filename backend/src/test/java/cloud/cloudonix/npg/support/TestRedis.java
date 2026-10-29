package cloud.cloudonix.npg.support;

import org.testcontainers.containers.GenericContainer;

public class TestRedis {

    private static final boolean USE_ENV;
    private static GenericContainer<?> container;

    static {
        String uri = System.getenv("REDIS_URI");
        USE_ENV = uri != null && !uri.isBlank();
    }

    public static void start() {
        if (!USE_ENV) {
            container = new GenericContainer<>("redis:7-alpine")
                .withExposedPorts(6379);
            container.start();
        }
    }

    public static void stop() {
        if (container != null) {
            container.stop();
        }
    }

    public static String uri() {
        if (USE_ENV) {
            return System.getenv("REDIS_URI");
        }
        return "redis://" + container.getHost() + ":" + container.getMappedPort(6379);
    }
}
