package com.tailor.web.storage;

import com.tailor.web.config.AppProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.stream.Stream;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Files on a directory of the server (PHASE6_SPEC.md section 3.1, {@code APP_STORAGE_MODE=filesystem}). The
 * directory is meant to be a Docker volume owned by the app's non-root user, mode 700.
 *
 * <p>Writes are atomic (a temporary name, then a rename or a no-clobber link), and a write-once object is never
 * replaced. There are no public URLs: {@link #presignGet} returns {@code {public base}/files/{id}} where the id is
 * opaque (the key, an expiry and a download name, encrypted and authenticated with a server-side key). The api's
 * {@code GET /files/{id}} then checks the signed-in user owns the key and streams the bytes.
 */
@Component
@ConditionalOnProperty(name = "app.storage.mode", havingValue = "filesystem")
public class FilesystemFileStorage implements FileStorage {

    /** What an opaque download id stands for. */
    public record Download(String key, Instant expiresAt, String downloadName) {
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Path root;
    private final String publicBaseUrl;
    private final SecretKeySpec linkKey;
    private final Clock clock;

    public FilesystemFileStorage(StorageProperties props, AppProperties app, Clock clock) {
        if (props.dir() == null || props.dir().isBlank()) {
            throw new IllegalStateException("app.storage.mode=filesystem needs app.storage.dir (APP_STORAGE_DIR)");
        }
        this.root = Path.of(props.dir()).toAbsolutePath().normalize();
        this.publicBaseUrl = app.publicBaseUrl();
        this.clock = clock;
        byte[] secret;
        if (props.linkSecret() != null && !props.linkSecret().isBlank()) {
            secret = props.linkSecret().getBytes(StandardCharsets.UTF_8);
        } else { // downloads live for minutes, so a key that lasts as long as the process is enough
            secret = new byte[32];
            RANDOM.nextBytes(secret);
        }
        this.linkKey = new SecretKeySpec(sha256(secret), "AES");
        try {
            Files.createDirectories(root);
            if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot prepare the storage directory " + root, e);
        }
    }

    // ---- FileStorage ---------------------------------------------------------------------------

    @Override
    public void put(String key, byte[] data, String contentType, boolean overwrite) {
        Path target = resolve(key);
        Path temp = null;
        try {
            Files.createDirectories(target.getParent());
            temp = Files.createTempFile(target.getParent(), ".tmp-", ".part");
            Files.write(temp, data);
            if (overwrite) {
                try {
                    Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } else {
                // A hard link fails if the name exists: the write-once rule, atomically, with no check-then-write gap.
                try {
                    Files.createLink(target, temp);
                } catch (FileAlreadyExistsException e) {
                    throw new StorageConflictException(key);
                } finally {
                    Files.deleteIfExists(temp);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot store " + key, e);
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // a leftover .part file is harmless and is skipped by every read
                }
            }
        }
    }

    @Override
    public byte[] get(String key) {
        try {
            return Files.readAllBytes(resolve(key));
        } catch (NoSuchFileException e) {
            throw new StorageNotFoundException(key);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + key, e);
        }
    }

    @Override
    public boolean exists(String key) {
        return Files.isRegularFile(resolve(key));
    }

    @Override
    public PresignedLink presignGet(String key, Duration ttl, String downloadName) {
        Instant expires = clock.instant().plus(ttl);
        return new PresignedLink(publicBaseUrl + "/files/" + seal(new Download(key, expires, downloadName)), expires);
    }

    @Override
    public int deletePrefix(String prefix) {
        int[] removed = {0};
        for (Path file : matching(prefix)) {
            try {
                if (Files.deleteIfExists(file)) {
                    removed[0]++;
                }
            } catch (IOException e) {
                throw new UncheckedIOException("cannot delete " + file, e);
            }
        }
        pruneEmptyDirectories(prefix);
        return removed[0];
    }

    @Override
    public int countPrefix(String prefix) {
        return matching(prefix).size();
    }

    // ---- downloads -----------------------------------------------------------------------------

    /** The file behind a key, for streaming. Empty if it is not there. */
    public Optional<Path> file(String key) {
        Path path = resolve(key);
        return Files.isRegularFile(path) ? Optional.of(path) : Optional.empty();
    }

    /** Opens an opaque id from {@link #presignGet}; empty if it was not issued by this server, is tampered with, or has expired. */
    public Optional<Download> open(String id) {
        Optional<Download> download = unseal(id);
        return download.filter(d -> d.expiresAt().isAfter(clock.instant()));
    }

    // ---- internals -----------------------------------------------------------------------------

    private Path resolve(String key) {
        if (key == null || key.isEmpty() || key.startsWith("/") || key.contains("\\") || key.contains("..")
                || key.contains("\0")) {
            throw new IllegalArgumentException("not a storage key");
        }
        Path path = root.resolve(key).normalize();
        if (!path.startsWith(root) || path.equals(root)) {
            throw new IllegalArgumentException("not a storage key");
        }
        return path;
    }

    private java.util.List<Path> matching(String prefix) {
        resolve(prefix.endsWith("/") ? prefix + "x" : prefix); // validates the prefix like a key
        int slash = prefix.lastIndexOf('/');
        Path start = slash < 0 ? root : root.resolve(prefix.substring(0, slash)).normalize();
        if (!Files.isDirectory(start)) {
            return java.util.List.of();
        }
        try (Stream<Path> walk = Files.walk(start)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().startsWith(".tmp-"))
                    .filter(p -> root.relativize(p).toString().replace('\\', '/').startsWith(prefix))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list " + prefix, e);
        }
    }

    private void pruneEmptyDirectories(String prefix) {
        int slash = prefix.lastIndexOf('/');
        Path start = slash < 0 ? root : root.resolve(prefix.substring(0, slash)).normalize();
        if (!Files.isDirectory(start) || start.equals(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(start)) {
            walk.sorted(java.util.Comparator.reverseOrder()).filter(Files::isDirectory)
                    .filter(p -> !p.equals(root)).forEach(dir -> {
                        try (Stream<Path> children = Files.list(dir)) {
                            if (children.findAny().isEmpty()) {
                                Files.delete(dir);
                            }
                        } catch (IOException ignored) {
                            // not empty or already gone
                        }
                    });
        } catch (IOException ignored) {
            // best effort: an empty directory holds no data
        }
    }

    private String seal(Download d) {
        try {
            byte[] nonce = new byte[12];
            RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, linkKey, new GCMParameterSpec(128, nonce));
            String plain = d.expiresAt().getEpochSecond() + "\n" + (d.downloadName() == null ? "" : d.downloadName())
                    + "\n" + d.key();
            byte[] sealed = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] all = new byte[nonce.length + sealed.length];
            System.arraycopy(nonce, 0, all, 0, nonce.length);
            System.arraycopy(sealed, 0, all, nonce.length, sealed.length);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(all);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private Optional<Download> unseal(String id) {
        try {
            byte[] all = Base64.getUrlDecoder().decode(id);
            if (all.length < 12 + 16) {
                return Optional.empty();
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, linkKey, new GCMParameterSpec(128, all, 0, 12));
            String plain = new String(cipher.doFinal(all, 12, all.length - 12), StandardCharsets.UTF_8);
            String[] parts = plain.split("\n", 3);
            if (parts.length != 3) {
                return Optional.empty();
            }
            return Optional.of(new Download(parts[2], Instant.ofEpochSecond(Long.parseLong(parts[0])),
                    parts[1].isEmpty() ? null : parts[1]));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
