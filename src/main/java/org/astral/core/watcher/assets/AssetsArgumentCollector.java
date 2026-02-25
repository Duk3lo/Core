package org.astral.core.watcher.assets;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

public class AssetsArgumentCollector {

    public static @NotNull List<String> collect(Path assetsDir) throws IOException {

        List<String> args = new ArrayList<>();

        if (!Files.exists(assetsDir)) {
            Files.createDirectories(assetsDir);
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(assetsDir, "*.zip")) {
            for (Path zip : stream) {
                Path absolute = zip.toAbsolutePath().normalize();
                args.add("--assets");
                args.add(absolute.toString());
                System.out.println("["+ args +"]");
            }
        }

        return args;
    }
}