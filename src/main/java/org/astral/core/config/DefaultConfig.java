package org.astral.core.config;

import java.util.ArrayList;

public final class DefaultConfig {

    private DefaultConfig() {}

    @org.jetbrains.annotations.NotNull
    public static Config defaultConfig() {
        Config cfg = new Config();

        Config.Server server = new Config.Server();
        server.basePath = "C:/Users/MSI/Desktop/hytale/Lobby - copia";
        server.jarName = "HytaleServer.jar";

        // args como UNA SOLA LÍNEA
        server.args = "--assets ../Assets.zip --backup --backup-dir backups --backup-frequency 30";

        cfg.server = server;
        cfg.watchers = new ArrayList<>();

        return cfg;
    }
}