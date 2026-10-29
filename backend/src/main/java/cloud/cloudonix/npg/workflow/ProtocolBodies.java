package cloud.cloudonix.npg.workflow;

import io.vertx.core.json.JsonObject;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;

/**
 * Message bodies in the shape observed in the live operator trace. Elements that the trace does
 * not show (mobile numbers, negative approvals, reject reasons) follow the same naming pattern and
 * are listed in docs/protocol/open-questions.md until the official XSD confirms them.
 */
public final class ProtocolBodies {

    private static final ZoneId ISRAEL = ZoneId.of("Asia/Jerusalem");

    private ProtocolBodies() {
    }

    /** {@code <fixed>}/{@code <mobile>} number block as in the trace's Check and Publish. */
    public static JsonObject numberBlock(String numberType, String phoneNumber, String identificationValue) {
        boolean mobile = "MOBILE".equals(numberType);
        String prefix = mobile ? "mobile" : "fixed";
        JsonObject block = new JsonObject()
            .put("networkType", mobile ? "M" : "F")
            .put(prefix + "NumberSingle", new JsonObject().put("numberType", "S").put("number", phoneNumber));
        if (identificationValue != null) {
            block.put("identificationValue", identificationValue)
                .put("identificationValue2nd", new JsonObject())
                .put("identificationValue3rd", new JsonObject());
        }
        return new JsonObject().put(prefix, block);
    }

    /** Positive business response: {@code requestTrxNo} + {@code positiveApproval}. */
    public static JsonObject approve(String requestTrxNo, JsonObject extra) {
        JsonObject approval = new JsonObject().put("approvalInd", "Y");
        if (extra != null) {
            approval.mergeIn(extra);
        }
        return new JsonObject().put("requestTrxNo", requestTrxNo).put("positiveApproval", approval);
    }

    /** Negative business response (element names assumed by analogy, see open questions). */
    public static JsonObject reject(String requestTrxNo, String reasonCode, String reasonText) {
        JsonObject rejection = new JsonObject().put("approvalInd", "N").put("rejectReasonCode", reasonCode);
        if (reasonText != null) {
            rejection.put("rejectReasonText", reasonText);
        }
        return new JsonObject().put("requestTrxNo", requestTrxNo).put("negativeApproval", rejection);
    }

    /** Protocol timestamp: Israel local time with offset, second precision (as in the trace). */
    public static String timestamp(LocalDateTime utc) {
        return utc.atOffset(ZoneOffset.UTC).atZoneSameInstant(ISRAEL).toOffsetDateTime()
            .truncatedTo(ChronoUnit.SECONDS).toString();
    }

    /** Israel local time without offset, as the trace's routeDateTime ({@code 2026-09-24T16:50:08}). */
    public static String localNow() {
        return java.time.LocalDateTime.now(ISRAEL).truncatedTo(ChronoUnit.SECONDS).toString();
    }

    public static String now() {
        return timestamp(LocalDateTime.now(ZoneOffset.UTC));
    }

    /** Parses a protocol timestamp (with or without offset; no offset means Israel time) to UTC. */
    public static LocalDateTime parseUtc(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value.trim()).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
        } catch (DateTimeParseException e) {
            return LocalDateTime.parse(value.trim()).atZone(ISRAEL).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        }
    }
}
