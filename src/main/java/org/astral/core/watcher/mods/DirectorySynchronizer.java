package org.astral.core.watcher.mods;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class DirectorySynchronizer {

    private static final long DEST_SUPPRESS_MILLIS = 1200L;

    private DirectorySynchronizer() {}

    public static void replaceSync(Path source, Path target) throws IOException {
        if (!Files.exists(source)) {
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

        deleteChildren(target);
        copyTreeSafe(source, target);
    }

    private static void deleteChildren(Path target) throws IOException {
        if (!Files.exists(target)) return;

        Files.walkFileTree(target, new SimpleFileVisitor<>() {
            @Override
            public @NotNull FileVisitResult visitFile(@NotNull Path file, @NotNull BasicFileAttributes attrs) {
                try {
                    WatchEventSuppressor.suppress(file, DEST_SUPPRESS_MILLIS);
                    Files.deleteIfExists(file);
                } catch (IOException e) {
                    System.err.println("[SYNC] No se pudo borrar archivo " + file + ": " + e.getMessage());
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public @NotNull FileVisitResult postVisitDirectory(@NotNull Path dir, IOException exc) {
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
                    WatchEventSuppressor.suppress(dest, DEST_SUPPRESS_MILLIS);
                    waitForStableFileQuiet(file);
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

    private static void ensureParentDirectory(@NotNull Path dest) throws IOException {
        Path parent = dest.getParent();
        if (parent != null && !Files.exists(parent)) {
            WatchEventSuppressor.suppress(parent, DEST_SUPPRESS_MILLIS);
            Files.createDirectories(parent);
        }
    }

    public static void applyEvents(Path source, Path target, List<WatchEvent<?>> events) throws IOException {
        if (!Files.exists(target)) {
            Files.createDirectories(target);
        }

        for (WatchEvent<?> ev : events) {
            WatchEvent.Kind<?> kind = ev.kind();

            if (kind == StandardWatchEventKinds.OVERFLOW) {
                replaceSync(source, target);
                return;
            }

            Object context = ev.context();
            if (!(context instanceof Path relative)) continue;

            Path srcPath = source.resolve(relative);
            Path destPath = target.resolve(relative);

            try {
                if (kind == StandardWatchEventKinds.ENTRY_DELETE) {
                    WatchEventSuppressor.suppress(destPath, DEST_SUPPRESS_MILLIS);
                    deleteRecursivelyIfExists(destPath);
                } else if (kind == StandardWatchEventKinds.ENTRY_CREATE ||
                        kind == StandardWatchEventKinds.ENTRY_MODIFY) {

                    if (Files.exists(srcPath) && Files.isDirectory(srcPath)) {
                        if (kind == StandardWatchEventKinds.ENTRY_CREATE) {
                            waitForStableTreeQuiet(srcPath);
                        }
                        WatchEventSuppressor.suppress(destPath, DEST_SUPPRESS_MILLIS);
                        replaceSync(srcPath, destPath);
                    } else {
                        if (Files.exists(srcPath)) {
                            waitForStableFileQuiet(srcPath);
                            ensureParentDirectory(destPath);
                            WatchEventSuppressor.suppress(destPath, DEST_SUPPRESS_MILLIS);
                            Files.copy(srcPath, destPath,
                                    StandardCopyOption.REPLACE_EXISTING,
                                    StandardCopyOption.COPY_ATTRIBUTES);
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
                } catch (IOException ignored) {}
                return FileVisitResult.CONTINUE;
            }

            @Override
            public @NotNull FileVisitResult postVisitDirectory(@NotNull Path dir, IOException exc) {
                try {
                    WatchEventSuppressor.suppress(dir, DEST_SUPPRESS_MILLIS);
                    Files.deleteIfExists(dir);
                } catch (IOException ignored) {}
                return FileVisitResult.CONTINUE;
            }
        });
    }

    public static void copyTopLevelContents(Path source, Path target) throws IOException {
        if (!Files.exists(source)) return;

        if (!Files.exists(target)) Files.createDirectories(target);

        try (DirectoryStream<Path> children = Files.newDirectoryStream(source)) {
            for (Path child : children) {
                Path dest = target.resolve(child.getFileName());
                try {
                    if (Files.isDirectory(child)) {
                        if (!Files.exists(dest)) {
                            waitForStableTreeQuiet(child);
                        }
                        WatchEventSuppressor.suppress(dest, DEST_SUPPRESS_MILLIS);
                        copyTreeSafe(child, dest);
                    } else {
                        waitForStableFileQuiet(child);
                        ensureParentDirectory(dest);
                        WatchEventSuppressor.suppress(dest, DEST_SUPPRESS_MILLIS);
                        Files.copy(child, dest,
                                StandardCopyOption.REPLACE_EXISTING,
                                StandardCopyOption.COPY_ATTRIBUTES);
                    }
                } catch (IOException ignored) {}
            }
        }
    }

    @SuppressWarnings("BusyWait")
    private static void waitForStableFileQuiet(Path file) {
        if (file == null || !Files.exists(file)) return;

        long start = System.currentTimeMillis();
        long stableStart;
        long prevSize;

        try {
            prevSize = Files.size(file);
            stableStart = System.currentTimeMillis();
        } catch (IOException e) {
            return;
        }

        while (true) {
            if (Thread.currentThread().isInterrupted()) return;

            try { Thread.sleep(200L); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); return; }

            long now = System.currentTimeMillis();
            long curSize;

            try {
                if (!Files.exists(file)) return;
                curSize = Files.size(file);
            } catch (IOException ex) {
                return;
            }

            if (curSize == prevSize) {
                if (now - stableStart >= 500L) return;
            } else {
                prevSize = curSize;
                stableStart = now;
            }

            if (now - start > 5000L) return;
        }
    }

    @SuppressWarnings("BusyWait")
    private static void waitForStableTreeQuiet(Path dir) {
        if (dir == null || !Files.exists(dir)) return;

        long start = System.currentTimeMillis();
        Map<Path, Long> lastSizes = new HashMap<>();
        Map<Path, Long> lastChange = new HashMap<>();

        try (var walk = Files.walk(dir)) {
            walk.filter(Files::isRegularFile).forEach(p -> {
                try {
                    Path rel = dir.relativize(p);
                    long size = Files.size(p);
                    lastSizes.put(rel, size);
                    lastChange.put(rel, start - 1000L);
                } catch (IOException ignored) {}
            });
        } catch (IOException ignored) {}

        while (true) {
            if (Thread.currentThread().isInterrupted()) return;

            try { Thread.sleep(300L); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); return; }

            long now = System.currentTimeMillis();
            Map<Path, Long> curr = new HashMap<>();

            try (var walk = Files.walk(dir)) {
                walk.filter(Files::isRegularFile).forEach(p -> {
                    try {
                        Path rel = dir.relativize(p);
                        long size = Files.size(p);
                        curr.put(rel, size);
                    } catch (IOException ignored) {}
                });
            } catch (IOException ignored) {}

            lastSizes.keySet().removeIf(k -> !curr.containsKey(k));
            lastChange.keySet().removeIf(k -> !curr.containsKey(k));

            for (var e : curr.entrySet()) {
                Path name = e.getKey();
                long size = e.getValue();
                Long prev = lastSizes.get(name);
                if (prev == null || prev != size) {
                    lastSizes.put(name, size);
                    lastChange.put(name, now);
                }
            }

            boolean allStable = true;
            for (var e : lastSizes.entrySet()) {
                long changedAt = lastChange.getOrDefault(e.getKey(), 0L);
                if (now - changedAt < 1000L) {
                    allStable = false;
                    break;
                }
            }

            if (allStable) return;
            if (now - start > 15000L) return;
        }
    }
}