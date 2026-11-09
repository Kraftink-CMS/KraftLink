package fr.nivcoo.kraftlink.service;

import fr.nivcoo.kraftlink.model.Heartbeat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

final class PresenceSample<T, K extends Comparable<? super K>> {

    private final Function<T, K> key;
    private K cursor;

    PresenceSample(Function<T, K> key) {
        this.key = key;
    }

    List<T> next(List<T> players) {
        if (players.size() <= Heartbeat.MAX_PLAYER_SAMPLE) {
            cursor = null;
            return players;
        }
        List<T> ordered = new ArrayList<>(players);
        ordered.sort(Comparator.comparing(key));
        int start = 0;
        while (cursor != null && start < ordered.size() && key.apply(ordered.get(start)).compareTo(cursor) <= 0) {
            start++;
        }
        if (start == ordered.size()) {
            start = 0;
        }
        int end = Math.min(start + Heartbeat.MAX_PLAYER_SAMPLE, ordered.size());
        cursor = key.apply(ordered.get(end - 1));
        return ordered.subList(start, end);
    }
}
