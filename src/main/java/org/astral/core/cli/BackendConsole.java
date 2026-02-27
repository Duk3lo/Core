package org.astral.core.cli;

import org.astral.core.config.Config;
import org.astral.core.config.ConfigLoader;
import org.astral.core.process.JarProcessManager;
import org.astral.core.process.ManagerHolder;
import org.astral.core.updates.github.GithubService;
import org.astral.core.updates.github.GithubConfig;
import org.astral.core.watcher.mods.WatcherRegistry;

import java.nio.file.Path;
import java.util.Iterator;
import java.util.Map;
import java.util.Scanner;

public class BackendConsole {

    private final ManagerHolder managerHolder;
    private final WatcherRegistry watcherRegistry;
    private final Config config;
    private final Runnable reloadConfigCallback;
    private final Runnable shutdownCallback;
    private final GithubService githubService;

    public BackendConsole(ManagerHolder managerHolder,
                          WatcherRegistry watcherRegistry,
                          Config config,
                          Runnable reloadConfigCallback,
                          Runnable shutdownCallback,
                          GithubService githubService) {
        this.managerHolder = managerHolder;
        this.watcherRegistry = watcherRegistry;
        this.config = config;
        this.reloadConfigCallback = reloadConfigCallback;
        this.shutdownCallback = shutdownCallback;
        this.githubService = githubService;
    }

    public void startListening() {
        new Thread(() -> {
            try { Thread.sleep(10000); } catch (InterruptedException ignored) {}
            System.out.println("""
[BACKEND] Consola lista. Comandos:
  watch add <ruta>
  watch remove <ruta>
  watch list
  backend send <comando> / bs <comando>
  backend reload-config / brc
  updates list
  updates check
  updates download <repoKey>
  updates download all
  exit / quit
""");
        }).start();

        try (Scanner scanner = new Scanner(System.in)) {
            while (true) {
                String input;
                try { input = scanner.nextLine(); } catch (Exception e) { break; }
                if (input == null) continue;
                input = input.trim();
                if (input.isEmpty()) continue;

                if (input.equalsIgnoreCase("exit") || input.equalsIgnoreCase("quit")) {
                    System.out.println("[BACKEND] Cerrando...");
                    if (shutdownCallback != null) shutdownCallback.run();
                    break;
                }

                if (input.startsWith("backend send ") || input.startsWith("b s ")) {
                    JarProcessManager manager = managerHolder.get();
                    if (manager == null || !manager.isRunning()) {
                        System.out.println("[BACKEND] Servidor no está corriendo.");
                        continue;
                    }
                    manager.sendCommand(input.substring("backend send ".length()));
                    continue;
                }

                if (input.equalsIgnoreCase("backend reload-config") || input.equalsIgnoreCase("brc")) {
                    if (reloadConfigCallback != null) reloadConfigCallback.run();
                    continue;
                }

                if (input.equalsIgnoreCase("watch list")) {
                    Map<Path, Path> watchers = watcherRegistry.listWatchers();
                    if (watchers.isEmpty()) System.out.println("[BACKEND] No hay watchers.");
                    else watchers.forEach((s, t) -> System.out.println(s + " -> " + t));
                    continue;
                }

                if (input.startsWith("watch add ")) {
                    String payload = input.substring("watch add ".length()).trim();
                    if (payload.isEmpty()) { System.out.println("[BACKEND] Debes indicar la ruta: watch add <ruta>"); continue; }
                    Path p = Path.of(payload).toAbsolutePath().normalize();
                    try {
                        watcherRegistry.addWatcher(p);
                        if (config.watchers == null) config.watchers = new java.util.ArrayList<>();
                        boolean exists = false;
                        for (Config.Watcher w : config.watchers) {
                            try { if (Path.of(w.path).toAbsolutePath().normalize().equals(p)) { exists = true; break; } } catch (Exception ignored) {}
                        }
                        if (!exists) {
                            config.watchers.add(new Config.Watcher(p.toString()));
                            ConfigLoader.save(config);
                            System.out.println("[BACKEND] Watcher guardado en config.yml: " + p);
                        } else System.out.println("[BACKEND] Watcher ya existe en config.yml: " + p);
                    } catch (Exception e) {
                        System.err.println("[BACKEND] No se pudo añadir watcher: " + e.getMessage());
                    }
                    continue;
                }

                if (input.startsWith("watch remove ")) {
                    String payload = input.substring("watch remove ".length()).trim();
                    if (payload.isEmpty()) { System.out.println("[BACKEND] Debes indicar la ruta: watch remove <ruta>"); continue; }
                    Path p = Path.of(payload).toAbsolutePath().normalize();
                    boolean removed = watcherRegistry.removeWatcher(p);
                    if (!removed) { System.out.println("[BACKEND] No se encontró watcher para remover: " + p); continue; }

                    if (config.watchers != null) {
                        Iterator<Config.Watcher> it = config.watchers.iterator();
                        boolean any = false;
                        while (it.hasNext()) {
                            Config.Watcher w = it.next();
                            try { if (Path.of(w.path).toAbsolutePath().normalize().equals(p)) { it.remove(); any = true; } } catch (Exception ignored) {}
                        }
                        if (any) { ConfigLoader.save(config); System.out.println("[BACKEND] Watcher eliminado del config.yml: " + p); }
                        else System.out.println("[BACKEND] No se encontró watcher en config.yml (solo eliminado en memoria): " + p);
                    }
                    continue;
                }

                if (input.equalsIgnoreCase("updates list")) {
                    if (githubService == null) System.out.println("[UPDATES] UpdaterService no disponible.");
                    else {
                        GithubConfig ucfg = githubService.getConfig();
                        if (ucfg == null || ucfg.repos.isEmpty()) System.out.println("[UPDATES] No hay repos en updates.yml");
                        else ucfg.repos.forEach((name, entry) ->
                                System.out.println(name + " -> " + entry.link_repo + " (asset_type: " + entry.asset_type + ", hash: " + entry.downloadedHash + ", file: " + entry.name_file_downloaded + ")")
                        );
                    }
                    continue;
                }

                if (input.equalsIgnoreCase("updates check")) {
                    if (githubService == null) System.out.println("[UPDATES] UpdaterService no disponible.");
                    else {
                        final GithubService us = githubService;
                        new Thread(() -> {
                            System.out.println("[UPDATES] Iniciando comprobación de repos...");
                            us.checkAllAndDownload();
                            System.out.println("[UPDATES] Comprobación finalizada.");
                        }, "UpdatesChecker").start();
                    }
                    continue;
                }

                if (input.equalsIgnoreCase("updates download all")) {
                    if (githubService == null) {
                        System.out.println("[UPDATES] UpdaterService no disponible.");
                        continue;
                    }

                    final GithubService us = githubService;
                    new Thread(() -> {
                        System.out.println("[UPDATES] Iniciando descarga de TODOS los repos...");
                        us.checkAllAndDownload();
                        System.out.println("[UPDATES] Descarga global finalizada.");
                    }, "UpdatesDownloader-All").start();

                    continue;
                }

                if (input.startsWith("updates download ")) {
                    if (githubService == null) { System.out.println("[UPDATES] UpdaterService no disponible."); continue; }
                    String key = input.substring("updates download ".length()).trim();
                    if (key.isEmpty()) { System.out.println("[UPDATES] Debes indicar la clave del repo en updates.yml: updates download <repoKey>"); continue; }
                    final GithubService us = githubService;
                    new Thread(() -> {
                        System.out.println("[UPDATES] Iniciando descarga para repo: " + key);
                        us.checkAndDownloadRepo(key);
                        System.out.println("[UPDATES] Descarga manual para " + key + " finalizada.");
                    }, "UpdatesDownloader-" + key).start();
                    continue;
                }



                System.out.println("[BACKEND] Comando inválido.");
            }
        }

        System.out.println("[BACKEND] Consola finalizada.");
    }
}