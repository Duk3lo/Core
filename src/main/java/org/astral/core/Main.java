package org.astral.core;

import org.astral.core.cli.BackendConsole;
import org.astral.core.config.Config;
import org.astral.core.config.ConfigLoader;
import org.astral.core.process.JarProcessManager;
import org.astral.core.process.ManagerHolder;
import org.astral.core.watcher.assets.AssetsWatcher;
import org.astral.core.watcher.mods.DirectorySynchronizer;
import org.astral.core.watcher.mods.WatcherRegistry;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

public class Main {

    static void main() {

        Scanner scanner = new Scanner(System.in);
        scanner.nextLine();

        Path baseDir = Path.of(System.getProperty("user.dir"));
        Path localMods = baseDir.resolve("mods");
        Path localAssets = baseDir.resolve("assets");

        try {
            Files.createDirectories(localMods);
            Files.createDirectories(localAssets);
        } catch (IOException e) {
            System.out.println("[MAIN] Error creando directorios: " + e.getMessage());
            return;
        }

        Config config = ConfigLoader.load();
        Path basePath = Path.of(config.server.basePath);

        Path serverMods = basePath.resolve("Server").resolve("mods");
        Path jarPath = basePath.resolve("Server").resolve(config.server.jarName);

        List<String> baseArgs = new ArrayList<>();
        if (config.server.args != null && !config.server.args.trim().isEmpty()) {
            baseArgs.addAll(parseArgsLine(config.server.args));
        } else {
            baseArgs.add("--assets");
            baseArgs.add("../Assets.zip");
            baseArgs.add("--backup");
            baseArgs.add("--backup-dir");
            baseArgs.add("backups");
            baseArgs.add("--backup-frequency");
            baseArgs.add("30");
        }

        JarProcessManager manager = new JarProcessManager(
                jarPath.toString(),
                localAssets,
                baseArgs
        );

        ManagerHolder managerHolder = new ManagerHolder(manager);
        WatcherRegistry watcherRegistry = new WatcherRegistry(managerHolder, serverMods);

        try {
            watcherRegistry.addWatcher(localMods, "mods");
        } catch (IOException e) {
            System.err.println("[MAIN] No se pudo añadir watcher para " + localMods + ": " + e.getMessage());
        }

        manager.start();

        manager.start();


        Thread assetsThread = new Thread(
                new AssetsWatcher(localAssets, managerHolder),
                "AssetsWatcher"
        );
        assetsThread.setDaemon(true);
        assetsThread.start();

        try {
            DirectorySynchronizer.replaceSync(localMods, serverMods);
            System.out.println("[MAIN] Sync inicial de mods completada.");
        } catch (IOException e) {
            System.out.println("[MAIN] Error sincronizando mods: " + e.getMessage());
        }

        BackendConsole console = new BackendConsole(
                managerHolder,
                watcherRegistry,
                null,
                manager::stop
        );

        console.startListening();

        System.out.println("[MAIN] Programa finalizado.");
    }


    private static @NotNull List<String> parseArgsLine(String line) {
        List<String> result = new ArrayList<>();
        if (line == null || line.trim().isEmpty()) return result;
        StringBuilder cur = new StringBuilder();
        boolean inDouble = false;
        boolean inSingle = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"' && !inSingle) {
                inDouble = !inDouble;
                continue;
            }
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
                continue;
            }
            if (Character.isWhitespace(c) && !inDouble && !inSingle) {
                if (!cur.isEmpty()) {
                    result.add(cur.toString());
                    cur.setLength(0);
                }
            } else {
                cur.append(c);
            }
        }
        if (!cur.isEmpty()) result.add(cur.toString());
        return result;
    }
}