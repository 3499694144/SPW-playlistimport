package local.saltplaylist.resolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Consumer;

/** Coordinates fingerprinting, cache recovery and diagnostics; feature rules live in separate files. */
public final class HostSymbolResolver {
    public static final String SUPPORTED_HOST = "1.19.0";
    private static final String RULE_VERSION = "8";
    private final String pluginVersion;
    private HostSymbols.Resolved resolved;

    public HostSymbolResolver(String pluginVersion) {
        this.pluginVersion = java.util.Objects.requireNonNull(pluginVersion);
    }

    public synchronized HostSymbols.Resolved resolve(Path hostJar, ClassLoader hostLoader,
                                                      Path dataDirectory, Consumer<String> output) throws IOException {
        if (resolved != null) return resolved;
        var report = new StringBuilder("AndroidPlaylistImport ").append(Instant.now()).append('\n');
        Consumer<String> log = line -> {
            report.append(line).append('\n');
            output.accept(line);
        };
        try {
            String fingerprint = sha256(hostJar);
            Path cache = dataDirectory.resolve("host-symbols.properties");
            if (Files.isRegularFile(cache)) {
                try {
                    if (Files.size(cache) > 64 * 1024) throw new IOException("Oversized symbol cache");
                    Properties properties = new Properties();
                    try (var reader = Files.newBufferedReader(cache, StandardCharsets.UTF_8)) { properties.load(reader); }
                    if (fingerprint.equals(properties.getProperty("host.sha256"))
                            && RULE_VERSION.equals(properties.getProperty("rules"))
                            && pluginVersion.equals(properties.getProperty("plugin"))) {
                        resolved = HostSymbols.load(properties).restore(hostLoader);
                        resolved.descriptors().points().forEach((point, ref) ->
                                log.accept("HookPoint[" + point + "] state=FOUND source=cache target=" + ref));
                        return resolved;
                    }
                    log.accept("SymbolCache invalidated: host fingerprint or rules changed");
                } catch (IOException | ReflectiveOperationException | RuntimeException | LinkageError failure) {
                    log.accept("Scan Errors: cache restore failed; rescanning: " + failure);
                }
            }

            var errors = new java.util.ArrayList<String>();
            HostSymbols playlist = scanFeature("PlaylistImport", () -> PlaylistRules.scan(hostJar, log), log, errors);
            CoverSymbols cover = scanFeature("PlaylistCover", () -> CoverRules.scan(hostJar, log), log, errors);
            ImportUiSymbols ui = scanFeature("ImportUi", () -> ImportUiRules.scan(hostJar, log), log, errors);
            FilePickerSymbols picker = scanFeature("FilePicker", () -> FilePickerRules.scan(hostJar, log), log, errors);
            if (!errors.isEmpty()) throw new IOException(String.join("; ", errors));
            HostSymbols symbols = playlist.withFeatures(cover, ui, picker);
            resolved = symbols.restore(hostLoader);
            if (Thread.currentThread().isInterrupted()) throw new IOException("Symbol resolution cancelled");
            Properties properties = new Properties();
            properties.setProperty("host.sha256", fingerprint);
            properties.setProperty("rules", RULE_VERSION);
            properties.setProperty("plugin", pluginVersion);
            symbols.save(properties);
            try {
                Files.createDirectories(dataDirectory);
                Path temporary = dataDirectory.resolve("host-symbols-" + UUID.randomUUID() + ".tmp");
                try {
                    try (var writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                        properties.store(writer, "Resolved for SPW " + SUPPORTED_HOST);
                    }
                    move(temporary, cache);
                } finally {
                    Files.deleteIfExists(temporary);
                }
            } catch (IOException failure) {
                log.accept("SymbolCache persistence disabled for this session: " + failure);
            }
            return resolved;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            log.accept("HookPoint[AndroidPlaylistImport] state=ERROR Scan Errors: " + failure);
            throw new IOException("Host symbols cannot be restored: " + failure.getMessage(), failure);
        } catch (IOException failure) {
            log.accept("HookPoint[AndroidPlaylistImport] state=ERROR Scan Errors: " + failure);
            throw failure;
        } finally {
            if (!Thread.currentThread().isInterrupted()) {
                try {
                    Files.createDirectories(dataDirectory);
                    Files.writeString(dataDirectory.resolve("symbol-status.txt"), report, StandardCharsets.UTF_8);
                } catch (IOException failure) {
                    output.accept("Symbol status persistence failed (not retried): " + failure);
                }
            }
        }
    }

    @FunctionalInterface private interface Scan<T> { T run() throws IOException; }

    private static <T> T scanFeature(String name, Scan<T> scan, Consumer<String> log, java.util.List<String> errors) {
        try { return scan.run(); }
        catch (IOException | RuntimeException | LinkageError failure) {
            String reason = name + ": " + failure;
            errors.add(reason);
            log.accept("HookPoint[" + name + "] state=ERROR Scan Errors: " + failure);
            return null;
        }
    }

    private static void move(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String sha256(Path path) throws IOException {
        final MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("Fingerprint cancelled");
                digest.update(buffer, 0, count);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
