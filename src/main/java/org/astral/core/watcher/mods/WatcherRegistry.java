package org.astral.core.watcher.mods;

import org.astral.core.process.ManagerHolder;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
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

        try {
            ModsAutoUpdater localToServer = new ModsAutoUpdater(this.managerHolder, this.localMods, this.baseServerMods, true);
            ModsWatcher watcherLocal = new ModsWatcher(this.localMods, localToServer);
            Thread tLocal = new Thread(watcherLocal, "ModsWatcher-localMods");
            tLocal.setDaemon(true);
            tLocal.start();

            handles.put(this.localMods, new WatchHandle(this.localMods, this.baseServerMods, watcherLocal, localToServer, tLocal));

            try {
                copyTopLevelIfAbsent(this.localMods, this.baseServerMods);
                System.out.println("[WATCHER] Import inicial (local -> server) completado: " + this.localMods + " -> " + this.baseServerMods);
            } catch (IOException e) {
                System.err.println("[WATCHER] Error en import inicial local -> server: " + e.getMessage());
            }

        } catch (Exception e) {
            System.err.println("[WATCHER] Error creando watcher para localMods: " + e.getMessage());
        }

        try {
            ModsAutoUpdater serverToLocal = new ModsAutoUpdater(this.managerHolder, this.baseServerMods, this.localMods, false);
            ModsWatcher watcherServer = new ModsWatcher(this.baseServerMods, serverToLocal);
            Thread tServer = new Thread(watcherServer, "ModsWatcher-baseServerMods");
            tServer.setDaemon(true);
            tServer.start();

            handles.put(this.baseServerMods, new WatchHandle(this.baseServerMods, this.localMods, watcherServer, serverToLocal, tServer));

            try {
                copyTopLevelIfAbsent(this.baseServerMods, this.localMods);
                System.out.println("[WATCHER] Import inicial (server -> local) completado: " + this.baseServerMods + " -> " + this.localMods);
            } catch (IOException e) {
                System.err.println("[WATCHER] Error en import inicial server -> local: " + e.getMessage());
            }

        } catch (Exception e) {
            System.err.println("[WATCHER] Error creando watcher para baseServerMods: " + e.getMessage());
        }
    }

    public synchronized void addWatcher(Path source) {
        try {
            source = source.toAbsolutePath().normalize();

            if (handles.containsKey(source)) {
                System.out.println("[WATCHER] Ya existe watcher registrado: " + source);
                return;
            }

            if (source.equals(localMods) || source.equals(baseServerMods)) {
                System.out.println("[WATCHER] No se puede registrar: ya existe watcher gestionado internamente: " + source);
                return;
            }

            if (!Files.exists(source)) {
                Files.createDirectories(source);
                System.out.println("[WATCHER] Directorio fuente creado: " + source);
            }

            boolean isBuildLibTrigger = source.endsWith(Path.of("build", "libs"));

            ModsAutoUpdater updater;
            Path target;

            if (isBuildLibTrigger) {
                updater = new ModsAutoUpdater(this.managerHolder, source, this.baseServerMods, true, this.localMods);
            } else {
                updater = new ModsAutoUpdater(this.managerHolder, source, this.localMods, false);
            }

            target = this.localMods;

            ModsWatcher watcher = new ModsWatcher(source, updater);

            Thread t = new Thread(
                    watcher,
                    "ModsWatcher-" + (source.getFileName() == null ? "root" : source.getFileName().toString())
            );
            t.setDaemon(true);

            handles.put(source, new WatchHandle(source, target, watcher, updater, t));

            try {
                DirectorySynchronizer.copyTopLevelContents(source, target);
                System.out.println("[WATCHER] Import inicial completado (contenido de " + source + " -> " + target + ")");
            } catch (IOException e) {
                System.err.println("[WATCHER] Error importando contenido inicial de watcher: " + e.getMessage());
            }

            t.start();

            System.out.println("[WATCHER] Watcher añadido: " + source + " -> " + target
                    + (isBuildLibTrigger ? " (build/libs trigger: copiar + reiniciar)" : " (externo, sin reinicio directo)"));

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

    private static void copyTopLevelIfAbsent(Path source, Path target) throws IOException {
        if (!Files.exists(source)) return;

        if (!Files.exists(target)) {
            Files.createDirectories(target);
        }

        try (DirectoryStream<Path> children = Files.newDirectoryStream(source)) {
            for (Path child : children) {
                Path dest = target.resolve(child.getFileName());
                try {
                    if (Files.exists(dest)) {
                        continue;
                    }

                    if (Files.isDirectory(child)) {
                        DirectorySynchronizer.copyTopLevelContents(child, dest);
                    } else {
                        if (dest.getParent() != null && !Files.exists(dest.getParent())) {
                            Files.createDirectories(dest.getParent());
                        }
                        Files.copy(child, dest, java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
                    }
                } catch (java.nio.file.NoSuchFileException nsf) {
                    System.err.println("[WATCHER] Archivo desapareció durante import: " + child);
                } catch (IOException e) {
                    System.err.println("[WATCHER] Error copiando top-level " + child + " -> " + dest + ": " + e.getMessage());
                }
            }
        }
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