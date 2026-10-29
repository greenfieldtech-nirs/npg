package cloud.cloudonix.npg.retry;

import cloud.cloudonix.npg.config.Env;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Decides what to do with an outbound message whose delivery outcome is unknown (the dispatcher
 * stopped after the send began). Only message types whose duplicate delivery is harmless are
 * re-sent automatically; everything else goes to manual review so the NOC can confirm with the
 * peer first.
 *
 * <p>The default safe set is read-only queries and idempotent status notifications. The official
 * specification does not state which messages tolerate duplicates (docs/protocol/open-questions.md),
 * so the set is configurable via OUTBOUND_SAFE_RESEND_TYPES (comma-separated message types).
 */
public final class UnknownDeliveryPolicy {

    static final String DEFAULT_SAFE_TYPES =
        "Check,Inquire_number,Inquire_number_response,Down_system,Up_system,DB_synch_request";

    private final Set<String> safeTypes;

    public UnknownDeliveryPolicy(Set<String> safeTypes) {
        this.safeTypes = Set.copyOf(safeTypes);
    }

    public static UnknownDeliveryPolicy fromEnvironment() {
        String configured = Env.get("OUTBOUND_SAFE_RESEND_TYPES", DEFAULT_SAFE_TYPES);
        return new UnknownDeliveryPolicy(Arrays.stream(configured.split(","))
            .map(String::trim).filter(t -> !t.isEmpty()).collect(Collectors.toSet()));
    }

    public boolean isSafeToResend(String messageType) {
        return safeTypes.contains(messageType);
    }
}
