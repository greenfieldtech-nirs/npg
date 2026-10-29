package cloud.cloudonix.npg.worker;

/**
 * Processing failure that retrying cannot fix (malformed stored message, no handler, a business
 * precondition that will never hold). The inbox item fails immediately and a manual task is opened.
 */
public class PermanentProcessingException extends RuntimeException {

    public PermanentProcessingException(String message) {
        super(message);
    }
}
