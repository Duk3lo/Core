package org.astral.core.watcher.mods;

import java.io.IOException;
import java.nio.file.*;
import java.util.Comparator;
import java.util.stream.Stream;

public final class DirectorySynchronizer {

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
        try (Stream<Path> stream = Files.walk(source)) {
            stream.forEach(srcPath -> {
                try {
                    Path relative = source.relativize(srcPath);
                    Path destPath = target.resolve(relative);

                    if (Files.isDirectory(srcPath)) {
                        if (!Files.exists(destPath)) {
                            Files.createDirectories(destPath);
                        }
                    } else {
                        if (destPath.getParent() != null && !Files.exists(destPath.getParent())) {
                            Files.createDirectories(destPath.getParent());
                        }
                        Files.copy(srcPath, destPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                    }
                } catch (IOException e) {
                    throw new RuntimeException("Error copiando " + srcPath + " -> " + e.getMessage(), e);
                }
            });
        }
    }

    private static void deleteChildren(Path target) throws IOException {
        try (Stream<Path> children = Files.list(target)) {
            children.forEach(path -> {
                try {
                    deleteRecursively(path);
                } catch (IOException e) {
                    throw new RuntimeException("Error borrando " + path + ": " + e.getMessage(), e);
                }
            });
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException e) {
                            throw new RuntimeException("No se pudo eliminar " + p + ": " + e.getMessage(), e);
                        }
                    });
        }
    }
}