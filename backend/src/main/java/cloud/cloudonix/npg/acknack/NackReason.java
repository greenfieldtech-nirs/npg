package cloud.cloudonix.npg.acknack;

public record NackReason(String code, String field, String message, String layer, boolean retrySafe) {
}
