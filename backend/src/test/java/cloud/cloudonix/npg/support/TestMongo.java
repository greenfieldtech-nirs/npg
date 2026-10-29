package cloud.cloudonix.npg.support;

import org.testcontainers.containers.MongoDBContainer;

public class TestMongo {

    private static final boolean USE_ENV;
    private static MongoDBContainer container;

    static {
        String uri = System.getenv("MONGO_URI");
        USE_ENV = uri != null && !uri.isBlank();
    }

    public static void start() {
        if (!USE_ENV) {
            container = new MongoDBContainer("mongo:7");
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
            return System.getenv("MONGO_URI");
        }
        return container.getConnectionString();
    }

    public static String dbName() {
        String value = System.getenv("MONGO_DB");
        return value != null && !value.isBlank() ? value : "npg_test";
    }
}
