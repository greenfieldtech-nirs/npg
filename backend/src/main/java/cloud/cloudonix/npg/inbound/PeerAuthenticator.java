package cloud.cloudonix.npg.inbound;

import cloud.cloudonix.npg.audit.XmlTransportMetadata;
import cloud.cloudonix.npg.config.Env;
import cloud.cloudonix.npg.protocol.ProtocolHeader;
import cloud.cloudonix.npg.security.ClientAddress;
import io.vertx.core.Future;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;

import java.util.Arrays;
import java.util.List;

/**
 * Decides whether an inbound message may be accepted from the claimed sender.
 *
 * <p>STRICT (the production default): the receiver must be our local operator code, and the sender
 * must be a known, active, fully configured peer whose request proves its identity with a
 * credential configured on one of its endpoints — a verified mTLS client-certificate subject
 * ({@code tls_subject}) and/or a source address in {@code ip_allowlist}. A peer with no credential
 * configured cannot be authenticated and is refused.
 *
 * <p>PERMISSIVE (dev/test default): everything is accepted, as before peers were configured.
 */
public class PeerAuthenticator {

    public enum Mode { STRICT, PERMISSIVE }

    public record Decision(boolean allowed, String nackCode, String reason) {
        static Decision allow() {
            return new Decision(true, null, null);
        }

        static Decision deny(String nackCode, String reason) {
            return new Decision(false, nackCode, reason);
        }
    }

    private final Mode mode;

    public PeerAuthenticator(Mode mode) {
        this.mode = mode;
    }

    public static PeerAuthenticator fromEnvironment() {
        String configured = Env.get("INBOUND_AUTH_MODE", Env.isDevMode() ? "permissive" : "strict");
        return new PeerAuthenticator(Mode.valueOf(configured.trim().toUpperCase(java.util.Locale.ROOT)));
    }

    public Mode mode() {
        return mode;
    }

    public Future<Decision> authorize(SqlClient db, ProtocolHeader header, XmlTransportMetadata transport) {
        if (mode == Mode.PERMISSIVE) {
            return Future.succeededFuture(Decision.allow());
        }
        return db.preparedQuery("""
                SELECT operator_code, is_local, is_active, requires_configuration FROM np_operators
                WHERE operator_code IN (?, ?)
                """)
            .execute(Tuple.of(header.senderOperator(), header.receiverOperator()))
            .compose(rows -> {
                Row sender = null;
                Row receiver = null;
                for (Row row : rows) {
                    if (row.getString("operator_code").equals(header.senderOperator())) {
                        sender = row;
                    }
                    if (row.getString("operator_code").equals(header.receiverOperator())) {
                        receiver = row;
                    }
                }
                if (receiver == null || !Boolean.TRUE.equals(receiver.getBoolean("is_local"))) {
                    return Future.succeededFuture(Decision.deny("NACK_RECEIVER_NOT_FOUND",
                        "Receiver " + header.receiverOperator() + " is not this gateway's operator"));
                }
                if (sender == null || Boolean.TRUE.equals(sender.getBoolean("is_local"))
                    || !Boolean.TRUE.equals(sender.getBoolean("is_active"))
                    || Boolean.TRUE.equals(sender.getBoolean("requires_configuration"))) {
                    return Future.succeededFuture(Decision.deny("NACK_UNAUTHORIZED_SENDER",
                        "Sender " + header.senderOperator() + " is not a configured, active peer"));
                }
                return credentialsOf(db, header.senderOperator())
                    .map(credentials -> verify(credentials, header.senderOperator(), transport));
            });
    }

    private record Credential(String tlsSubject, List<String> ipAllowlist) {
    }

    private Future<List<Credential>> credentialsOf(SqlClient db, String operatorCode) {
        return db.preparedQuery("""
                SELECT e.tls_subject, e.ip_allowlist FROM np_operator_endpoints e
                JOIN np_operators o ON o.id = e.operator_id
                WHERE o.operator_code = ? AND e.is_active = TRUE
                """)
            .execute(Tuple.of(operatorCode))
            .map(rows -> {
                List<Credential> credentials = new java.util.ArrayList<>();
                for (Row row : rows) {
                    String subject = blankToNull(row.getString("tls_subject"));
                    String allowlist = blankToNull(row.getString("ip_allowlist"));
                    if (subject != null || allowlist != null) {
                        credentials.add(new Credential(subject,
                            allowlist != null ? Arrays.asList(allowlist.split("[,\\s]+")) : List.of()));
                    }
                }
                return credentials;
            });
    }

    /** Some endpoint credential must fully match: every condition it configures must hold. */
    private Decision verify(List<Credential> credentials, String sender, XmlTransportMetadata transport) {
        if (credentials.isEmpty()) {
            return Decision.deny("NACK_UNAUTHORIZED_SENDER",
                "No mTLS subject or IP allowlist configured for " + sender + "; it cannot be authenticated");
        }
        for (Credential c : credentials) {
            boolean subjectOk = c.tlsSubject() == null || c.tlsSubject().equals(transport.tlsSubject());
            boolean ipOk = c.ipAllowlist().isEmpty() || ClientAddress.matchesAny(transport.remoteIp(), c.ipAllowlist());
            if (subjectOk && ipOk) {
                return Decision.allow();
            }
        }
        return Decision.deny("NACK_UNAUTHORIZED_SENDER",
            "Request does not present the credential configured for " + sender);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
