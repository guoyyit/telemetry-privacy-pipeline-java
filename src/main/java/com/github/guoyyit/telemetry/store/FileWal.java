package com.github.guoyyit.telemetry.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.guoyyit.telemetry.domain.TelemetryEvent;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * File-based WAL. Each batch is a single JSON file written via temp-file + atomic
 * rename, so a crash can never leave a partially written batch file. Batches exist in
 * exactly one of two states, distinguishable by file suffix:
 * <ul>
 *   <li>{@code .pending}   — written but not committed; discarded on recovery</li>
 *   <li>{@code .committed} — fully committed with a version; replayed on recovery</li>
 * </ul>
 */
@Component
public class FileWal implements Wal {

    private static final String PENDING_SUFFIX = ".pending";
    private static final String COMMITTED_SUFFIX = ".committed";

    private final Path dir;
    private final ObjectMapper mapper;

    public FileWal(Path dir) {
        this.dir = dir;
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private record WalRecord(String batchId, Long version, List<TelemetryEvent> events) {
    }

    @Override
    public void beginBatch(String batchId, List<TelemetryEvent> events) {
        writeAtomically(pendingFile(batchId), new WalRecord(batchId, null, events));
    }

    @Override
    public void markCommitted(String batchId, long version) {
        Path pending = pendingFile(batchId);
        try {
            WalRecord record = mapper.readValue(pending.toFile(), WalRecord.class);
            writeAtomically(committedFile(batchId), new WalRecord(batchId, version, record.events()));
            Files.deleteIfExists(pending);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public ReplayResult replay() {
        List<CommittedBatch> committed = new ArrayList<>();
        List<String> discarded = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> all = files.toList();
            for (Path p : all) {
                String name = p.getFileName().toString();
                if (name.endsWith(COMMITTED_SUFFIX)) {
                    WalRecord record = mapper.readValue(p.toFile(), WalRecord.class);
                    committed.add(new CommittedBatch(record.batchId(), record.version(), record.events()));
                } else if (name.endsWith(PENDING_SUFFIX)) {
                    discarded.add(name.substring(0, name.length() - PENDING_SUFFIX.length()));
                    Files.deleteIfExists(p);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        committed.sort(Comparator.comparingLong(CommittedBatch::version));
        return new ReplayResult(List.copyOf(committed), List.copyOf(discarded));
    }

    /** Remove a batch file after its data has been superseded (e.g. retention purge). */
    public void deleteBatch(String batchId) {
        try {
            Files.deleteIfExists(committedFile(batchId));
            Files.deleteIfExists(pendingFile(batchId));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path pendingFile(String batchId) {
        return dir.resolve(sanitize(batchId) + PENDING_SUFFIX);
    }

    private Path committedFile(String batchId) {
        return dir.resolve(sanitize(batchId) + COMMITTED_SUFFIX);
    }

    private static String sanitize(String batchId) {
        return batchId.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private void writeAtomically(Path target, WalRecord record) {
        try {
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            mapper.writeValue(tmp.toFile(), record);
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
