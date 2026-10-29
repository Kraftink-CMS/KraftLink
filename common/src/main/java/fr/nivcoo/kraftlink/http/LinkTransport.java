package fr.nivcoo.kraftlink.http;

import fr.nivcoo.kraftlink.config.MainConfig;
import fr.nivcoo.kraftlink.model.Delivery;
import fr.nivcoo.kraftlink.model.Heartbeat;

import java.io.IOException;
import java.util.List;

public interface LinkTransport extends AutoCloseable {

    void heartbeat(MainConfig config, Heartbeat heartbeat) throws IOException, InterruptedException;

    List<Delivery> claim(MainConfig config, MainConfig.Channel channel, Heartbeat snapshot)
            throws IOException, InterruptedException;

    void acknowledge(MainConfig config, MainConfig.Channel channel, Delivery delivery, String state, String error)
            throws IOException, InterruptedException;

    @Override
    default void close() {
    }
}
