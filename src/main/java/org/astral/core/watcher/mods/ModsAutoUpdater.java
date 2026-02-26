package org.astral.core.watcher.mods;

import org.astral.core.process.ManagerHolder;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class ModsAutoUpdater {

    private final ManagerHolder managerHolder;
    private final Path sourceMods;
    private final Path serverMods;
    private final boolean restartOnUpdate;

    private final AtomicBoolean updating = new AtomicBoolean(false);

    public ModsAutoUpdater(ManagerHolder managerHolder, Path sourceMods, Path serverMods, boolean restartOnUpdate) {
        this.managerHolder = managerHolder;
        this.sourceMods = sourceMods;
        this.serverMods = serverMods;
        this.restartOnUpdate = restartOnUpdate;
    }

    // recibe eventos; si restartOnUpdate==true hace replaceSync + reinicio,
    // si false aplica eventos incrementalmente al target (normalmente localMods) y NO reinicia.
    public void triggerUpdate(List<WatchEvent<?>> events) {

        if (!updating.compareAndSet(false, true)) {
            return;
        }

        Thread t = new Thread(() -> {
            try {
                if (restartOnUpdate) {
                    System.out.println("[MODS] Cambios detectados (localMods watcher). Reiniciando servidor...");

                    if (managerHolder.get() != null) {
                        managerHolder.get().stop();
                        managerHolder.get().waitForStop();
                    }

                    try {
                        DirectorySynchronizer.replaceSync(sourceMods, serverMods);
                        System.out.println("[MODS] Sincronización completada.");
                    } catch (IOException e) {
                        System.err.println("[MODS] Error sincronizando: " + e.getMessage());
                    }

                    if (managerHolder.get() != null) {
                        managerHolder.get().start();
                    }

                    System.out.println("[MODS] Reinicio completado.");
                } else {
                    System.out.println("[MODS] Cambios detectados (watcher de usuario). Aplicando cambios a localMods sin reiniciar...");

                    try {
                        DirectorySynchronizer.applyEvents(sourceMods, serverMods, events);
                        System.out.println("[MODS] Sincronización incremental completada (hacia localMods).");
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
}