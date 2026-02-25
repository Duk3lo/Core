package org.astral.core.watcher.assets;

import org.astral.core.process.ManagerHolder;

import java.nio.file.*;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class AssetsWatcher implements Runnable {

    private final Path assetsDir;
    private final ManagerHolder managerHolder;

    public AssetsWatcher(Path assetsDir, ManagerHolder managerHolder) {
        this.assetsDir = assetsDir;
        this.managerHolder = managerHolder;
    }

    @Override
    public void run() {

        try {

            if (!Files.exists(assetsDir)) {
                Files.createDirectories(assetsDir);
            }

            WatchService watchService = FileSystems.getDefault().newWatchService();

            assetsDir.register(
                    watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_DELETE,
                    StandardWatchEventKinds.ENTRY_MODIFY
            );

            System.out.println("[ASSETS] Watcher activo en: " + assetsDir);

            while (!Thread.currentThread().isInterrupted()) {

                WatchKey key = watchService.poll(1, TimeUnit.SECONDS);
                if (key == null) continue;

                boolean changed = false;

                for (WatchEvent<?> event : key.pollEvents()) {
                    Path changedFile = (Path) event.context();
                    if (changedFile.toString().endsWith(".zip")) {
                        changed = true;
                        break;
                    }
                }

                if (changed) {
                    boolean stable = waitForStableZipFiles(assetsDir);
                    if (stable) {
                        reloadServer();
                    } else {
                        System.out.println("[ASSETS] Archivos .zip no estabilizados en el tiempo esperado; no se reinicia.");
                    }
                }

                key.reset();
            }

        } catch (Exception e) {
            System.out.println("[ASSETS] Error: " + e.getMessage());
        }
    }

    private boolean waitForStableZipFiles(Path dir) {
        long start = System.currentTimeMillis();
        Map<Path, Long> lastSizes = new HashMap<>();

        try {
            while (System.currentTimeMillis() - start < (long) 10000) {
                boolean allStable = true;

                try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.zip")) {
                    for (Path p : stream) {
                        long size = Files.size(p);
                        Long prev = lastSizes.get(p);
                        if (prev == null || prev != size) {
                            lastSizes.put(p, size);
                            allStable = false;
                        }
                    }
                }

                if (allStable && !lastSizes.isEmpty()) {
                    return true;
                }

                TimeUnit.MILLISECONDS.sleep((long) 500);
            }
        } catch (Exception e) {
            System.out.println("[ASSETS] Error comprobando estabilidad: " + e.getMessage());
        }
        return false;
    }

    private void reloadServer() {
        try {
            System.out.println("[ASSETS] Cambio detectado y archivos estables. Reiniciando servidor...");

            if (managerHolder.get() != null) {
                managerHolder.get().restart();
            }

        } catch (Exception e) {
            System.out.println("[ASSETS] Error reiniciando: " + e.getMessage());
        }
    }
}