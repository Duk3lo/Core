package org.astral.core.config;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;

public final class DefaultConfig {

    private DefaultConfig() {}


    public static @NotNull Config defaultConfig() {
        Config cfg = new Config();

        Config.Server server = new Config.Server();
        server.basePath = "# /home/ruta/completa";
        server.jarName = "HytaleServer.jar";

        server.args = "--assets ../Assets.zip --backup --backup-dir backups --backup-frequency 30";

        cfg.server = server;
        cfg.watchers = new ArrayList<>();

        return cfg;
    }
}