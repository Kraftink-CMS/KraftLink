package fr.nivcoo.kraftlink.platform;

public final class ExecutionResult {

    private final State state;
    private final String error;

    public ExecutionResult(State state, String error) {
        this.state = state;
        this.error = error;
    }

    public State state() {
        return state;
    }

    public String error() {
        return error;
    }

    public enum State {
        EXECUTED,
        FAILED,
        DEFERRED
    }

    public static ExecutionResult executed() {
        return new ExecutionResult(State.EXECUTED, null);
    }

    public static ExecutionResult deferred(String reason) {
        return new ExecutionResult(State.DEFERRED, reason);
    }
}
