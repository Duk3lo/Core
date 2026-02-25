package org.astral.core.cli;

import org.astral.core.process.JarProcessManager;
import org.astral.core.process.ManagerHolder;
import org.astral.core.watcher.mods.WatcherRegistry;

import java.nio.file.Path;
import java.util.Map;
import java.util.Scanner;

public class BackendConsole {

    private final ManagerHolder managerHolder;
    private final WatcherRegistry watcherRegistry;
    private final Runnable reloadConfigCallback;
    private final Runnable shutdownCallback;

    public BackendConsole(ManagerHolder managerHolder,
                          WatcherRegistry watcherRegistry,
                          Runnable reloadConfigCallback,
                          Runnable shutdownCallback) {
        this.managerHolder = managerHolder;
        this.watcherRegistry = watcherRegistry;
        this.reloadConfigCallback = reloadConfigCallback;
        this.shutdownCallback = shutdownCallback;
    }

    public void startListening() {

        System.out.println("""
                [BACKEND] Consola lista. Comandos:
                  watch add <ruta> [tipo]
                  watch remove <ruta>
                  watch list
                  backend send <comando>
                  backend reload-config
                  exit / quit
                """);

        try (Scanner scanner = new Scanner(System.in)) {

            while (true) {

                String input;

                try {
                    input = scanner.nextLine();
                } catch (Exception e) {
                    break;
                }

                if (input == null) continue;
                input = input.trim();
                if (input.isEmpty()) continue;

                if (input.equalsIgnoreCase("exit") || input.equalsIgnoreCase("quit")) {
                    System.out.println("[BACKEND] Cerrando...");
                    if (shutdownCallback != null) shutdownCallback.run();
                    break;
                }

                if (input.startsWith("backend send ")) {
                    JarProcessManager manager = managerHolder.get();
                    if (manager == null || !manager.isRunning()) {
                        System.out.println("[BACKEND] Servidor no está corriendo.");
                        continue;
                    }
                    manager.sendCommand(input.substring("backend send ".length()));
                    continue;
                }

                if (input.equalsIgnoreCase("backend reload-config")) {
                    if (reloadConfigCallback != null) reloadConfigCallback.run();
                    continue;
                }

                if (input.startsWith("watch list")) {
                    Map<Path, Path> watchers = watcherRegistry.listWatchers();
                    if (watchers.isEmpty()) {
                        System.out.println("[BACKEND] No hay watchers.");
                    } else {
                        watchers.forEach((s, t) ->
                                System.out.println(s + " -> " + t));
                    }
                    continue;
                }

                System.out.println("[BACKEND] Comando inválido.");
            }
        }

        System.out.println("[BACKEND] Consola finalizada.");
    }
}