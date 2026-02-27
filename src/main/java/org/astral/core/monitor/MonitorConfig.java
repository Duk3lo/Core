package org.astral.core.monitor;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class MonitorConfig {

    public int checkIntervalSeconds = 60;
    public int responseTimeoutSeconds = 5;
    public double tpsMin = 18.0;
    public String tpsCommand = "backend send /world perf";
    public int unresponsiveThreshold = 3;
    public int minTimeBetweenRestartsSeconds = 60;
    public int restartCooldownSeconds = 300;
    public boolean restartOnLowTps = true;

    public boolean enablePeriodicRestart = false;
    public int periodicRestartDays = 7;

    public MonitorConfig() {}

    public static MonitorConfig load(Path path) {
        MonitorConfig cfg = new MonitorConfig();

        try {
            if (path == null) return cfg;

            if (Files.notExists(path)) {
                writeDefaults(path);
                System.out.println("[MONITOR] monitor.yml no existía — creado con valores por defecto: " + path.toAbsolutePath());
                return cfg;
            }

            List<String> lines = Files.readAllLines(path);
            for (String raw : lines) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int idx = line.indexOf(':');
                if (idx <= 0) continue;
                String key = line.substring(0, idx).trim();
                String value = line.substring(idx + 1).trim();
                if ((value.startsWith("\"") && value.endsWith("\"")) ||
                        (value.startsWith("'") && value.endsWith("'"))) {
                    value = value.substring(1, value.length() - 1);
                }
                try {
                    switch (key) {
                        case "checkIntervalSeconds" -> cfg.checkIntervalSeconds = Integer.parseInt(value);
                        case "responseTimeoutSeconds" -> cfg.responseTimeoutSeconds = Integer.parseInt(value);
                        case "tpsMin" -> cfg.tpsMin = Double.parseDouble(value);
                        case "tpsCommand" -> cfg.tpsCommand = value;
                        case "unresponsiveThreshold" -> cfg.unresponsiveThreshold = Integer.parseInt(value);
                        case "minTimeBetweenRestartsSeconds" -> cfg.minTimeBetweenRestartsSeconds = Integer.parseInt(value);
                        case "restartCooldownSeconds" -> cfg.restartCooldownSeconds = Integer.parseInt(value);
                        case "restartOnLowTps" -> cfg.restartOnLowTps = Boolean.parseBoolean(value);
                        case "enablePeriodicRestart" -> cfg.enablePeriodicRestart = Boolean.parseBoolean(value);
                        case "periodicRestartDays" -> cfg.periodicRestartDays = Integer.parseInt(value);
                        default -> { /* ignore unknown */ }
                    }
                } catch (Exception e) {
                    System.out.println("[MONITOR] Valor inválido en monitor.yml para '" + key + "': " + value);
                }
            }

        } catch (IOException e) {
            System.out.println("[MONITOR] Error leyendo monitor.yml: " + e.getMessage());
        }

        return cfg;
    }

    private static void writeDefaults(@NotNull Path path) throws IOException {
        if (path.getParent() != null) Files.createDirectories(path.getParent());
        String content = """
                # monitor.yml - configuración para TpsMonitor
                # checkIntervalSeconds: cada cuantos segundos chequear TPS
                # responseTimeoutSeconds: cuanto esperar (segundos) por la respuesta tras enviar el comando
                # tpsMin: si el avg de TPS es menor a esto, reiniciar
                # tpsCommand: comando que se envía al servidor para obtener TPS
                # unresponsiveThreshold: intentos sin respuesta antes de forzar restart
                # minTimeBetweenRestartsSeconds: evita reinicios repetidos (segundos)
                # restartCooldownSeconds: tiempo de cooldown (segundos) después de un restart
                # restartOnLowTps: si es true reinicia cuando TPS < tpsMin
                # enablePeriodicRestart: si true, realizará un reinicio periódico cada periodicRestartDays días
                # periodicRestartDays: número de días entre reinicios periódicos
                checkIntervalSeconds: 60
                responseTimeoutSeconds: 5
                tpsMin: 18.0
                tpsCommand: "world perf"
                unresponsiveThreshold: 3
                minTimeBetweenRestartsSeconds: 60
                restartCooldownSeconds: 300
                restartOnLowTps: true
                enablePeriodicRestart: false
                periodicRestartDays: 7
                """;
        Files.writeString(path, content);
    }
}