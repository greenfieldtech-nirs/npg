package cloud.cloudonix.npg.domain;

public record Operator(
    Long id,
    String operatorCode,
    String operatorName,
    String licenseType,
    boolean active,
    String availabilityStatus
) {
}
