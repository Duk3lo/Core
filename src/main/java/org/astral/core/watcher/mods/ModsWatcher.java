package org.astral.core.watcher.mods;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class ModsWatcher implements Runnable {

    private final Path modsPath;
    private final ModsAutoUpdater updater;

    public ModsWatcher(Path modsPath, ModsAutoUpdater updater) {
        this.modsPath = modsPath;
        this.updater = updater;
    }

    @Override
    public void run() {

        WatchService watchService = null;

        try {

            if (Files.notExists(modsPath)) {
                Files.createDirectories(modsPath);
                System.out.println("[MODS] Directorio creado: " + modsPath);
            }

            watchService = FileSystems.getDefault().newWatchService();

            modsPath.register(
                    watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_DELETE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.OVERFLOW
            );

            System.out.println("[MODS] Watcher activo en: " + modsPath);

            while (!Thread.currentThread().isInterrupted()) {

                WatchKey key;

                try {
                    key = watchService.poll(1, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }

                if (key == null) {
                    continue;
                }

                List<WatchEvent<?>> events = key.pollEvents();

                if (!events.isEmpty()) {
                    updater.triggerUpdate(events);
                }

                boolean valid = key.reset();
                if (!valid) {
                    break;
                }
            }

            System.out.println("[MODS] Watcher finalizado: " + modsPath);

        } catch (IOException e) {
            System.err.println("[MODS] Error en watcher: " + e.getMessage());
        } finally {
            if (watchService != null) {
                try {
                    watchService.close();
                } catch (IOException ignored) {}
            }
        }
    }
}