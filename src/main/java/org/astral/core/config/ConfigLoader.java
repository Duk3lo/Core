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
    private static final DateTimeFormatter BACKUP_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    public static Config load() {
        Path configPath = Path.of(System.getProperty("user.dir")).resolve(CONFIG_FILE_NAME);

        try {
            if (Files.notExists(configPath)) {
                Config defaultCfg = DefaultConfig.defaultConfig();
                writeConfigFile(configPath, defaultCfg);
                System.out.println("[CONFIG] config.yml no encontrado — se creó uno en: " + configPath.toAbsolutePath());
                return defaultCfg;
            }
            try (InputStream input = new FileInputStream(configPath.toFile())) {
                Yaml yaml = new Yaml();
                Config cfg = yaml.loadAs(input, Config.class);
                if (cfg == null) {
                    Config defaultCfg = DefaultConfig.defaultConfig();
                    writeConfigFile(configPath, defaultCfg);
                    System.out.println("[CONFIG] config.yml vacío — se reemplazó por la configuración por defecto.");
                    return defaultCfg;
                }
                if (cfg.watchers == null) cfg.watchers = new ArrayList<>();
                return cfg;
            } catch (Exception primaryEx) {
                System.err.println("[CONFIG] Error al leer config.yml: " + primaryEx.getMessage());
                try (InputStream input2 = new FileInputStream(configPath.toFile())) {
                    Yaml yaml = new Yaml();
                    Object loaded = yaml.load(input2);
                    if (loaded instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> root = (Map<String, Object>) loaded;
                        Config repaired = mapToConfig(root);
                        // reescribir fichero limpio (sin tags)
                        writeConfigFile(configPath, repaired);
                        System.out.println("[CONFIG] config.yml reparado a partir del viejo contenido (se sobrescribió con versión limpia).");
                        return repaired;
                    } else {
                        System.err.println("[CONFIG] El YAML no es un map (tipo: " + (loaded == null ? "null" : loaded.getClass()) + ")");
                        Path bak = backupConfigFile(configPath);
                        System.err.println("[CONFIG] Se movió el config viejo a: " + bak.toAbsolutePath());
                        Config defaultCfg = DefaultConfig.defaultConfig();
                        writeConfigFile(configPath, defaultCfg);
                        return defaultCfg;
                    }
                } catch (Exception secondEx) {
                    // todo falló -> backup y default
                    System.err.println("[CONFIG] No se pudo reparar config.yml: " + secondEx.getMessage());
                    Path bak = backupConfigFile(configPath);
                    System.err.println("[CONFIG] Se movió el config viejo a: " + bak.toAbsolutePath());
                    Config defaultCfg = DefaultConfig.defaultConfig();
                    writeConfigFile(configPath, defaultCfg);
                    return defaultCfg;
                }
            }

        } catch (Exception e) {
            throw new RuntimeException("Error cargando/creando config.yml en: " + Path.of(System.getProperty("user.dir")).resolve(CONFIG_FILE_NAME).toAbsolutePath(), e);
        }
    }

    private static @NotNull Config mapToConfig(@NotNull Map<String, Object> root) {
        Config cfg = DefaultConfig.defaultConfig(); // start from defaults and override if present

        if (root.containsKey("server")) {
            Object serverObj = root.get("server");
            if (serverObj instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> serverMap = (Map<String, Object>) serverObj;
                if (serverMap.containsKey("basePath")) {
                    cfg.server.basePath = String.valueOf(serverMap.get("basePath"));
                }
                if (serverMap.containsKey("jarName")) {
                    cfg.server.jarName = String.valueOf(serverMap.get("jarName"));
                }

                // args puede venir como List o como String (línea única).
                if (serverMap.containsKey("args")) {
                    Object argsObj = serverMap.get("args");
                    List<String> parsedArgs = new ArrayList<>();
                    if (argsObj instanceof List) {
                        @SuppressWarnings("unchecked")
                        List<Object> al = (List<Object>) argsObj;
                        for (Object o : al) {
                            parsedArgs.add(String.valueOf(o));
                        }
                    } else if (argsObj instanceof String raw) {
                        parsedArgs.addAll(splitArgs(raw));
                    }

                    // Filtrar entradas --assets que apunten a "Complement" (case-insensitive)
                    List<String> filtered = new ArrayList<>();
                    for (int i = 0; i < parsedArgs.size(); i++) {
                        String tok = parsedArgs.get(i);
                        if ("--assets".equals(tok) && i + 1 < parsedArgs.size()) {
                            String val = parsedArgs.get(i + 1);
                            if (val.toLowerCase(Locale.ROOT).contains("complement")) {
                                // saltar ambos
                                System.out.println("[CONFIG] Filtrando --assets " + val + " (se omiten los Complement).");
                            } else {
                                filtered.add(tok);
                                filtered.add(val);
                            }
                            i++; // skip value
                        } else {
                            filtered.add(tok);
                        }
                    }
                    cfg.server.args = joinTokensToLine(filtered);
                }
            }
        }

        if (root.containsKey("watchers")) {
            Object w = root.get("watchers");
            if (w instanceof List) {
                @SuppressWarnings("unchecked")
                List<Object> wl = (List<Object>) w;
                List<Config.Watcher> watchers = new ArrayList<>();
                for (Object o : wl) {
                    if (o instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> wm = (Map<String, Object>) o;
                        String path = wm.containsKey("path") ? String.valueOf(wm.get("path")) : null;
                        String type = wm.containsKey("type") ? String.valueOf(wm.get("type")) : "mods";
                        if (path != null) {
                            watchers.add(new Config.Watcher(path, type));
                        }
                    } else if (o instanceof String) {
                        // antigua forma: lista de strings => interpretar como path con tipo "mods"
                        watchers.add(new Config.Watcher(String.valueOf(o), "mods"));
                    }
                }
                cfg.watchers = watchers;
            }
        }

        if (cfg.watchers == null) cfg.watchers = new ArrayList<>();
        return cfg;
    }

    private static Path backupConfigFile(@NotNull Path path) {
        try {
            String ts = LocalDateTime.now().format(BACKUP_TS);
            Path bak = path.resolveSibling(path.getFileName().toString() + ".bak-" + ts);
            Files.move(path, bak);
            return bak;
        } catch (IOException e) {
            System.err.println("[CONFIG] No se pudo mover config.yml para backup: " + e.getMessage());
            return path;
        }
    }

    private static void writeConfigFile(@NotNull Path path, Config cfg) throws IOException {
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);

        Yaml yaml = new Yaml(options);

        Map<String, Object> server = new LinkedHashMap<>();
        server.put("basePath", cfg.server != null ? cfg.server.basePath : "");
        server.put("jarName", cfg.server != null ? cfg.server.jarName : "");

        if (cfg.server != null && cfg.server.args != null && !cfg.server.args.trim().isEmpty()) {
            server.put("args", cfg.server.args);
        }

        List<Object> watchersOut = new ArrayList<>();
        if (cfg.watchers != null) {
            for (Config.Watcher w : cfg.watchers) {
                Map<String, Object> wm = new LinkedHashMap<>();
                wm.put("path", w.path);
                wm.put("type", w.type != null ? w.type : "mods");
                watchersOut.add(wm);
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("server", server);
        data.put("watchers", watchersOut);

        try (BufferedWriter writer = Files.newBufferedWriter(path)) {
            yaml.dump(data, writer);
        }
    }

    private static @NotNull List<String> splitArgs(String line) {
        List<String> result = new ArrayList<>();
        if (line == null || line.trim().isEmpty()) return result;
        StringBuilder cur = new StringBuilder();
        boolean inDouble = false;
        boolean inSingle = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"' && !inSingle) {
                inDouble = !inDouble;
                continue;
            }
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
                continue;
            }
            if (Character.isWhitespace(c) && !inDouble && !inSingle) {
                if (!cur.isEmpty()) {
                    result.add(cur.toString());
                    cur.setLength(0);
                }
            } else {
                cur.append(c);
            }
        }
        if (!cur.isEmpty()) result.add(cur.toString());
        return result;
    }

    private static @NotNull String joinTokensToLine(List<String> tokens) {
        if (tokens == null || tokens.isEmpty()) return "";
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < tokens.size(); i++) {
            String t = tokens.get(i);
            if (needsQuoting(t)) {
                String esc = t.replace("\"", "\\\"");
                joined.append("\"").append(esc).append("\"");
            } else {
                joined.append(t);
            }
            if (i < tokens.size() - 1) joined.append(" ");
        }
        return joined.toString();
    }

    private static boolean needsQuoting(@NotNull String s) {
        return s.contains(" ") || s.contains(":") || s.contains("#") || (s.startsWith("-") && s.length() > 1 && Character.isDigit(s.charAt(1)));
    }

    public static void save(Config cfg) {
        Path configPath = Path.of(System.getProperty("user.dir")).resolve(CONFIG_FILE_NAME);
        try {
            writeConfigFile(configPath, cfg);
        } catch (IOException e) {
            throw new RuntimeException("No se pudo guardar config.yml en: " + configPath.toAbsolutePath(), e);
        }
    }
}