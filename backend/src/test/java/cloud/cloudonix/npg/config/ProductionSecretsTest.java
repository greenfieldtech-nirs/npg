package cloud.cloudonix.npg.config;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ProductionSecretsTest {

    @Test
    void theExamplePlaceholdersAreRefusedByName() {
        Map<String, String> env = Map.of(
            "NPG_ADMIN_PASSWORD", "REQUIRED-change-me",
            "NPG_JWT_SECRET", "a-real-secret-that-is-long-enough-123456",
            "DB_PASSWORD", "REQUIRED-change-me",
            "MONGO_URI", "mongodb://npg:REQUIRED-change-me@mongodb:27017/?authSource=admin");
        assertEquals(java.util.List.of("NPG_ADMIN_PASSWORD", "DB_PASSWORD", "MONGO_URI"), ProductionSecrets.placeholders(env::get));
    }

    @Test
    void realValuesPass() {
        Map<String, String> env = Map.of("NPG_ADMIN_PASSWORD", "correct-horse-battery", "MONGO_URI", "mongodb://npg:s3cret@mongodb:27017");
        assertTrue(ProductionSecrets.placeholders(env::get).isEmpty());
    }
}
