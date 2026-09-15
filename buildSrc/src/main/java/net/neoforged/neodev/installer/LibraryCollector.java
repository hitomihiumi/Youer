package net.neoforged.neodev.installer;

import net.neoforged.neodev.utils.MavenIdentifier;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * For each file in a collection, finds the repository that the file came from.
 */
class LibraryCollector {
    public static List<Library> resolveLibraries(List<URI> repositoryUrls, Collection<IdentifiedFile> libraries) throws IOException {
        var collector = new LibraryCollector(repositoryUrls);
        for (var library : libraries) {
            collector.addLibrary(library.getFile().getAsFile().get(), library.getIdentifier().get());
        }

        var result = collector.libraries.stream().map(future -> {
            try {
                return future.get();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).toList();
        LOGGER.info("Collected {} libraries", result.size());
        return result;
    }

    private static final Logger LOGGER = Logging.getLogger(LibraryCollector.class);
    /**
     * Hosts from which we allow the installer to download.
     * We whitelist here to avoid redirecting player download traffic to anyone not affiliated with Mojang or us.
     */
    // Youer: the installer may only be pointed at hosts we trust. NeoForge's own three are kept, and
    // the rest are exactly the repositories settings.gradle declares - Youer's dependency graph pulls
    // Paper, Spigot, Mohist and JitPack artifacts that are published nowhere else.
    private static final List<String> HOST_WHITELIST = List.of(
            "minecraft.net",
            "neoforged.net",
            "mojang.com",
            "maven.org",
            "spigotmc.org",
            "mohistmc.com",
            "mohistmc.github.io",
            "papermc.io",
            "jitpack.io",
            "createmod.net",
            "ryanhcode.dev",
            "minecraftforge.net",
            "modrinth.com"
    );

    private static final URI MOJANG_MAVEN = URI.create("https://libraries.minecraft.net");
    private static final URI NEOFORGED_MAVEN = URI.create("https://maven.neoforged.net/releases");
    private static final URI MOHISTMC_MAVEN = URI.create("https://maven.mohistmc.com");
    private static final URI PAPERMC_MAVEN = URI.create("https://repo.papermc.io/repository/maven-public"); // Youer

    private final List<URI> repositoryUrls;

    private final List<Future<Library>> libraries = new ArrayList<>();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private LibraryCollector(List<URI> repoUrl) {
        this.repositoryUrls = new ArrayList<>(repoUrl);

        // Only remote repositories make sense (no maven local)
        repositoryUrls.removeIf(it -> {
            var lowercaseScheme = it.getScheme().toLowerCase(Locale.ROOT);
            return !lowercaseScheme.equals("https") && !lowercaseScheme.equals("http");
        });
        // Allow only URLs from whitelisted hosts
        repositoryUrls.removeIf(uri -> {
            var lowercaseHost = uri.getHost().toLowerCase(Locale.ROOT);
            return HOST_WHITELIST.stream().noneMatch(it -> lowercaseHost.equals(it) || lowercaseHost.endsWith("." + it));
        });
        // Always try Mojang Maven first, then our installer Maven
        repositoryUrls.removeIf(it -> it.getHost().equals(MOJANG_MAVEN.getHost()));
        repositoryUrls.removeIf(it -> it.getHost().equals(NEOFORGED_MAVEN.getHost()) && it.getPath().startsWith(NEOFORGED_MAVEN.getPath()));
        repositoryUrls.removeIf(it -> it.getHost().equals(MOHISTMC_MAVEN.getHost()) && it.getPath().startsWith(MOHISTMC_MAVEN.getPath()));
        repositoryUrls.removeIf(it -> it.getHost().equals(PAPERMC_MAVEN.getHost()) && it.getPath().startsWith(PAPERMC_MAVEN.getPath())); // Youer
        repositoryUrls.add(0, PAPERMC_MAVEN); // Youer
        repositoryUrls.add(0, NEOFORGED_MAVEN);
        repositoryUrls.add(0, MOJANG_MAVEN);
        repositoryUrls.add(0, MOHISTMC_MAVEN);

        LOGGER.info("Collecting libraries from:");
        for (var repo : repositoryUrls) {
            LOGGER.info(" - {}", repo);
        }
    }

    private void addLibrary(File file, MavenIdentifier identifier) throws IOException {
        final String name = identifier.artifactNotation();
        final String path = identifier.repositoryPath();

        var sha1 = sha1Hash(file.toPath());
        var fileSize = Files.size(file.toPath());

        // Try each configured repository in-order to find the file
        CompletableFuture<Library> libraryFuture = null;
        for (var repositoryUrl : repositoryUrls) {
            // Youer: a -SNAPSHOT version is not the file name; ask the repo's maven-metadata.xml for the
            // timestamped build it currently points at, so the installer gets a URL that actually exists.
            var artifactUri = joinUris(repositoryUrl, resolveSnapshotPath(repositoryUrl, identifier, path));
            var request = HttpRequest.newBuilder(artifactUri)
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build();

            Function<String, CompletableFuture<Library>> makeRequest = (String previousError) -> {
                return httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                        .thenApply(response -> {
                            if (response.statusCode() != 200) {
                                LOGGER.info("  Got {} for {}", response.statusCode(), artifactUri);
                                String message = "Could not find %s: %d".formatted(artifactUri, response.statusCode());
                                // Prepend error message from previous repo if they all fail
                                if (previousError != null) {
                                    message = previousError + "\n" + message;
                                }
                                throw new RuntimeException(message);
                            }
                            LOGGER.info("  Found {} -> {}", name, artifactUri);
                            return new Library(
                                    name,
                                    new LibraryDownload(new LibraryArtifact(
                                            sha1,
                                            fileSize,
                                            artifactUri.toString(),
                                            path)));
                        });
            };

            if (libraryFuture == null) {
                libraryFuture = makeRequest.apply(null);
            } else {
                libraryFuture = libraryFuture.exceptionallyCompose(error -> {
                    if (error instanceof CompletionException e) {
                        return makeRequest.apply(e.getCause().getMessage());
                    }
                    return makeRequest.apply(error.getMessage());
                });
            }
        }

        libraries.add(libraryFuture);
    }

    // Youer start - resolve Maven snapshot versions to their timestamped artifact
    private static final Pattern SNAPSHOT_VALUE = Pattern.compile("<value>([^<]+)</value>");
    /** A snapshot Gradle already resolved, e.g. {@code 0.1-20240720.200737-2}. */
    private static final Pattern RESOLVED_SNAPSHOT = Pattern.compile("^(.*)-\\d{8}\\.\\d{6}-\\d+$");

    /**
     * A snapshot artifact does not live under a directory named after its version: the directory is
     * always {@code <base>-SNAPSHOT} while the file name carries the timestamped build. Gradle hands us
     * either form, so normalise both into a path the installer can actually download.
     */
    private String resolveSnapshotPath(URI repositoryUrl, MavenIdentifier identifier, String literalPath) {
        var version = identifier.version();

        String directoryVersion;
        String fileVersion;
        Matcher resolved = RESOLVED_SNAPSHOT.matcher(version);
        if (resolved.matches()) {
            directoryVersion = resolved.group(1) + "-SNAPSHOT";
            fileVersion = version;
        } else if (version.endsWith("-SNAPSHOT")) {
            directoryVersion = version;
            fileVersion = latestSnapshotBuild(repositoryUrl, identifier);
            if (fileVersion == null) {
                return literalPath;
            }
        } else {
            return literalPath;
        }

        return identifier.group().replace(".", "/") + "/" + identifier.artifact() + "/" + directoryVersion
                + "/" + identifier.artifact() + "-" + fileVersion
                + (identifier.classifier().isEmpty() ? "" : "-" + identifier.classifier())
                + "." + identifier.extension();
    }

    /** The timestamped build {@code maven-metadata.xml} currently points at, or null if unavailable. */
    private String latestSnapshotBuild(URI repositoryUrl, MavenIdentifier identifier) {
        var metadataPath = identifier.group().replace(".", "/") + "/" + identifier.artifact() + "/"
                + identifier.version() + "/maven-metadata.xml";
        try {
            var request = HttpRequest.newBuilder(joinUris(repositoryUrl, metadataPath)).GET().build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return null;
            }

            Matcher matcher = SNAPSHOT_VALUE.matcher(response.body());
            return matcher.find() ? matcher.group(1) : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
    // Youer end - resolve Maven snapshot versions to their timestamped artifact

    static String sha1Hash(Path path) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }

        try (var in = Files.newInputStream(path);
                var din = new DigestInputStream(in, digest)) {
            byte[] buffer = new byte[8192];
            while (din.read(buffer) != -1) {
            }
        }

        return HexFormat.of().formatHex(digest.digest());
    }

    private static URI joinUris(URI repositoryUrl, String path) {
        var baseUrl = repositoryUrl.toString();
        if (baseUrl.endsWith("/") && path.startsWith("/")) {
            while (path.startsWith("/")) {
                path = path.substring(1);
            }
            return URI.create(baseUrl + path);
        } else if (!baseUrl.endsWith("/") && !path.startsWith("/")) {
            return URI.create(baseUrl + "/" + path);
        } else {
            return URI.create(baseUrl + path);
        }
    }
}
