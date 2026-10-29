package fr.nivcoo.kraftlink.platform;

import fr.nivcoo.kraftlink.model.Delivery;
import fr.nivcoo.kraftlink.model.Heartbeat;

import java.util.concurrent.CompletionStage;
import java.util.function.BooleanSupplier;

public interface ServerPlatform {

    CompletionStage<Heartbeat> snapshot();

    CompletionStage<ExecutionResult> execute(Delivery delivery, boolean requireOnline, BooleanSupplier authorized);
}
