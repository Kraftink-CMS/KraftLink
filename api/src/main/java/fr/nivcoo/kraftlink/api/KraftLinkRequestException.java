package fr.nivcoo.kraftlink.api;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class KraftLinkRequestException extends IOException {

    private static final long serialVersionUID = 1L;

    private final int status;
    private final Map<String, List<String>> errors;

    public KraftLinkRequestException(int status) {
        this(status, Collections.emptyMap());
    }

    public KraftLinkRequestException(int status, Map<String, List<String>> errors) {
        super(status == 0 ? "KraftLink request failed" : "KraftLink request failed (HTTP " + status + ")");
        this.status = status;
        Map<String, List<String>> copy = new LinkedHashMap<>();
        errors.forEach((key, messages) ->
                copy.put(key, Collections.unmodifiableList(new ArrayList<>(messages))));
        this.errors = Collections.unmodifiableMap(copy);
    }

    public int status() {
        return status;
    }

    public Map<String, List<String>> errors() {
        return errors;
    }
}
