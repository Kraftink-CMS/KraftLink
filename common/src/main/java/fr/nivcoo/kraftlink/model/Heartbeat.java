package fr.nivcoo.kraftlink.model;

import java.util.List;

public final class Heartbeat {

    public static final int MAX_PLAYER_SAMPLE = 2000;

    private final int protocol;
    private final String game;
    private final String software;
    private final String version;
    private final List<PlayerIdentity> players;
    private final Integer playersOnline;
    private final int maxPlayers;
    private final long uptimeSeconds;
    private final List<Double> tps;
    private final Memory memory;
    private final List<WorldStatus> worlds;

    public Heartbeat(int protocol, String game, String software, String version, List<PlayerIdentity> players,
                     int maxPlayers, long uptimeSeconds, List<Double> tps, Memory memory, List<WorldStatus> worlds) {
        this(protocol, game, software, version, players, maxPlayers, uptimeSeconds, tps, memory, worlds, null);
    }

    private Heartbeat(int protocol, String game, String software, String version, List<PlayerIdentity> players,
                      int maxPlayers, long uptimeSeconds, List<Double> tps, Memory memory, List<WorldStatus> worlds,
                      Integer playersOnline) {
        this.protocol = protocol;
        this.game = game;
        this.software = software;
        this.version = version;
        this.players = players;
        this.playersOnline = playersOnline;
        this.maxPlayers = maxPlayers;
        this.uptimeSeconds = uptimeSeconds;
        this.tps = tps;
        this.memory = memory;
        this.worlds = worlds;
    }

    public int protocol() {
        return protocol;
    }

    public String game() {
        return game;
    }

    public String software() {
        return software;
    }

    public String version() {
        return version;
    }

    public List<PlayerIdentity> players() {
        return players;
    }

    public Heartbeat withPlayerSample(List<PlayerIdentity> sample) {
        if (sample == players) {
            return this;
        }
        return new Heartbeat(protocol, game, software, version, sample, maxPlayers, uptimeSeconds, tps, memory, worlds,
                playersOnline == null ? players.size() : playersOnline);
    }

    public int maxPlayers() {
        return maxPlayers;
    }

    public long uptimeSeconds() {
        return uptimeSeconds;
    }

    public List<Double> tps() {
        return tps;
    }

    public Memory memory() {
        return memory;
    }

    public List<WorldStatus> worlds() {
        return worlds;
    }

    public static final class PlayerIdentity {

        private final String uuid;
        private final String name;

        public PlayerIdentity(String uuid, String name) {
            this.uuid = uuid;
            this.name = name;
        }

        public String uuid() {
            return uuid;
        }

        public String name() {
            return name;
        }
    }

    public static final class Memory {

        private final long used;
        private final long max;

        public Memory(long used, long max) {
            this.used = used;
            this.max = max;
        }

        public long used() {
            return used;
        }

        public long max() {
            return max;
        }
    }

    public static final class WorldStatus {

        private final String name;
        private final int players;

        public WorldStatus(String name, int players) {
            this.name = name;
            this.players = players;
        }

        public String name() {
            return name;
        }

        public int players() {
            return players;
        }
    }
}
