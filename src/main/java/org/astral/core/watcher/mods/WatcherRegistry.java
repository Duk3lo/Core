package org.astral.core.watcher.mods;

import org.astral.core.process.ManagerHolder;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class WatcherRegistry {

    private final ManagerHolder managerHolder;
    private final Path baseServerMods;
    private final Map<Path, WatchHandle> handles = new ConcurrentHashMap<>();

    public WatcherRegistry(ManagerHolder managerHolder, @NotNull Path baseServerMods) {
        this.managerHolder = managerHolder;
        this.baseServerMods = baseServerMods.toAbsolutePath().normalize();
    }

    public synchronized void addWatcher(Path source) {
        try {
            source = source.toAbsolutePath().normalize();

            if (handles.containsKey(source)) {
                System.out.println("[WATCHER] Ya existe watcher: " + source);
                return;
            }

            if (!Files.exists(source)) {
                Files.createDirectories(source);
                System.out.println("[WATCHER] Directorio fuente creado: " + source);
            }

            Path folderName = source.getFileName();
            if (folderName == null) {
                System.out.println("[WATCHER] Ruta inválida: " + source);
                return;
            }

            Path target = baseServerMods
                    .resolve(folderName.toString())
                    .toAbsolutePath()
                    .normalize();

            Files.createDirectories(target);

            ModsAutoUpdater updater = new ModsAutoUpdater(managerHolder, source, target);
            ModsWatcher watcher = new ModsWatcher(source, updater);

            Thread t = new Thread(watcher, "ModsWatcher-" + folderName);
            t.setDaemon(true);
            t.start();

            handles.put(source, new WatchHandle(source, target, watcher, updater, t));

            try {
                DirectorySynchronizer.replaceSync(source, target);
                System.out.println("[WATCHER] Sync inicial completada: " + source + " -> " + target);
            } catch (IOException e) {
                System.err.println("[WATCHER] Error en sync inicial: " + e.getMessage());
            }

            System.out.println("[WATCHER] Watcher añadido: " + source + " -> " + target);

        } catch (Exception e) {
            System.err.println("[WATCHER] Error añadiendo watcher: " + e.getMessage());
        }
    }

    public synchronized boolean removeWatcher(Path source) {
        source = source.toAbsolutePath().normalize();

        WatchHandle h = handles.remove(source);
        if (h == null) {
            System.out.println("[WATCHER] No existe watcher: " + source);
            return false;
        }

        h.thread.interrupt();
        try {
            h.thread.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        System.out.println("[WATCHER] Watcher removido: " + source);
        return true;
    }

    public synchronized Map<Path, Path> listWatchers() {
        Map<Path, Path> m = new ConcurrentHashMap<>();
        handles.forEach((k, v) -> m.put(k, v.target));
        return m;
    }

    public synchronized void shutdownAll() {
        System.out.println("[WATCHER] Cerrando todos los watchers...");

        handles.forEach((_, h) -> h.thread.interrupt());

        handles.forEach((_, h) -> {
            try {
                h.thread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        handles.clear();
        System.out.println("[WATCHER] Todos los watchers finalizados.");
    }

    private static class WatchHandle {
        final Path source;
        volatile Path target;
        final ModsWatcher watcher;
        final ModsAutoUpdater updater;
        final Thread thread;

        WatchHandle(Path source, Path target,
                    ModsWatcher watcher,
                    ModsAutoUpdater updater,
                    Thread thread) {
            this.source = source;
            this.target = target;
            this.watcher = watcher;
            this.updater = updater;
            this.thread = thread;
        }
    }
}