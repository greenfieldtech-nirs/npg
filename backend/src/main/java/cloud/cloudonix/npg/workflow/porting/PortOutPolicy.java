package cloud.cloudonix.npg.workflow.porting;

import cloud.cloudonix.npg.config.Env;
import cloud.cloudonix.npg.number.NumberOwnershipService;
import cloud.cloudonix.npg.number.NumberRanges;
import cloud.cloudonix.npg.workflow.StateMachines;
import cloud.cloudonix.npg.workflow.Workflow;
import io.vertx.core.Future;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * How this gateway, as donor, answers a recipient's Check and Request.
 *
 * <p>Hard rules always apply: a number recorded as served by another operator is rejected, as is a
 * second concurrent port-out of the same number, and a Request must name a sensible port time.
 * Beyond that the decision belongs to the operator's business (customer identity, contract, debt) —
 * data this gateway does not hold, since BSS integration is out of scope (docs/01). The configured
 * mode (PORT_OUT_DECISION_MODE) therefore either approves automatically or hands the decision to
 * the NOC as a manual task, decided through the management API.
 */
public class PortOutPolicy {

    public enum Mode { AUTO_APPROVE, MANUAL }

    public enum Outcome { APPROVE, REJECT, MANUAL }

    public record Decision(Outcome outcome, String reasonCode, String reasonText) {
        static Decision approve() {
            return new Decision(Outcome.APPROVE, null, null);
        }

        static Decision manual() {
            return new Decision(Outcome.MANUAL, null, null);
        }

        /** A NOC decision whatever the mode, with the reason it cannot be automatic. */
        static Decision manual(String why) {
            return new Decision(Outcome.MANUAL, null, why);
        }

        static Decision reject(String code, String text) {
            return new Decision(Outcome.REJECT, code, text);
        }
    }

    static final Duration MAX_PAST = Duration.ofHours(1);
    static final Duration MAX_AHEAD = Duration.ofDays(90);

    // finished porting states; COMPLETED is listed explicitly because a completed port-in can still
    // have its publication cancelled, so it is not terminal in the recipient machine
    private static final Set<String> PORTING_TERMINAL = Stream.concat(Stream.of(
            StateMachines.of(StateMachines.PORTING, StateMachines.RECIPIENT),
            StateMachines.of(StateMachines.PORTING, StateMachines.DONOR))
        .flatMap(m -> m.states().stream().filter(m::isTerminal)),
            Stream.of("COMPLETED", StateMachines.FAILED))
        .collect(Collectors.toUnmodifiableSet());

    private final Mode mode;

    public PortOutPolicy(Mode mode) {
        this.mode = mode;
    }

    public static PortOutPolicy fromEnvironment() {
        String configured = Env.get("PORT_OUT_DECISION_MODE", Env.isDevMode() ? "AUTO_APPROVE" : "MANUAL");
        return new PortOutPolicy(Mode.valueOf(configured.trim().toUpperCase(Locale.ROOT)));
    }

    public Mode mode() {
        return mode;
    }

    public Future<Decision> onCheck(SqlClient tx, Workflow wf, long localOperatorId) {
        return NumberOwnershipService.currentOperatorId(tx, wf.phoneNumber())
            // never ported: the range holder serves it
            .compose(owner -> owner != null ? Future.succeededFuture(owner) : NumberRanges.nativeOperator(tx, wf.phoneNumber()))
            .compose(owner -> {
            if (owner != null && owner != localOperatorId) {
                return Future.succeededFuture(Decision.reject("REJECTED_NUMBER_NOT_FOUND",
                    "The number is not served by this operator"));
            }
            return otherPortInProgress(tx, wf).map(busy -> {
                if (busy) {
                    return Decision.reject("REJECTED_OTHER", "Another porting process for this number is in progress");
                }
                // no ownership record and no number range: we cannot show the number is ours, so never
                // approve automatically (fail closed); the NOC decides
                return owner == null
                    ? Decision.manual("Neither our records nor the configured number ranges show that we serve this number.")
                    : byMode();
            });
        });
    }

    public Decision onRequest(LocalDateTime requestedPortTimeUtc) {
        String timeProblem = portTimeProblem(requestedPortTimeUtc);
        return timeProblem != null ? Decision.reject("REJECTED_INVALID_PORT_TIME", timeProblem) : byMode();
    }

    /** Null when the port time is acceptable, otherwise why not. */
    public static String portTimeProblem(LocalDateTime requestedPortTimeUtc) {
        if (requestedPortTimeUtc == null) {
            return "No valid port time was given";
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (requestedPortTimeUtc.isBefore(now.minus(MAX_PAST))) {
            return "The port time is in the past";
        }
        if (requestedPortTimeUtc.isAfter(now.plus(MAX_AHEAD))) {
            return "The port time is more than " + MAX_AHEAD.toDays() + " days ahead";
        }
        return null;
    }

    private Decision byMode() {
        return mode == Mode.AUTO_APPROVE ? Decision.approve() : Decision.manual();
    }

    private Future<Boolean> otherPortInProgress(SqlClient tx, Workflow wf) {
        String placeholders = String.join(",", PORTING_TERMINAL.stream().map(s -> "?").toList());
        Tuple params = Tuple.of(wf.phoneNumber(), wf.id());
        PORTING_TERMINAL.forEach(params::addValue);
        return tx.preparedQuery("SELECT COUNT(*) AS c FROM np_workflows WHERE workflow_type = 'PORTING' "
                + "AND phone_number = ? AND id <> ? AND current_state NOT IN (" + placeholders + ")")
            .execute(params)
            .map(rows -> rows.iterator().next().getLong("c") > 0);
    }
}
