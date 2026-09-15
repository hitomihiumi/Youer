package io.papermc.paper.pluginremap;

import com.mohistmc.art.api.Renamer;
import com.mohistmc.art.api.Transformer;
import com.mohistmc.art.internal.RenamerImpl;
import com.mojang.logging.LogUtils;
import io.papermc.paper.util.AtomicFiles;
import io.papermc.paper.util.MappingEnvironment;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.neoforged.srgutils.IMappingFile;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.framework.qual.DefaultQualifier;
import org.slf4j.Logger;
import static io.papermc.paper.pluginremap.InsertManifestAttribute.addNamespaceManifestAttribute;

@DefaultQualifier(NonNull.class)
final class ReobfServer {
    private static final Logger LOGGER = io.papermc.paper.util.PaperLogUtils.getClassLogger();

    private final Path remapClasspathDir;
    private final CompletableFuture<Void> load;

    ReobfServer(final Path remapClasspathDir, final CompletableFuture<IMappingFile> mappings, final Executor executor) {
        this.remapClasspathDir = remapClasspathDir;
        if (this.mappingsChanged()) {
            this.load = mappings.thenAcceptAsync(this::remap, executor);
        } else {
            if (PluginRemapper.DEBUG_LOGGING) {
				LOGGER.info("Have cached reobf server for current mappings.");
			}
            this.load = CompletableFuture.completedFuture(null);
        }
    }

    CompletableFuture<Path> remapped() {
        return this.load.thenApply($ -> this.remappedPath());
    }

    private Path remappedPath() {
        return this.remapClasspathDir.resolve(MappingEnvironment.mappingsHash() + ".jar");
    }

    private boolean mappingsChanged() {
        return !Files.exists(this.remappedPath());
    }

    private void remap(final IMappingFile mappings) {
        try {
            if (!Files.exists(this.remapClasspathDir)) {
                Files.createDirectories(this.remapClasspathDir);
            }
            for (final Path file : PluginRemapper.list(this.remapClasspathDir, Files::isRegularFile)) {
                Files.delete(file);
            }
        } catch (final IOException ex) {
            throw new RuntimeException(ex);
        }

        LOGGER.info("Remapping server...");
        final long startRemap = System.currentTimeMillis();
        try (final DebugLogger log = DebugLogger.forOutputFile(this.remappedPath())) {
            AtomicFiles.atomicWrite(this.remappedPath(), writeTo -> {
                try (final RenamerImpl renamer = (RenamerImpl) Renamer.builder()
                    .logger(log)
                    .debug(log.debug())
                    .threads(1)
                    .add(Transformer.renamerFactory(mappings, false))
                    .add(addNamespaceManifestAttribute(InsertManifestAttribute.SPIGOT_NAMESPACE))
                    .build()) {
                    final Path serverJar = serverJar(); // Youer - may be a temporary merge, see below
                    try {
                        renamer.run(serverJar.toFile(), writeTo.toFile(), true);
                    } finally {
                        if (serverJar.startsWith(this.remapClasspathDir)) { // Youer
                            Files.deleteIfExists(serverJar); // Youer
                        } // Youer
                    }
                }
            });
        } catch (final Exception ex) {
            throw new RuntimeException("Failed to remap server jar", ex);
        }
        LOGGER.info("Done remapping server in {}ms.", System.currentTimeMillis() - startRemap);
    }

    // Youer start - reconstruct Paper's single server jar
    // Paper remaps one jar holding both net.minecraft and the Bukkit layer. Youer's server is split
    // across FML's module jars, and both of them live inside SecureJarHandler's union file system,
    // whose paths java.nio refuses to turn into Files at all. So resolve each module back to the real
    // jar behind it and, when they differ, merge them into one temporary jar for ART to read.
    private Path serverJar() throws IOException {
        final Path bukkitLayer = realJarOf(ReobfServer.class);
        final Path minecraft = realJarOf(net.minecraft.server.MinecraftServer.class);
        if (bukkitLayer.equals(minecraft)) {
            return bukkitLayer;
        }

        Files.createDirectories(this.remapClasspathDir);
        final Path merged = Files.createTempFile(this.remapClasspathDir, "server-merge", ".jar");
        final java.util.Set<String> seen = new java.util.HashSet<>();
        try (final java.util.zip.ZipOutputStream out =
                 new java.util.zip.ZipOutputStream(Files.newOutputStream(merged))) {
            // The Bukkit layer goes first so that its copy of a shared entry wins, the way it does on
            // the running server's classpath.
            for (final Path source : java.util.List.of(bukkitLayer, minecraft)) {
                try (final java.util.zip.ZipFile in = new java.util.zip.ZipFile(source.toFile())) {
                    final java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = in.entries();
                    while (entries.hasMoreElements()) {
                        final java.util.zip.ZipEntry entry = entries.nextElement();
                        if (entry.isDirectory() || !seen.add(entry.getName())) {
                            continue;
                        }
                        out.putNextEntry(new java.util.zip.ZipEntry(entry.getName()));
                        try (final java.io.InputStream data = in.getInputStream(entry)) {
                            data.transferTo(out);
                        }
                        out.closeEntry();
                    }
                }
            }
        }
        return merged;
    }

    /** The jar a class was loaded from, seen through the default file system. */
    private static Path realJarOf(final Class<?> owner) {
        final Path path;
        try {
            path = Path.of(owner.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (final URISyntaxException ex) {
            throw new RuntimeException(ex);
        }
        if (path.getFileSystem() == java.nio.file.FileSystems.getDefault()) {
            return path;
        }
        // cpw.mods.niofs.union.UnionFileSystem#getPrimaryPath, reached reflectively so that this stays
        // free of a compile-time dependency on SecureJarHandler.
        try {
            return (Path) path.getFileSystem().getClass().getMethod("getPrimaryPath").invoke(path.getFileSystem());
        } catch (final ReflectiveOperationException | ClassCastException ex) {
            throw new RuntimeException("Could not find the jar " + owner.getName() + " was loaded from", ex);
        }
    }
    // Youer end - reconstruct Paper's single server jar
}
