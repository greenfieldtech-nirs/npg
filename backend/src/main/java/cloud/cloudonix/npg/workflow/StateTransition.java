package cloud.cloudonix.npg.workflow;

public record StateTransition(String workflowType, String fromState, String toState, String messageType) {
}
