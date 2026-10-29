package cloud.cloudonix.npg.workflow;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Every workflow state machine, keyed by workflow type and the role this gateway plays in it
 * (docs/protocol/state-machines.md). The same protocol process is a different machine for the
 * recipient (gaining operator), the donor (losing operator) and a third party.
 *
 * <p>Universal rules: any non-terminal state may escalate to MANUAL_REVIEW or FAILED; only a manual
 * (audited) transition leaves MANUAL_REVIEW.
 */
public final class StateMachines {

    public static final String MANUAL_REVIEW = "MANUAL_REVIEW";
    public static final String FAILED = "FAILED";

    // workflow types
    public static final String PORTING = "PORTING";
    public static final String PUBLICATION = "PUBLICATION";
    public static final String NUMBER_RETURN = "NUMBER_RETURN";
    public static final String NUMBER_INQUIRY = "NUMBER_INQUIRY";
    public static final String MAINTENANCE = "MAINTENANCE";
    public static final String DATA_SYNCHRONIZATION = "DATA_SYNCHRONIZATION";

    // local roles
    public static final String RECIPIENT = "RECIPIENT";
    public static final String DONOR = "DONOR";
    public static final String OBSERVER = "OBSERVER";
    public static final String CURRENT_OPERATOR = "CURRENT_OPERATOR";
    public static final String ORIGINAL_OPERATOR = "ORIGINAL_OPERATOR";
    public static final String REQUESTER = "REQUESTER";
    public static final String RESPONDER = "RESPONDER";
    public static final String ANNOUNCER = "ANNOUNCER";

    /** One state machine: allowed transitions and terminal states. */
    public static final class Machine {
        private final String type;
        private final String role;
        private final String initial;
        private final Map<String, Set<String>> transitions = new LinkedHashMap<>();
        private final Set<String> terminal = new LinkedHashSet<>();

        private Machine(String type, String role, String initial) {
            this.type = type;
            this.role = role;
            this.initial = initial;
        }

        private Machine edge(String from, String... to) {
            transitions.computeIfAbsent(from, k -> new LinkedHashSet<>()).addAll(Set.of(to));
            return this;
        }

        private Machine terminal(String... states) {
            terminal.addAll(Set.of(states));
            return this;
        }

        public String type() {
            return type;
        }

        public String role() {
            return role;
        }

        public String initial() {
            return initial;
        }

        public boolean isTerminal(String state) {
            return terminal.contains(state);
        }

        public Set<String> states() {
            Set<String> all = new LinkedHashSet<>();
            all.add(initial);
            transitions.forEach((from, to) -> {
                all.add(from);
                all.addAll(to);
            });
            all.addAll(terminal);
            all.add(MANUAL_REVIEW);
            all.add(FAILED);
            return all;
        }

        /** Automatic (non-manual) transition check. */
        public boolean allows(String from, String to) {
            if (MANUAL_REVIEW.equals(from)) {
                return false;
            }
            if ((MANUAL_REVIEW.equals(to) || FAILED.equals(to)) && !isTerminal(from) && !FAILED.equals(from)) {
                return true;
            }
            return transitions.getOrDefault(from, Set.of()).contains(to);
        }
    }

    private static final Map<String, Machine> MACHINES = new LinkedHashMap<>();

    static {
        register(new Machine(PORTING, RECIPIENT, "NEW")
            .edge("NEW", "CHECK_SENT")
            .edge("CHECK_SENT", "CHECK_APPROVED", "CHECK_REJECTED")
            .edge("CHECK_APPROVED", "REQUEST_SENT")
            .edge("REQUEST_SENT", "REQUEST_APPROVED", "REQUEST_REJECTED")
            .edge("REQUEST_APPROVED", "KD_UPDATE_SENT", "PORT_SCHEDULED", "CANCEL_SENT")
            .edge("KD_UPDATE_SENT", "PORT_SCHEDULED")
            .edge("PORT_SCHEDULED", "UPDATE_SENT", "CANCEL_SENT", "EXECUTION_SENT")
            .edge("UPDATE_SENT", "PORT_SCHEDULED")
            .edge("CANCEL_SENT", "CANCELLED", "PORT_SCHEDULED")
            .edge("EXECUTION_SENT", "EXECUTION_CONFIRMED")
            .edge("EXECUTION_CONFIRMED", "PUBLISH_SENT")
            .edge("PUBLISH_SENT", "PUBLISHED", "PARTIALLY_PUBLISHED", "PUBLISH_FAILED")
            .edge("PARTIALLY_PUBLISHED", "PUBLISHED", "PUBLISH_FAILED")
            .edge("PUBLISHED", "COMPLETED")
            .edge("COMPLETED", "CANCEL_PUBLISH_SENT")
            .edge("CANCEL_PUBLISH_SENT", "PUBLICATION_CANCELLED")
            .terminal("CHECK_REJECTED", "REQUEST_REJECTED", "CANCELLED", "PUBLICATION_CANCELLED"));

        register(new Machine(PORTING, DONOR, "CHECK_RECEIVED")
            .edge("CHECK_RECEIVED", "CHECK_APPROVED", "CHECK_REJECTED")
            .edge("CHECK_APPROVED", "REQUEST_RECEIVED", "CANCELLED")
            .edge("REQUEST_RECEIVED", "PORT_SCHEDULED", "REQUEST_REJECTED", "CANCELLED")
            .edge("PORT_SCHEDULED", "CANCELLED", "COMPLETED")
            .terminal("CHECK_REJECTED", "REQUEST_REJECTED", "CANCELLED", "COMPLETED"));

        register(new Machine(PUBLICATION, OBSERVER, "PUBLICATION_RECEIVED")
            .edge("PUBLICATION_RECEIVED", "PUBLICATION_APPLIED", "PUBLICATION_REJECTED")
            .edge("PUBLICATION_APPLIED", "PUBLICATION_CANCELLED")
            .terminal("PUBLICATION_REJECTED", "PUBLICATION_CANCELLED"));

        register(new Machine(NUMBER_RETURN, CURRENT_OPERATOR, "NUMBER_DISCONNECTED")
            .edge("NUMBER_DISCONNECTED", "RETURN_COOLING_PERIOD")
            .edge("RETURN_COOLING_PERIOD", "RETURN_SENT")
            .edge("RETURN_SENT", "RETURN_APPROVED", "RETURN_REJECTED")
            .edge("RETURN_APPROVED", "RETURN_PUBLISH_SENT")
            .edge("RETURN_PUBLISH_SENT", "RETURNED", "PARTIALLY_PUBLISHED", "PUBLISH_FAILED")
            .edge("PARTIALLY_PUBLISHED", "RETURNED", "PUBLISH_FAILED")
            .terminal("RETURN_REJECTED", "RETURNED"));

        register(new Machine(NUMBER_RETURN, ORIGINAL_OPERATOR, "RETURN_RECEIVED")
            .edge("RETURN_RECEIVED", "RETURN_ACCEPTED", "RETURN_REJECTED")
            .terminal("RETURN_ACCEPTED", "RETURN_REJECTED"));

        register(new Machine(NUMBER_INQUIRY, REQUESTER, "INQUIRY_SENT")
            .edge("INQUIRY_SENT", "INQUIRY_COMPLETED")
            .terminal("INQUIRY_COMPLETED"));

        register(new Machine(NUMBER_INQUIRY, RESPONDER, "INQUIRY_RECEIVED")
            .edge("INQUIRY_RECEIVED", "INQUIRY_ANSWERED")
            .terminal("INQUIRY_ANSWERED"));

        register(new Machine(MAINTENANCE, OBSERVER, "OPERATOR_MARKED_DOWN")
            .edge("OPERATOR_MARKED_DOWN", "OPERATOR_MARKED_UP")
            .terminal("OPERATOR_MARKED_UP"));

        register(new Machine(MAINTENANCE, ANNOUNCER, "DOWN_ANNOUNCED")
            .edge("DOWN_ANNOUNCED", "UP_ANNOUNCED")
            .terminal("UP_ANNOUNCED"));

        register(new Machine(DATA_SYNCHRONIZATION, REQUESTER, "SYNC_REQUESTED")
            .edge("SYNC_REQUESTED", "SYNC_COMPLETED", "SYNC_REJECTED")
            .terminal("SYNC_COMPLETED", "SYNC_REJECTED"));

        register(new Machine(DATA_SYNCHRONIZATION, RESPONDER, "SYNC_RECEIVED")
            .edge("SYNC_RECEIVED", "SYNC_ANSWERED")
            .terminal("SYNC_ANSWERED"));
    }

    private StateMachines() {
    }

    private static void register(Machine machine) {
        MACHINES.put(key(machine.type(), machine.role()), machine);
    }

    private static String key(String type, String role) {
        return type + "/" + role;
    }

    public static Optional<Machine> find(String type, String role) {
        return Optional.ofNullable(MACHINES.get(key(type, role)));
    }

    public static Machine of(String type, String role) {
        return find(type, role).orElseThrow(() -> new IllegalArgumentException("No state machine for " + key(type, role)));
    }

    public static java.util.Collection<Machine> all() {
        return MACHINES.values();
    }

    /**
     * States in which a workflow needs nothing more: every machine's terminal states plus the
     * "done unless someone acts" states (a completed port can still have its publication cancelled,
     * an applied publication can still be withdrawn by its publisher).
     */
    public static Set<String> finishedStates() {
        Set<String> finished = new LinkedHashSet<>();
        MACHINES.values().forEach(m -> m.states().stream().filter(m::isTerminal).forEach(finished::add));
        finished.addAll(Set.of(FAILED, "COMPLETED", "PUBLICATION_APPLIED"));
        return finished;
    }
}
