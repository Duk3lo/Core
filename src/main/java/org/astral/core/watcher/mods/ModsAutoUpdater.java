package org.astral.core.watcher.mods;

import org.astral.core.process.ManagerHolder;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

public class ModsAutoUpdater {

    private final ManagerHolder managerHolder;
    private final Path sourceMods;
    private final Path serverMods;

    private final AtomicBoolean updating = new AtomicBoolean(false);

    public ModsAutoUpdater(ManagerHolder managerHolder, Path sourceMods, Path serverMods) {
        this.managerHolder = managerHolder;
        this.sourceMods = sourceMods;
        this.serverMods = serverMods;
    }

    public void triggerUpdate() {

        if (!updating.compareAndSet(false, true)) {
            return;
        }

        Thread t = new Thread(() -> {
            try {
                System.out.println("[MODS] Cambios detectados. Reiniciando servidor...");

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

            } finally {
                updating.set(false);
            }
        }, "ModsAutoUpdater-Thread");

        t.setDaemon(true);
        t.start();
    }
}