package org.astral.core.watcher.mods;

import org.astral.core.process.ManagerHolder;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class WatcherRegistry {

    private final ManagerHolder managerHolder;
    private volatile Path baseServerMods;
    private final Map<Path, WatchHandle> handles = new ConcurrentHashMap<>();

    public WatcherRegistry(ManagerHolder managerHolder, @NotNull Path baseServerMods) {
        this.managerHolder = managerHolder;
        this.baseServerMods = baseServerMods.toAbsolutePath().normalize();
    }

    public synchronized void addWatcher(Path source, String type) throws IOException {
        source = source.toAbsolutePath().normalize();

        if (handles.containsKey(source)) {
            System.out.println("[WATCHER] Ya existe watcher: " + source);
            return;
        }

        if (!"mods".equalsIgnoreCase(type)) {
            throw new IllegalArgumentException("Tipo no soportado: " + type);
        }

        if (!Files.exists(source)) {
            Files.createDirectories(source);
            System.out.println("[WATCHER] Directorio fuente creado: " + source);
        }

        Path folderName = source.getFileName();
        if (folderName == null) {
            throw new IllegalArgumentException("Ruta fuente inválida: " + source);
        }

        Path target = baseServerMods.resolve(folderName.toString()).toAbsolutePath().normalize();
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

    public synchronized void updateBaseServerMods(@NotNull Path newBaseServerMods) {
        this.baseServerMods = newBaseServerMods.toAbsolutePath().normalize();
        System.out.println("[WATCHER] Nueva baseServerMods: " + this.baseServerMods);

        handles.forEach((src, handle) -> {
            try {
                Path newTarget = this.baseServerMods
                        .resolve(src.getFileName().toString())
                        .toAbsolutePath()
                        .normalize();

                Files.createDirectories(newTarget);
                handle.updater.setServerMods(newTarget);
                handle.target = newTarget;

                DirectorySynchronizer.replaceSync(src, newTarget);
                System.out.println("[WATCHER] Resync completado: " + src);

            } catch (IOException e) {
                System.err.println("[WATCHER] Error actualizando target: " + e.getMessage());
            }
        });
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