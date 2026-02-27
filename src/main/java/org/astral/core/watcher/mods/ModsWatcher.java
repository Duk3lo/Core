package org.astral.core.watcher.mods;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class ModsWatcher implements Runnable {

    final Path modsPath;
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

                List<WatchEvent<?>> rawEvents = key.pollEvents();
                if (rawEvents.isEmpty()) {
                    boolean valid = key.reset();
                    if (!valid) break;
                    continue;
                }
                boolean hasOverflow = rawEvents.stream().anyMatch(ev -> ev.kind() == StandardWatchEventKinds.OVERFLOW);
                if (hasOverflow) {
                    updater.triggerUpdate(rawEvents);
                    boolean valid = key.reset();
                    if (!valid) break;
                    continue;
                }
                List<WatchEvent<?>> filtered = new ArrayList<>();
                for (WatchEvent<?> ev : rawEvents) {
                    Object ctx = ev.context();
                    if (ctx instanceof Path rel) {
                        Path abs = modsPath.resolve(rel).toAbsolutePath().normalize();
                        if (!WatchEventSuppressor.isSuppressed(abs)) {
                            filtered.add(ev);
                        }
                    } else {
                        filtered.add(ev);
                    }
                }

                if (!filtered.isEmpty()) {
                    updater.triggerUpdate(filtered);
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