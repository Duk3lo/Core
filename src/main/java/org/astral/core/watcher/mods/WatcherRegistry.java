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
    private final Path localMods;
    private final Map<Path, WatchHandle> handles = new ConcurrentHashMap<>();

    public WatcherRegistry(ManagerHolder managerHolder, @NotNull Path baseServerMods, @NotNull Path localMods) {
        this.managerHolder = managerHolder;
        this.baseServerMods = baseServerMods.toAbsolutePath().normalize();
        this.localMods = localMods.toAbsolutePath().normalize();

        try {
            if (!Files.exists(this.localMods)) {
                Files.createDirectories(this.localMods);
                System.out.println("[WATCHER] localMods creado: " + this.localMods);
            }
        } catch (IOException e) {
            System.err.println("[WATCHER] No se pudo crear localMods: " + e.getMessage());
        }

        try {
            if (!Files.exists(this.baseServerMods)) {
                Files.createDirectories(this.baseServerMods);
                System.out.println("[WATCHER] baseServerMods creado: " + this.baseServerMods);
            }
        } catch (IOException e) {
            System.err.println("[WATCHER] No se pudo crear baseServerMods: " + e.getMessage());
        }

        // Crear y arrancar el watcher "clásico" que vigila localMods y sincroniza con baseServerMods.
        // Este watcher puede reiniciar el servidor cuando detecta cambios.
        try {
            ModsAutoUpdater updater = new ModsAutoUpdater(this.managerHolder, this.localMods, this.baseServerMods, true);
            ModsWatcher watcher = new ModsWatcher(this.localMods, updater);
            Thread t = new Thread(watcher, "ModsWatcher-localMods");
            t.setDaemon(true);
            t.start();

            handles.put(this.localMods, new WatchHandle(this.localMods, this.baseServerMods, watcher, updater, t));

            // Sync inicial (localMods -> serverMods)
            try {
                DirectorySynchronizer.replaceSync(this.localMods, this.baseServerMods);
                System.out.println("[WATCHER] Sync inicial completada (localMods -> serverMods): " + this.localMods + " -> " + this.baseServerMods);
            } catch (IOException e) {
                System.err.println("[WATCHER] Error en sync inicial localMods -> serverMods:");
            }

        } catch (Exception e) {
            System.err.println("[WATCHER] Error creando watcher para localMods: " + e.getMessage());
        }
    }

    public synchronized void addWatcher(Path source) {
        try {
            source = source.toAbsolutePath().normalize();

            if (handles.containsKey(source)) {
                System.out.println("[WATCHER] Ya existe watcher registrado: " + source);
                return;
            }

            if (!Files.exists(source)) {
                Files.createDirectories(source);
                System.out.println("[WATCHER] Directorio fuente creado: " + source);
            }

            // Si el watcher apunta dentro de la carpeta del servidor, avisamos pero lo tratamos
            // como watcher de usuario (no reiniciará).
            if (source.startsWith(baseServerMods)) {
                System.out.println("[WATCHER] Atención: la ruta del watcher apunta dentro de la carpeta del servidor: " + source);
                System.out.println("[WATCHER] Se registrará como watcher de usuario (no reiniciará ni reemplazará serverMods).");
            }

            // Target para watchers de usuario: siempre localMods (merge / import hacia localMods)
            Path target = localMods;
            boolean restartOnUpdate = false;

            // Aseguramos target
            Files.createDirectories(target);

            ModsAutoUpdater updater = new ModsAutoUpdater(managerHolder, source, target, restartOnUpdate);
            ModsWatcher watcher = new ModsWatcher(source, updater);

            Thread t = new Thread(watcher, "ModsWatcher-" + (source.getFileName() == null ? "root" : source.getFileName().toString()));
            t.setDaemon(true);
            t.start();

            handles.put(source, new WatchHandle(source, target, watcher, updater, t));

            try {
                DirectorySynchronizer.copyTopLevelContents(source, target);
                System.out.println("[WATCHER] Import inicial completado (contenido de " + source + " -> " + target + ")");
            } catch (IOException e) {
                System.err.println("[WATCHER] Error importando contenido inicial de watcher:");
            }

            System.out.println("[WATCHER] Watcher de usuario añadido (eventos hacia localMods): " + source + " -> " + target);

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