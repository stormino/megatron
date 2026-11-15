package io.github.stormino.megatron.download;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * JSON file next to the {@code .part} recording which chunks are complete. It is invalidated when
 * the file handle, size or chunk size change; it does not depend on the number of connections.
 */
final class ProgressSidecar {

    private static final Logger log = LoggerFactory.getLogger(ProgressSidecar.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ProgressSidecar() {
    }

    static Path pathFor(Path partFile) {
        return partFile.resolveSibling(partFile.getFileName() + ".progress.json");
    }

    static Set<Chunk> load(Path partFile, String handle, long fileSize, long chunkSize) {
        Path p = pathFor(partFile);
        Set<Chunk> done = new HashSet<>();
        if (!Files.isRegularFile(p)) {
            return done;
        }
        try {
            JsonNode root = MAPPER.readTree(p.toFile());
            if (!handle.equals(root.path("file_handle").asText()) || root.path("file_size").asLong(-1) != fileSize
                    || root.path("chunk_size").asLong(-1) != chunkSize) {
                log.info("Sidecar {} does not match (handle/size/chunk size changed): ignored", p);
                return done;
            }
            for (JsonNode pair : root.path("done")) {
                done.add(new Chunk(-1, pair.get(0).asLong(), pair.get(1).asLong()));
            }
        } catch (IOException | RuntimeException e) {
            log.warn("Unreadable sidecar {}: {}", p, e.toString());
        }
        return done;
    }

    static void save(Path partFile, String handle, long fileSize, long chunkSize, Collection<Chunk> done) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("file_handle", handle);
        root.put("file_size", fileSize);
        root.put("chunk_size", chunkSize);
        ArrayNode arr = root.putArray("done");
        done.stream().sorted().forEach(c -> arr.addArray().add(c.start()).add(c.end()));
        Path p = pathFor(partFile);
        Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
        try {
            MAPPER.writeValue(tmp.toFile(), root);
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.warn("Cannot save sidecar {}: {}", p, e.toString());
        }
    }

    static void delete(Path partFile) {
        try {
            Files.deleteIfExists(pathFor(partFile));
        } catch (IOException ignored) {
            // best effort
        }
    }
}
