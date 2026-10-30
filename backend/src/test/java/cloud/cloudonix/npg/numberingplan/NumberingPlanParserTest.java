package cloud.cloudonix.npg.numberingplan;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

/** Parsing the Ministry of Communications file and classifying its ranges with libphonenumber. */
class NumberingPlanParserTest {

    static String sample() throws IOException {
        return new String(Objects.requireNonNull(NumberingPlanParserTest.class.getResourceAsStream("/numbering-plan/sample.csv"))
            .readAllBytes(), StandardCharsets.UTF_8);
    }

    private static NumberingPlanParser.PlanRange range(List<NumberingPlanParser.PlanRange> ranges, String e164) {
        return ranges.stream().filter(r -> r.e164Pattern().equals(e164)).findFirst().orElseThrow();
    }

    @Test
    void parsesTheRealFileFormat() throws IOException {
        NumberingPlanParser.ParseResult result = NumberingPlanParser.parse(sample());
        assertEquals(10, result.ranges().size(), result.warnings().toString());
        assertTrue(result.warnings().isEmpty(), result.warnings().toString());

        NumberingPlanParser.PlanRange jerusalem = range(result.ranges(), "+972 2 30XXXXX");
        assertEquals("230", jerusalem.nsnPrefix());
        assertEquals(8, jerusalem.nsnLength());
        assertEquals(100_000, jerusalem.rangeSize());
        assertEquals("HOT TELECOM LP", jerusalem.allocatedTo());
        assertEquals("02-30XXXXX", jerusalem.nationalPattern());

        // quoted operator names keep their comma
        assertEquals("BEZEQ, THE ISRAELI TELECOMMUNICATIONS CORP. LTD", range(result.ranges(), "+972 2 50XXXXX").allocatedTo());

        NumberingPlanParser.PlanRange iot = range(result.ranges(), "+972 13 02XXXXXX");
        assertEquals("1302", iot.nsnPrefix());
        assertEquals(10, iot.nsnLength());
        assertEquals("IOT/M2M Services", iot.notes());
    }

    @Test
    void rejectsSomethingThatIsNotThePlan() {
        assertThrows(NumberingPlanParser.InvalidPlanException.class, () -> NumberingPlanParser.parse("a,b,c\n1,2,3\n"));
        assertThrows(NumberingPlanParser.InvalidPlanException.class, () -> NumberingPlanParser.parse(""));
        assertThrows(NumberingPlanParser.InvalidPlanException.class,
            () -> NumberingPlanParser.parse("<html><body>403 ERROR</body></html>"));
    }

    @Test
    void rejectsADamagedPlanButToleratesAFewBadRows() throws IOException {
        String good = sample();
        NumberingPlanParser.ParseResult tolerated = NumberingPlanParser.parse(good + "2,+972 2 ??,02-??,SOMEONE,\n");
        assertEquals(10, tolerated.ranges().size());
        assertEquals(1, tolerated.warnings().size());

        StringBuilder damaged = new StringBuilder(good);
        for (int i = 0; i < 6; i++) {
            damaged.append("2,garbage ").append(i).append(",x,SOMEONE,\n");
        }
        assertThrows(NumberingPlanParser.InvalidPlanException.class, () -> NumberingPlanParser.parse(damaged.toString()));
    }

    @Test
    void duplicatesAreSkippedWithAWarning() throws IOException {
        NumberingPlanParser.ParseResult result = NumberingPlanParser.parse(sample() + "2,+972 2 30XXXXX,02-30XXXXX,SOMEONE ELSE,\n");
        assertEquals("HOT TELECOM LP", range(result.ranges(), "+972 2 30XXXXX").allocatedTo());
        assertTrue(result.warnings().get(0).contains("duplicate"));
    }

    @Test
    void libphonenumberClassifiesEachKindOfRange() {
        PhoneNumbers.RangeInfo jerusalem = PhoneNumbers.classify("230", 8);
        assertTrue(jerusalem.valid());
        assertEquals("FIXED_LINE", jerusalem.numberType());
        assertNotNull(jerusalem.area(), "geographic numbers have an area");
        assertEquals("+972 2-300-0000", jerusalem.exampleInternational());

        PhoneNumbers.RangeInfo mobile = PhoneNumbers.classify("522", 9);
        assertTrue(mobile.valid());
        assertEquals("MOBILE", mobile.numberType());
        assertEquals("052-200-0000", mobile.exampleNational());

        assertEquals("VOIP", PhoneNumbers.classify("722", 9).numberType());
    }

    @Test
    void parsesNumbersInEveryCommonForm() {
        for (String form : new String[]{"052-2345678", "0522345678", "+972 52 234 5678", "972522345678", "+972-52-2345678"}) {
            PhoneNumbers.Parsed parsed = PhoneNumbers.parse(form);
            assertNotNull(parsed, form);
            assertEquals("522345678", parsed.nsn(), form);
            assertEquals("+972522345678", parsed.e164(), form);
            assertTrue(parsed.valid(), form);
        }
        assertNull(PhoneNumbers.parse("+1 212 555 0100"), "not an Israeli number");
        assertNull(PhoneNumbers.parse("hello"));
    }
}
