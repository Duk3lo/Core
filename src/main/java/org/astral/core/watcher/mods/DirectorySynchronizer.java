package org.astral.core.watcher.mods;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;

public final class DirectorySynchronizer {

    private static final long DEST_SUPPRESS_MILLIS = 1200L; // supresión corta por cada archivo escrito

    private DirectorySynchronizer() {}

    /**
     * Replace target contents with source contents. Robust to files created/deleted concurrently.
     */
    public static void replaceSync(Path source, Path target) throws IOException {
        if (!Files.exists(source)) {
            // Si no existe la fuente, borramos contenido del target (si existe) y salimos.
            if (Files.exists(target)) {
                deleteChildren(target);
            } else {
                Files.createDirectories(target);
            }
            return;
        }

        if (!Files.exists(target)) {
            Files.createDirectories(target);
        }

        // Borramos contenido del target (de forma tolerante) y copiamos archivo por archivo.
        deleteChildren(target);
        copyTreeSafe(source, target);
    }

    /**
     * Borra el contenido directo de 'target' (no borra target mismo).
     * Tolerante a errores y archivos que desaparezcan en el proceso.
     */
    private static void deleteChildren(Path target) throws IOException {
        if (!Files.exists(target)) return;

        Files.walkFileTree(target, new SimpleFileVisitor<>() {
            @Override
            public @NotNull FileVisitResult visitFile(@NotNull Path file, @NotNull BasicFileAttributes attrs) {
                try {
                    // suprimir notificaciones para este archivo antes de borrar
                    WatchEventSuppressor.suppress(file, DEST_SUPPRESS_MILLIS);
                    Files.deleteIfExists(file);
                } catch (IOException e) {
                    System.err.println("[SYNC] No se pudo borrar archivo " + file + ": " + e.getMessage());
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public @NotNull FileVisitResult postVisitDirectory(@NotNull Path dir, IOException exc) {
                if (exc != null) {
                    System.err.println("[SYNC] Error visitando dir " + dir + ": " + exc.getMessage());
                }
                if (!dir.equals(target)) {
                    try {
                        WatchEventSuppressor.suppress(dir, DEST_SUPPRESS_MILLIS);
                        Files.deleteIfExists(dir);
                    } catch (IOException e) {
                        System.err.println("[SYNC] No se pudo borrar dir " + dir + ": " + e.getMessage());
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * Copia el árbol de source dentro de target de forma segura:
     * por cada archivo/dir intenta crear/copiar y si falla en ese item lo reporta y continúa.
     */
    static void copyTreeSafe(Path source, Path target) throws IOException {
        if (!Files.exists(source)) return;

        Files.walkFileTree(source, new SimpleFileVisitor<>() {

            @Override
            public @NotNull FileVisitResult preVisitDirectory(@NotNull Path dir, @NotNull BasicFileAttributes attrs) {
                Path rel = source.relativize(dir);
                Path destDir = target.resolve(rel);
                try {
                    if (!Files.exists(destDir)) {
                        WatchEventSuppressor.suppress(destDir, DEST_SUPPRESS_MILLIS);
                        Files.createDirectories(destDir);
                    }
                } catch (IOException e) {
                    System.err.println("[SYNC] No se pudo crear dir " + destDir + ": " + e.getMessage());
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public @NotNull FileVisitResult visitFile(@NotNull Path file,
                                                      @NotNull BasicFileAttributes attrs) {
                Path rel = source.relativize(file);
                Path dest = target.resolve(rel);

                try {
                    ensureParentDirectory(dest);

                    // suprimir el destino concreto ANTES de escribir
                    WatchEventSuppressor.suppress(dest, DEST_SUPPRESS_MILLIS);

                    Files.copy(file, dest,
                            StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.COPY_ATTRIBUTES);

                } catch (NoSuchFileException nsf) {
                    System.err.println("[SYNC] Archivo desapareció antes de copiar: " + file);
                } catch (IOException e) {
                    System.err.println("[SYNC] Error copiando " + file + " -> " + dest + ": " + e.getMessage());
                }

                return FileVisitResult.CONTINUE;
            }

            @Override
            public @NotNull FileVisitResult visitFileFailed(@NotNull Path file, @NotNull IOException exc) {
                System.err.println("[SYNC] visitFileFailed " + file + ": " + exc.getMessage());
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * Asegura que el parent de 'dest' exista; lo crea si hace falta y marca supresión.
     */
    private static void ensureParentDirectory(@NotNull Path dest) throws IOException {
        Path parent = dest.getParent();
        if (parent != null && !Files.exists(parent)) {
            WatchEventSuppressor.suppress(parent, DEST_SUPPRESS_MILLIS);
            Files.createDirectories(parent);
        }
    }

    /**
     * Aplica eventos del WatchService de forma tolerante.
     */
    public static void applyEvents(Path source, Path target, List<WatchEvent<?>> events) throws IOException {
        if (!Files.exists(target)) {
            Files.createDirectories(target);
        }

        for (WatchEvent<?> ev : events) {
            WatchEvent.Kind<?> kind = ev.kind();

            if (kind == StandardWatchEventKinds.OVERFLOW) {
                // fallback: full sync
                replaceSync(source, target);
                return;
            }

            Object context = ev.context();
            if (!(context instanceof Path relative)) {
                continue;
            }

            Path srcPath = source.resolve(relative);
            Path destPath = target.resolve(relative);

            try {
                if (kind == StandardWatchEventKinds.ENTRY_DELETE) {
                    try {
                        WatchEventSuppressor.suppress(destPath, DEST_SUPPRESS_MILLIS);
                        deleteRecursivelyIfExists(destPath);
                    } catch (IOException e) {
                        System.err.println("[SYNC] Error borrando " + destPath + ": " + e.getMessage());
                    }
                } else if (kind == StandardWatchEventKinds.ENTRY_CREATE || kind == StandardWatchEventKinds.ENTRY_MODIFY) {
                    if (Files.exists(srcPath) && Files.isDirectory(srcPath)) {
                        // suprimir destino de la subtree antes de hacer replace
                        WatchEventSuppressor.suppress(destPath, DEST_SUPPRESS_MILLIS);
                        DirectorySynchronizer.replaceSync(srcPath, destPath);
                    } else {
                        if (Files.exists(srcPath)) {
                            try {
                                ensureParentDirectory(destPath);
                                WatchEventSuppressor.suppress(destPath, DEST_SUPPRESS_MILLIS);
                                Files.copy(srcPath, destPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                            } catch (NoSuchFileException nsf) {
                                System.err.println("[SYNC] Archivo fuente desapareció: " + srcPath);
                            } catch (IOException e) {
                                System.err.println("[SYNC] Error copiando evento " + srcPath + " -> " + destPath + ": " + e.getMessage());
                            }
                        } else {
                            System.err.println("[SYNC] Evento para archivo que no existe (ignorado): " + srcPath);
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("[SYNC] Error aplicando evento " + kind + " " + relative + ": " + e.getMessage());
            }
        }
    }

    private static void deleteRecursivelyIfExists(Path path) throws IOException {
        if (!Files.exists(path)) return;
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public @NotNull FileVisitResult visitFile(@NotNull Path file, @NotNull BasicFileAttributes attrs) {
                try {
                    WatchEventSuppressor.suppress(file, DEST_SUPPRESS_MILLIS);
                    Files.deleteIfExists(file);
                } catch (IOException e) {
                    System.err.println("[SYNC] No se pudo eliminar archivo " + file + ": " + e.getMessage());
                }
                return FileVisitResult.CONTINUE;
            }
            @Override
            public @NotNull FileVisitResult postVisitDirectory(@NotNull Path dir, IOException exc) {
                try {
                    WatchEventSuppressor.suppress(dir, DEST_SUPPRESS_MILLIS);
                    Files.deleteIfExists(dir);
                } catch (IOException e) {
                    System.err.println("[SYNC] No se pudo eliminar dir " + dir + ": " + e.getMessage());
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * Copia solo el contenido top-level (sin hacer fail por archivos faltantes).
     */
    public static void copyTopLevelContents(Path source, Path target) throws IOException {
        if (!Files.exists(source)) return;

        if (!Files.exists(target)) {
            Files.createDirectories(target);
        }

        try (DirectoryStream<Path> children = Files.newDirectoryStream(source)) {
            for (Path child : children) {
                Path dest = target.resolve(child.getFileName());
                try {
                    if (Files.isDirectory(child)) {
                        // suprimir destino raíz de esta subtree
                        WatchEventSuppressor.suppress(dest, DEST_SUPPRESS_MILLIS);
                        copyTreeSafe(child, dest);
                    } else {
                        ensureParentDirectory(dest);
                        WatchEventSuppressor.suppress(dest, DEST_SUPPRESS_MILLIS);
                        Files.copy(child, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                    }
                } catch (NoSuchFileException nsf) {
                    System.err.println("[SYNC] Archivo desapareció durante copyTopLevel: " + child);
                } catch (IOException e) {
                    System.err.println("[SYNC] Error copiando top-level " + child + " -> " + dest + ": " + e.getMessage());
                }
            }
        }
    }
}