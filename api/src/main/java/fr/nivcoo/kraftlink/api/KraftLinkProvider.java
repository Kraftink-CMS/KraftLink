package fr.nivcoo.kraftlink.api;

import java.util.Optional;

public interface KraftLinkProvider {

    Optional<KraftLinkApi> api();
}
