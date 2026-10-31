package cloud.cloudonix.npg.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.status.Status;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** The production logback.xml (tests normally run with logback-test.xml, so it is loaded explicitly). */
class ProductionLoggingTest {

    @Test
    void productionConfigLoadsCleanlyAndMasksPhoneNumbers() throws Exception {
        PrintStream original = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        LoggerContext context = new LoggerContext();
        // a standalone context needs the MDC adapter the real SLF4J binding installs
        context.setMDCAdapter(new ch.qos.logback.classic.util.LogbackMDCAdapter());
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            JoranConfigurator configurator = new JoranConfigurator();
            configurator.setContext(context);
            configurator.doConfigure(getClass().getResource("/logback.xml"));
            Logger log = context.getLogger("npg.test");
            log.info("Porting 0521234567 and 972521234567 for request NPBZHL261030000000004");
        } finally {
            System.setOut(original);
            context.stop();
        }
        // no configuration errors (a broken masker is reported here and silently dropped)
        for (Status status : context.getStatusManager().getCopyOfStatusList()) {
            assertTrue(status.getLevel() < Status.ERROR, status.toString());
        }
        String line = captured.toString(StandardCharsets.UTF_8);
        assertTrue(line.contains("\"message\""), "JSON output: " + line);
        assertFalse(line.contains("0521234567"), line);
        assertTrue(line.contains("052****67"), line);
        assertTrue(line.contains("97252****67"), line);
        assertTrue(line.contains("NPBZHL261030000000004"), "request ids are not phone numbers: " + line);
    }
}
