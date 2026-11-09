package fr.nivcoo.kraftlink.api;

import java.util.Map;

public interface LinkPollOperation {

    String name();

    default int intervalSeconds() {
        return 5;
    }

    Map<String, Object> request();

    void receive(Map<String, Object> data);

    default void rejected(int status) {
    }
}
