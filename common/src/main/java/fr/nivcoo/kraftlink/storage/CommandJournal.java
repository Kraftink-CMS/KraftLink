package fr.nivcoo.kraftlink.storage;

import fr.nivcoo.kraftlink.config.MainConfig;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class CommandJournal implements AutoCloseable {

    private final FileChannel channel;
    private final FileLock lock;
    private final Map<String, Entry> entries = new HashMap<>();

    public CommandJournal(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        lock = channel.tryLock();
        if (lock == null) {
            channel.close();
            throw new IOException("The KraftLink journal is already in use");
        }
        try {
            load();
            channel.position(channel.size());
            for (Entry entry : new ArrayList<>(entries.values())) {
                if (entry.state == State.EXECUTING) {
                    write(entry.key, State.UNCERTAIN, entry.commandHash, "Interrupted during command execution");
                }
            }
        } catch (IOException | RuntimeException error) {
            lock.release();
            channel.close();
            throw error;
        }
    }

    public synchronized Entry get(String key) {
        return entries.get(key);
    }

    public synchronized List<Entry> uncertain() {
        return entries.values().stream()
                .filter(entry -> entry.state == State.UNCERTAIN || entry.state == State.FAILED)
                .collect(Collectors.toList());
    }

    public synchronized void write(String key, State state, String commandHash, String error) throws IOException {
        Entry entry = new Entry(key, state, commandHash, System.currentTimeMillis(), error);
        String payload = MainConfig.JSON.toJson(entry);
        ByteBuffer bytes = StandardCharsets.UTF_8.encode(MainConfig.JSON.toJson(new Envelope(payload, hash(payload))) + "\n");
        while (bytes.hasRemaining()) {
            channel.write(bytes);
        }
        channel.force(true);
        entries.put(key, entry);
    }

    public synchronized boolean resolve(String key, boolean delivered) throws IOException {
        Entry entry = entries.get(key);
        if (entry == null || (entry.state != State.UNCERTAIN && entry.state != State.FAILED)) {
            return false;
        }
        write(key, delivered ? State.EXECUTED : State.RECEIVED, entry.commandHash, null);
        return true;
    }

    private void load() throws IOException {
        long completed = 0;
        channel.position(0);
        ByteBuffer buffer = ByteBuffer.allocate(8192);
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        while (channel.read(buffer) != -1) {
            buffer.flip();
            while (buffer.hasRemaining()) {
                int value = buffer.get() & 0xff;
                if (value != '\n') {
                    line.write(value);
                    if (line.size() > 16_384) {
                        throw new IOException("Invalid KraftLink journal record size");
                    }
                    continue;
                }
                String text = new String(line.toByteArray(), StandardCharsets.UTF_8);
                completed += line.size() + 1L;
                line.reset();
                Envelope envelope = MainConfig.JSON.fromJson(text, Envelope.class);
                if (envelope == null || envelope.payload == null || envelope.checksum == null
                        || !MessageDigest.isEqual(hash(envelope.payload).getBytes(StandardCharsets.US_ASCII),
                        envelope.checksum.getBytes(StandardCharsets.US_ASCII))) {
                    throw new IOException("KraftLink journal checksum mismatch");
                }
                Entry entry = MainConfig.JSON.fromJson(envelope.payload, Entry.class);
                if (entry == null || entry.key == null || entry.state == null || entry.commandHash == null) {
                    throw new IOException("Invalid KraftLink journal entry");
                }
                entries.put(entry.key, entry);
            }
            buffer.clear();
        }
        if (channel.size() != completed) {
            channel.truncate(completed);
            channel.force(true);
        }
    }

    public static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte valueByte : digest) {
                hex.append(Character.forDigit((valueByte >>> 4) & 0xf, 16));
                hex.append(Character.forDigit(valueByte & 0xf, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    @Override
    public synchronized void close() throws IOException {
        lock.release();
        channel.close();
    }

    public enum State {
        RECEIVED,
        EXECUTING,
        EXECUTED,
        ACKED,
        FAILED,
        UNCERTAIN
    }

    public static final class Entry {

        private final String key;
        private final State state;
        private final String commandHash;
        private final long updatedAt;
        private final String error;

        public Entry(String key, State state, String commandHash, long updatedAt, String error) {
            this.key = key;
            this.state = state;
            this.commandHash = commandHash;
            this.updatedAt = updatedAt;
            this.error = error;
        }

        public String key() {
            return key;
        }

        public State state() {
            return state;
        }

        public String commandHash() {
            return commandHash;
        }

        public long updatedAt() {
            return updatedAt;
        }

        public String error() {
            return error;
        }
    }

    private static final class Envelope {

        private final String payload;
        private final String checksum;

        private Envelope(String payload, String checksum) {
            this.payload = payload;
            this.checksum = checksum;
        }
    }
}
