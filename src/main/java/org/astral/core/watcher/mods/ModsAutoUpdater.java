package org.astral.core.watcher.mods;

import org.astral.core.process.ManagerHolder;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public class ModsAutoUpdater {

    private final ManagerHolder managerHolder;
    private final Path sourceMods;
    private final Path serverMods;
    private final boolean restartOnUpdate;
    private final Path localModsForBuildTrigger;

    private final AtomicBoolean updating = new AtomicBoolean(false);

    public ModsAutoUpdater(ManagerHolder managerHolder,
                           Path sourceMods,
                           Path serverMods,
                           boolean restartOnUpdate,
                           Path localModsForBuildTrigger) {
        this.managerHolder = managerHolder;
        this.sourceMods = sourceMods;
        this.serverMods = serverMods;
        this.restartOnUpdate = restartOnUpdate;
        this.localModsForBuildTrigger = localModsForBuildTrigger;
    }

    public ModsAutoUpdater(ManagerHolder managerHolder,
                           Path sourceMods,
                           Path serverMods,
                           boolean restartOnUpdate) {
        this(managerHolder, sourceMods, serverMods, restartOnUpdate, null);
    }

    public void triggerUpdate(List<WatchEvent<?>> events) {
        if (!updating.compareAndSet(false, true)) {
            return;
        }

        Thread t = new Thread(() -> {
            try {

                if (localModsForBuildTrigger != null) {

                    try {
                        waitForStableTopLevel(sourceMods);
                        DirectorySynchronizer.copyTopLevelContents(sourceMods, localModsForBuildTrigger);
                    } catch (IOException e) {
                        System.err.println("[MODS] Error copiando build/libs -> localMods: " + e.getMessage());
                    }

                    if (managerHolder.get() != null) {
                        managerHolder.get().stop();
                        managerHolder.get().waitForStop();
                    }

                    try {
                        DirectorySynchronizer.copyTopLevelContents(localModsForBuildTrigger, serverMods);
                    } catch (IOException e) {
                        System.err.println("[MODS] Error sincronizando localMods -> serverMods: " + e.getMessage());
                    }

                    if (managerHolder.get() != null) {
                        managerHolder.get().start();
                    }

                } else if (restartOnUpdate) {

                    if (managerHolder.get() != null) {
                        managerHolder.get().stop();
                        managerHolder.get().waitForStop();
                    }

                    try {
                        DirectorySynchronizer.copyTopLevelContents(sourceMods, serverMods);
                    } catch (IOException e) {
                        System.err.println("[MODS] Error sincronizando: " + e.getMessage());
                    }

                    if (managerHolder.get() != null) {
                        managerHolder.get().start();
                    }

                } else {

                    try {
                        DirectorySynchronizer.applyEvents(sourceMods, serverMods, events);
                    } catch (IOException e) {
                        System.err.println("[MODS] Error en sincronización incremental: " + e.getMessage());
                    }
                }

            } finally {
                updating.set(false);
            }
        }, "ModsAutoUpdater-Thread");

        t.setDaemon(true);
        t.start();
    }

    @SuppressWarnings("BusyWait")
    private static void waitForStableTopLevel(Path dir) {
        if (dir == null || !Files.exists(dir)) return;

        long start = System.currentTimeMillis();
        long now;

        Map<Path, Long> lastSizes = snapshotTopLevel(dir);
        Map<Path, Long> lastChange = new HashMap<>();

        long initStamp = start - 1000L;
        for (Path p : lastSizes.keySet()) {
            lastChange.put(p, initStamp);
        }

        while (true) {
            if (Thread.currentThread().isInterrupted()) return;

            try {
                Thread.sleep(200L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }

            now = System.currentTimeMillis();

            Map<Path, Long> curr = snapshotTopLevel(dir);

            lastSizes.keySet().removeIf(k -> !curr.containsKey(k));
            lastChange.keySet().removeIf(k -> !curr.containsKey(k));

            for (Map.Entry<Path, Long> e : curr.entrySet()) {
                Path name = e.getKey();
                long size = e.getValue();
                Long prev = lastSizes.get(name);

                if (prev == null || prev != size) {
                    lastSizes.put(name, size);
                    lastChange.put(name, now);
                }
            }

            boolean allStable = true;
            for (Map.Entry<Path, Long> e : lastSizes.entrySet()) {
                long changedAt = lastChange.getOrDefault(e.getKey(), 0L);
                if (now - changedAt < 1000L) {
                    allStable = false;
                    break;
                }
            }

            if (allStable) return;
            if (now - start > 10000L) return;
        }
    }

    private static Map<Path, Long> snapshotTopLevel(Path dir) {
        Map<Path, Long> map = new HashMap<>();

        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path child : ds) {
                if (Files.isRegularFile(child)) {
                    try {
                        map.put(child.getFileName(), Files.size(child));
                    } catch (IOException ignored) {
                    }
                }
            }
        } catch (IOException ignored) {
        }

        return map;
    }
}