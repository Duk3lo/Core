package org.astral.core.updates.github;

import org.jetbrains.annotations.NotNull;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

public class UpdatesLoader {

    private static final String EXAMPLE_YAML = """
repos:
  example:
    link_repo: "owner/repo"
    asset_type: ".jar"
    downloadedHash: ""
    name_file_downloaded: ""
""";

    public static @NotNull UpdatesConfig loadOrCreate(Path file) {
        try {
            if (Files.notExists(file)) {
                Files.createDirectories(file.getParent());
                Files.writeString(file, EXAMPLE_YAML);
            }

            try (InputStream in = Files.newInputStream(file)) {
                Yaml yaml = new Yaml();
                UpdatesConfig cfg = yaml.loadAs(in, UpdatesConfig.class);
                if (cfg == null) cfg = new UpdatesConfig();
                if (cfg.repos == null) cfg.repos = new java.util.LinkedHashMap<>();
                return cfg;
            }
        } catch (IOException e) {
            System.out.println("[UPDATES] Error cargando/creando updates.yml: " + e.getMessage());
            return new UpdatesConfig();
        }
    }

    public static void save(Path file, UpdatesConfig cfg) {
        try {
            DumperOptions opts = new DumperOptions();
            opts.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
            opts.setPrettyFlow(true);
            Yaml yaml = new Yaml(opts);
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file)) {
                yaml.dump(cfg, writer);
            }
        } catch (IOException e) {
            System.err.println("[UPDATES] Error guardando updates.yml: " + e.getMessage());
        }
    }
}