package dev.darshan.spotme.media;

import dev.darshan.spotme.config.SpotMeProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** Private files on local disk, outside any web root. Only the media endpoint reads them. */
@Component
public class LocalPhotoStorage implements PhotoStorage {

    private final Path root;

    public LocalPhotoStorage(SpotMeProperties props) throws IOException {
        this.root = Path.of(props.storageDir()).toAbsolutePath().normalize();
        Files.createDirectories(root);
    }

    @Override
    public void put(String key, byte[] bytes) throws IOException {
        Path target = resolve(key);
        Files.createDirectories(target.getParent());
        // write to a temp name first, then rename, so a crash never leaves half a file under the real key
        Path tmp = target.resolveSibling(target.getFileName() + ".part");
        Files.write(tmp, bytes);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    @Override
    public byte[] get(String key) throws IOException {
        return Files.readAllBytes(resolve(key));
    }

    @Override
    public InputStream open(String key) throws IOException {
        return Files.newInputStream(resolve(key));
    }

    @Override
    public void delete(String key) throws IOException {
        Files.deleteIfExists(resolve(key));
    }

    @Override
    public boolean exists(String key) {
        return Files.exists(resolve(key));
    }

    @Override
    public List<StoredObject> list(String prefix) throws IOException {
        Path dir = resolve(prefix);
        List<StoredObject> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path p : files.filter(Files::isRegularFile).toList()) {
                String key = root.relativize(p).toString().replace('\\', '/');
                out.add(new StoredObject(key, Files.getLastModifiedTime(p).toInstant()));
            }
        }
        return out;
    }

    /** Keys are built by our own code, but we still refuse anything that escapes the root. */
    private Path resolve(String key) {
        Path p = root.resolve(key).normalize();
        if (!p.startsWith(root)) {
            throw new IllegalArgumentException("Bad storage key");
        }
        return p;
    }
}
