package org.astral.core.monitor;

import org.astral.core.process.JarProcessManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TpsMonitor {

    private final JarProcessManager manager;
    private volatile MonitorConfig cfg;
    private final Object schedulerLock = new Object();
    private ScheduledExecutorService scheduler;

    private final AtomicInteger unresponsiveCount = new AtomicInteger(0);
    private volatile long lastRestartAt = 0L;

    private final Path lastPeriodicFile;

    private static final Pattern TPS_PATTERN =
            Pattern.compile("TPS \\(([^)]+)\\): Min: ([0-9.]+), Avg: ([0-9.]+), Max: ([0-9.]+)");

    public TpsMonitor(JarProcessManager manager, MonitorConfig cfg, Path monitorFile) {
        this.manager = manager;
        this.cfg = cfg;
        if (monitorFile != null && monitorFile.getParent() != null) {
            this.lastPeriodicFile = monitorFile.getParent().resolve("monitor.last_restart");
        } else {
            this.lastPeriodicFile = null;
        }
    }

    public void start() {
        synchronized (schedulerLock) {
            if (scheduler != null && !scheduler.isShutdown()) return;
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "TpsMonitor");
                t.setDaemon(true);
                return t;
            });
            scheduler.scheduleAtFixedRate(this::checkOnce, 2, Math.max(1, cfg.checkIntervalSeconds), TimeUnit.SECONDS);
            System.out.println("[MONITOR] TPS Monitor iniciado (interval: " + cfg.checkIntervalSeconds + "s)");
        }
    }

    public void shutdown() {
        synchronized (schedulerLock) {
            if (scheduler != null) {
                scheduler.shutdownNow();
                scheduler = null;
            }
        }
        System.out.println("[MONITOR] TPS Monitor detenido.");
    }

    public void updateConfig(MonitorConfig newCfg) {
        if (newCfg == null) return;
        boolean needReschedule = newCfg.checkIntervalSeconds != this.cfg.checkIntervalSeconds;
        this.cfg = newCfg;
        System.out.println("[MONITOR] Config actualizada. checkIntervalSeconds=" + cfg.checkIntervalSeconds);
        if (needReschedule) {
            shutdown();
            start();
        }
    }

    private void checkOnce() {
        try {
            if (!manager.isRunning()) {
                checkPeriodicRestart();
                return;
            }

            long now = System.currentTimeMillis();
            if (now - lastRestartAt < (cfg.minTimeBetweenRestartsSeconds * 1000L)) {
                checkPeriodicRestart();
                return;
            }

            manager.sendCommand(cfg.tpsCommand);

            final long end = System.currentTimeMillis() + (cfg.responseTimeoutSeconds * 1000L);
            boolean gotTps = false;

            while (System.currentTimeMillis() < end) {
                long timeLeft = end - System.currentTimeMillis();
                String line = manager.pollOutputLine(Math.max(100, timeLeft), TimeUnit.MILLISECONDS);
                if (line == null) continue;

                Matcher m = TPS_PATTERN.matcher(line);
                if (m.find()) {
                    double avg = Double.parseDouble(m.group(3));
                    System.out.println("[MONITOR] TPS detectado (" + m.group(1) + ") Avg=" + avg);
                    gotTps = true;
                    unresponsiveCount.set(0);

                    if (cfg.restartOnLowTps && avg < cfg.tpsMin) {
                        long sinceLast = System.currentTimeMillis() - lastRestartAt;
                        if (sinceLast >= (cfg.minTimeBetweenRestartsSeconds * 1000L)) {
                            System.out.println("[MONITOR] TPS bajo (" + avg + " < " + cfg.tpsMin + "), reiniciando servidor...");
                            doRestart();
                        } else {
                            System.out.println("[MONITOR] TPS bajo pero dentro del cooldown de reinicio (" + (sinceLast / 1000) + "s).");
                        }
                    }
                    break;
                }
            }

            if (!gotTps) {
                int count = unresponsiveCount.incrementAndGet();
                System.out.println("[MONITOR] No se obtuvo respuesta TPS (contador=" + count + ")");

                if (count >= cfg.unresponsiveThreshold) {
                    System.out.println("[MONITOR] Terminal no responde. Intentando reinicio forzoso...");
                    doForceRestart();
                    unresponsiveCount.set(0);
                }
            }

            checkPeriodicRestart();

        } catch (Throwable t) {
            System.out.println("[MONITOR] Error en checkOnce: " + t.getMessage());
        }
    }

    private void checkPeriodicRestart() {
        try {
            if (!cfg.enablePeriodicRestart) return;
            if (cfg.periodicRestartDays <= 0) return;
            if (lastPeriodicFile == null) return;

            long now = System.currentTimeMillis();
            long last = readLastPeriodicTimestamp();
            long intervalMillis = TimeUnit.DAYS.toMillis(cfg.periodicRestartDays);

            if (now - last >= intervalMillis) {
                if (now - lastRestartAt < (cfg.minTimeBetweenRestartsSeconds * 1000L)) {
                    System.out.println("[MONITOR] Reinicio periódico pendiente pero dentro del cooldown de reinicio.");
                    return;
                }

                System.out.println("[MONITOR] Reinicio periódico programado. Procediendo a reiniciar...");
                doRestart();
                writeLastPeriodicTimestamp(now);
            }
        } catch (Exception e) {
            System.out.println("[MONITOR] Error comprobando reinicio periódico: " + e.getMessage());
        }
    }

    private long readLastPeriodicTimestamp() {
        try {
            if (lastPeriodicFile == null) return 0L;
            if (!Files.exists(lastPeriodicFile)) return 0L;
            String s = Files.readString(lastPeriodicFile).trim();
            if (s.isEmpty()) return 0L;
            return Long.parseLong(s);
        } catch (Exception e) {
            System.err.println("[MONITOR] No se pudo leer lastPeriodicFile: " + e.getMessage());
            return 0L;
        }
    }

    private void writeLastPeriodicTimestamp(long ts) {
        try {
            if (lastPeriodicFile == null) return;
            if (lastPeriodicFile.getParent() != null) Files.createDirectories(lastPeriodicFile.getParent());
            Files.writeString(lastPeriodicFile, String.valueOf(ts));
        } catch (IOException e) {
            System.err.println("[MONITOR] No se pudo escribir lastPeriodicFile: " + e.getMessage());
        }
    }

    private void doRestart() {
        try {
            lastRestartAt = System.currentTimeMillis();
            manager.sendCommand("stop");
            manager.waitForStop();
            if (manager.isRunning()) {
                manager.stop();
            }
            manager.start();
            System.out.println("[MONITOR] Restart completado.");
        } catch (Exception e) {
            System.out.println("[MONITOR] Error en doRestart: " + e.getMessage());
        }
    }

    private void doForceRestart() {
        try {
            lastRestartAt = System.currentTimeMillis();
            manager.sendCommand("stop");
            manager.waitForStop();
            if (manager.isRunning()) {
                System.out.println("[MONITOR] Stop gracioso falló, forzando stop...");
                manager.stop();
            }
            manager.start();
            System.out.println("[MONITOR] Force restart completado.");
        } catch (Exception e) {
            System.out.println("[MONITOR] Error en doForceRestart: " + e.getMessage());
        }
    }
}