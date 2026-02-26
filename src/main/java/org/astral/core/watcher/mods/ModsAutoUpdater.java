package org.astral.core.watcher.mods;

import org.astral.core.process.ManagerHolder;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class ModsAutoUpdater {

    private final ManagerHolder managerHolder;
    private final Path sourceMods;
    private final Path serverMods; // target donde se aplican los cambios (normalmente serverMods)
    private final boolean restartOnUpdate;

    // cuando este valor != null indica que este updater corresponde a un watcher de tipo build/libs;
    // en ese caso se copiará top-level sourceMods -> localModsForBuildTrigger, luego se forzará
    // una sincronización localModsForBuildTrigger -> serverMods y se reiniciará el servidor.
    private final Path localModsForBuildTrigger; // nullable

    private final AtomicBoolean updating = new AtomicBoolean(false);

    /**
     * Constructor general.
     *
     * @param managerHolder               managerHolder
     * @param sourceMods                  ruta origen (el watcher vigila esto)
     * @param serverMods                  ruta destino final (normalmente carpeta mods del servidor)
     * @param restartOnUpdate             si true: realiza un reemplazo completo y reinicia (comportamiento del watcher local -> server)
     * @param localModsForBuildTrigger    si no-nulo: indica que este updater es un trigger de build/libs; se usará como localMods temporal
     */
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

    /**
     * Compat constructor (sin build trigger).
     */
    public ModsAutoUpdater(ManagerHolder managerHolder, Path sourceMods, Path serverMods, boolean restartOnUpdate) {
        this(managerHolder, sourceMods, serverMods, restartOnUpdate, null);
    }

    /**
     * recibe eventos; comportamiento:
     * - si local build trigger (localModsForBuildTrigger != null) -> copyTopLevel(source -> localMods), luego replaceSync(localMods -> serverMods) y reinicio
     * - else si restartOnUpdate==true -> replaceSync(source -> serverMods) + reinicio (uso original para localMods watcher)
     * - else -> applyEvents(source -> serverMods) (incremental, sin reinicio)
     */
    public void triggerUpdate(List<WatchEvent<?>> events) {

        if (!updating.compareAndSet(false, true)) {
            return;
        }

        Thread t = new Thread(() -> {
            try {

                if (localModsForBuildTrigger != null) {
                    // Caso especial: watcher en build/libs
                    System.out.println("[MODS] Cambios detectados (build/libs). Copiando a " + localModsForBuildTrigger + " y reiniciando servidor...");

                    try {
                        // 1) copiar top-level from build/libs -> localMods (no asumimos que esto provoque reinicio porque se suprimen eventos)
                        DirectorySynchronizer.copyTopLevelContents(sourceMods, localModsForBuildTrigger);
                        System.out.println("[MODS] Copia build/libs -> localMods completada.");
                    } catch (IOException e) {
                        System.err.println("[MODS] Error copiando build/libs -> localMods: " + e.getMessage());
                    }

                    // 2) forzar parada del servidor y sincronizar localMods -> serverMods (reemplazo completo)
                    if (managerHolder.get() != null) {
                        managerHolder.get().stop();
                        managerHolder.get().waitForStop();
                    }

                    try {
                        DirectorySynchronizer.replaceSync(localModsForBuildTrigger, serverMods);
                        System.out.println("[MODS] Sincronización localMods -> serverMods completada (build trigger).");
                    } catch (IOException e) {
                        System.err.println("[MODS] Error sincronizando localMods -> serverMods (build trigger): " + e.getMessage());
                    }

                    if (managerHolder.get() != null) {
                        managerHolder.get().start();
                    }

                    System.out.println("[MODS] Reinicio completado (build trigger).");

                } else if (restartOnUpdate) {
                    // caso normal: watcher sobre localMods -> serverMods (reemplazo completo + reinicio)
                    System.out.println("[MODS] Cambios detectados (watcher local). Copiando a server y reiniciando...");

                    if (managerHolder.get() != null) {
                        managerHolder.get().stop();
                        managerHolder.get().waitForStop();
                    }

                    try {
                        DirectorySynchronizer.replaceSync(sourceMods, serverMods);
                        System.out.println("[MODS] Sincronización completada (pushToServer).");
                    } catch (IOException e) {
                        System.err.println("[MODS] Error sincronizando (pushToServer): " + e.getMessage());
                    }

                    if (managerHolder.get() != null) {
                        managerHolder.get().start();
                    }

                    System.out.println("[MODS] Reinicio completado.");
                } else {
                    // comportamiento por defecto para watchers de usuario: aplicar eventos incrementalmente (sin reinicio)
                    System.out.println("[MODS] Cambios detectados (watcher de usuario). Aplicando cambios al target sin reiniciar...");

                    try {
                        DirectorySynchronizer.applyEvents(sourceMods, serverMods, events);
                        System.out.println("[MODS] Sincronización incremental completada.");
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