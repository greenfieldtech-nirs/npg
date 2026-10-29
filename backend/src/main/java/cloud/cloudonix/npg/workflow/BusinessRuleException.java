package cloud.cloudonix.npg.workflow;

/**
 * A requested business action is not allowed (wrong state, unknown operator, invalid input).
 * Carries the HTTP status the management API should answer with.
 */
public class BusinessRuleException extends RuntimeException {

    private final int status;

    public BusinessRuleException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }

    public static BusinessRuleException badRequest(String message) {
        return new BusinessRuleException(400, message);
    }

    public static BusinessRuleException conflict(String message) {
        return new BusinessRuleException(409, message);
    }

    public static BusinessRuleException notFound(String message) {
        return new BusinessRuleException(404, message);
    }
}
