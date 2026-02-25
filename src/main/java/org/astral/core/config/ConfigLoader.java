package org.astral.core.config;

import org.jetbrains.annotations.NotNull;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class ConfigLoader {

    private static final String CONFIG_FILE_NAME = "config.yml";
    private static final DateTimeFormatter BACKUP_TS =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    public static Config load() {
        Path configPath = Path.of(System.getProperty("user.dir"))
                .resolve(CONFIG_FILE_NAME);

        try {
            if (Files.notExists(configPath)) {
                Config def = DefaultConfig.defaultConfig();
                writeConfigFile(configPath, def);
                System.out.println("[CONFIG] config.yml creado en: "
                        + configPath.toAbsolutePath());
                return def;
            }

            try (InputStream in = new FileInputStream(configPath.toFile())) {
                Yaml yaml = new Yaml();
                Config cfg = yaml.loadAs(in, Config.class);

                if (cfg == null) {
                    Config def = DefaultConfig.defaultConfig();
                    writeConfigFile(configPath, def);
                    System.out.println("[CONFIG] config.yml vacío — restaurado.");
                    return def;
                }

                if (cfg.watchers == null)
                    cfg.watchers = new ArrayList<>();

                return cfg;

            } catch (Exception ex) {
                System.err.println("[CONFIG] Error leyendo config.yml: "
                        + ex.getMessage());

                return attemptRepair(configPath);
            }

        } catch (Exception e) {
            throw new RuntimeException(
                    "Error cargando config.yml en: "
                            + configPath.toAbsolutePath(), e);
        }
    }

    private static Config attemptRepair(Path configPath) {
        try (InputStream in = new FileInputStream(configPath.toFile())) {
            Yaml yaml = new Yaml();
            Object loaded = yaml.load(in);

            if (loaded instanceof Map<?, ?> raw) {
                @SuppressWarnings("unchecked")
                Map<String, Object> root = (Map<String, Object>) raw;

                Config repaired = mapToConfig(root);
                writeConfigFile(configPath, repaired);

                System.out.println("[CONFIG] config.yml reparado.");
                return repaired;
            }

        } catch (Exception ignored) {}

        Path bak = backupConfigFile(configPath);
        System.err.println("[CONFIG] Se movió config inválido a: "
                + bak.toAbsolutePath());

        Config def = DefaultConfig.defaultConfig();
        try {
            writeConfigFile(configPath, def);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return def;
    }

    private static @NotNull Config mapToConfig(
            @NotNull Map<String, Object> root) {

        Config cfg = DefaultConfig.defaultConfig();

        /* ================= SERVER ================= */

        if (root.containsKey("server")) {
            Object serverObj = root.get("server");

            if (serverObj instanceof Map<?, ?> raw) {
                @SuppressWarnings("unchecked")
                Map<String, Object> server =
                        (Map<String, Object>) raw;

                if (server.containsKey("basePath"))
                    cfg.server.basePath =
                            String.valueOf(server.get("basePath"));

                if (server.containsKey("jarName"))
                    cfg.server.jarName =
                            String.valueOf(server.get("jarName"));

                if (server.containsKey("args")) {
                    Object argsObj = server.get("args");
                    List<String> parsed = new ArrayList<>();

                    if (argsObj instanceof List<?> list) {
                        for (Object o : list)
                            parsed.add(String.valueOf(o));
                    } else if (argsObj instanceof String s) {
                        parsed.addAll(splitArgs(s));
                    }

                    cfg.server.args = joinTokensToLine(parsed);
                }
            }
        }

        /* ================= WATCHERS ================= */

        if (root.containsKey("watchers")) {
            Object w = root.get("watchers");

            if (w instanceof List<?> list) {
                List<Config.Watcher> watchers = new ArrayList<>();

                for (Object o : list) {

                    if (o instanceof Map<?, ?> raw) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> wm =
                                (Map<String, Object>) raw;

                        Object pathObj = wm.get("path");
                        if (pathObj != null) {
                            watchers.add(
                                    new Config.Watcher(
                                            String.valueOf(pathObj)
                                    )
                            );
                        }

                    } else if (o instanceof String s) {
                        watchers.add(new Config.Watcher(s));
                    }
                }

                cfg.watchers = watchers;
            }
        }

        if (cfg.watchers == null)
            cfg.watchers = new ArrayList<>();

        return cfg;
    }

    private static Path backupConfigFile(@NotNull Path path) {
        try {
            String ts = LocalDateTime.now().format(BACKUP_TS);
            Path bak = path.resolveSibling(
                    path.getFileName() + ".bak-" + ts
            );
            Files.move(path, bak);
            return bak;
        } catch (IOException e) {
            System.err.println("[CONFIG] No se pudo hacer backup: "
                    + e.getMessage());
            return path;
        }
    }

    private static void writeConfigFile(
            @NotNull Path path,
            Config cfg) throws IOException {

        if (path.getParent() != null)
            Files.createDirectories(path.getParent());

        DumperOptions opts = new DumperOptions();
        opts.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        opts.setPrettyFlow(true);

        Yaml yaml = new Yaml(opts);

        Map<String, Object> server = new LinkedHashMap<>();
        server.put("basePath",
                cfg.server != null ? cfg.server.basePath : "");
        server.put("jarName",
                cfg.server != null ? cfg.server.jarName : "");

        if (cfg.server != null &&
                cfg.server.args != null &&
                !cfg.server.args.isBlank()) {
            server.put("args", cfg.server.args);
        }

        List<Object> watchersOut = new ArrayList<>();
        if (cfg.watchers != null) {
            for (Config.Watcher w : cfg.watchers) {
                Map<String, Object> wm = new LinkedHashMap<>();
                wm.put("path", w.path);
                watchersOut.add(wm);
            }
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("server", server);
        root.put("watchers", watchersOut);

        try (BufferedWriter writer =
                     Files.newBufferedWriter(path)) {
            yaml.dump(root, writer);
        }
    }

    /* ================= ARG UTILS ================= */

    public static @NotNull List<String> splitArgs(String line) {
        List<String> result = new ArrayList<>();
        if (line == null || line.isBlank()) return result;

        StringBuilder cur = new StringBuilder();
        boolean inDouble = false;
        boolean inSingle = false;

        for (char c : line.toCharArray()) {

            if (c == '"' && !inSingle) {
                inDouble = !inDouble;
                continue;
            }

            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
                continue;
            }

            if (Character.isWhitespace(c)
                    && !inDouble && !inSingle) {

                if (!cur.isEmpty()) {
                    result.add(cur.toString());
                    cur.setLength(0);
                }
            } else {
                cur.append(c);
            }
        }

        if (!cur.isEmpty())
            result.add(cur.toString());

        return result;
    }

    private static @NotNull String joinTokensToLine(
            List<String> tokens) {

        if (tokens == null || tokens.isEmpty())
            return "";

        StringBuilder joined = new StringBuilder();

        for (int i = 0; i < tokens.size(); i++) {

            String t = tokens.get(i);

            if (needsQuoting(t)) {
                String esc = t.replace("\"", "\\\"");
                joined.append("\"").append(esc).append("\"");
            } else {
                joined.append(t);
            }

            if (i < tokens.size() - 1)
                joined.append(" ");
        }

        return joined.toString();
    }

    private static boolean needsQuoting(@NotNull String s) {
        return s.contains(" ")
                || s.contains(":")
                || s.contains("#");
    }

    public static void save(Config cfg) {
        Path configPath = Path.of(System.getProperty("user.dir"))
                .resolve(CONFIG_FILE_NAME);

        try {
            writeConfigFile(configPath, cfg);
        } catch (IOException e) {
            throw new RuntimeException(
                    "No se pudo guardar config.yml en: "
                            + configPath.toAbsolutePath(), e);
        }
    }
}