package org.astral.core;

import org.astral.core.cli.BackendConsole;
import org.astral.core.config.Config;
import org.astral.core.config.ConfigLoader;
import org.astral.core.process.JarProcessManager;
import org.astral.core.process.ManagerHolder;
import org.astral.core.updates.github.GithubService;
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

class Main {

    static void main() {

        Scanner scanner = new Scanner(System.in);

        Config config = ConfigLoader.load();
        if (config == null) config = new Config();
        if (config.server == null) config.server = new Config.Server();
        if (config.watchers == null) config.watchers = new ArrayList<>();

        Path basePath = resolveServerPath(config, scanner);

        Path baseDir = Path.of(System.getProperty("user.dir"));
        Path localMods = baseDir.resolve("mods");
        Path localAssets = baseDir.resolve("assets");

        try {
            Files.createDirectories(localMods);
            Files.createDirectories(localAssets);
        } catch (IOException e) {
            System.out.println("[MAIN] Error creando directorios locales: " + e.getMessage());
            return;
        }

        // Inicializar UpdaterService (y generar updates.yml si no existe)
        GithubService githubService = null;
        Path updatesGithubFile = baseDir.resolve("githubUpdates.yml");
        try {
            githubService = new GithubService(updatesGithubFile, localMods, localAssets);
            System.out.println("[MAIN] UpdaterService inicializado.");
        } catch (IOException e) {
            System.err.println("[MAIN] No se pudo inicializar UpdaterService: " + e.getMessage());
        }

        if (config.server.jarName == null) config.server.jarName = "";

        Path serverMods = basePath.resolve("Server").resolve("mods");
        Path jarPath = basePath.resolve("Server").resolve(config.server.jarName);

        List<String> baseArgs = buildArgs(config.server.args);

        JarProcessManager manager = new JarProcessManager(jarPath.toString(), localAssets, baseArgs);

        ManagerHolder managerHolder = new ManagerHolder(manager);

        WatcherRegistry watcherRegistry = new WatcherRegistry(managerHolder, serverMods, localMods);

        /* ================= CARGA WATCHERS DESDE CONFIG ================= */

        if (!config.watchers.isEmpty()) {

            for (Config.Watcher w : config.watchers) {

                if (w.path == null || w.path.isBlank()) {
                    System.out.println("[WATCHER] Ruta vacía en config, ignorando.");
                    continue;
                }

                Path watcherPath = Path.of(w.path);

                if (!Files.exists(watcherPath)) {
                    System.out.println("[WATCHER] No se pudo encontrar la ruta: " + watcherPath);
                    continue;
                }

                try {
                    watcherRegistry.addWatcher(watcherPath);
                    System.out.println("[WATCHER] Cargado watcher: " + watcherPath);
                } catch (Exception e) {
                    System.out.println("[WATCHER] Error registrando watcher "
                            + watcherPath + ": " + e.getMessage());
                }
            }

        }

        /* ================= SHUTDOWN LIMPIO ================= */

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("[SHUTDOWN] Cerrando aplicación...");
            watcherRegistry.shutdownAll();
            manager.stop();
        }));

        /* ================= INICIAR PROCESO ================= */

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

        // construir BackendConsole (asegúrate la clase ya acepta UpdaterService)
        BackendConsole console = new BackendConsole(
                managerHolder,
                watcherRegistry,
                config,
                null,
                () -> {
                    watcherRegistry.shutdownAll();
                    manager.stop();
                },
                githubService // ahora la clase acepta este argumento
        );

        if (githubService != null) {
            final GithubService us = githubService;
            new Thread(() -> {
                System.out.println("[MAIN] Iniciando comprobación inicial de actualizaciones...");
                us.checkAllAndDownload();
                System.out.println("[MAIN] Comprobación inicial de actualizaciones finalizada.");
            }, "InitialUpdatesCheck").start();
        }

        console.startListening();

        System.out.println("[MAIN] Programa finalizado.");
    }

    /* ================= RESOLVER RUTA SERVER ================= */

    private static @NotNull Path resolveServerPath(@NotNull Config config, Scanner scanner) {

        String raw = config.server.basePath != null
                ? config.server.basePath.trim()
                : "";

        Path configuredPath = raw.isEmpty() ? null : Path.of(raw);

        if (configuredPath != null && Files.exists(configuredPath)) {
            System.out.println("[MAIN] Usando ruta del config: " + configuredPath);
            return configuredPath;
        }

        System.out.println("[MAIN] Ruta inválida.");
        System.out.print("[MAIN] Ingrese ruta del servidor: ");

        String input = scanner.nextLine().trim();

        Path basePath;

        if (input.isEmpty()) {
            basePath = Path.of(System.getProperty("user.dir"));
        } else {
            basePath = Path.of(input);
            if (!Files.exists(basePath)) {
                try {
                    Files.createDirectories(basePath);
                } catch (IOException e) {
                    basePath = Path.of(System.getProperty("user.dir"));
                }
            }
        }

        config.server.basePath = basePath.toString();
        ConfigLoader.save(config);

        return basePath;
    }

    /* ================= BUILD ARGS ================= */

    private static @NotNull List<String> buildArgs(String rawArgs) {

        if (rawArgs != null && !rawArgs.trim().isEmpty()) {
            return ConfigLoader.splitArgs(rawArgs);
        }

        List<String> defaults = new ArrayList<>();
        defaults.add("--assets");
        defaults.add("../Assets.zip");
        defaults.add("--backup");
        defaults.add("--backup-dir");
        defaults.add("backups");
        defaults.add("--backup-frequency");
        defaults.add("30");

        return defaults;
    }
}