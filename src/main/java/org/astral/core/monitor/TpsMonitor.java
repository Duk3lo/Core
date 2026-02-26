package org.astral.core.monitor;

import org.astral.core.process.JarProcessManager;

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

    // ejemplo de línea a parsear:
    // TPS (10 sec): Min: 30.0, Avg: 30.0, Max: 30.0
    private static final Pattern TPS_PATTERN =
            Pattern.compile("TPS \\(([^)]+)\\): Min: ([0-9.]+), Avg: ([0-9.]+), Max: ([0-9.]+)");

    public TpsMonitor(JarProcessManager manager, MonitorConfig cfg) {
        this.manager = manager;
        this.cfg = cfg;
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

    /**
     * Update configuration in hot (aplica cambios). Si cambia el intervalo, resetea el scheduler.
     */
    public void updateConfig(MonitorConfig newCfg) {
        if (newCfg == null) return;
        boolean needReschedule = newCfg.checkIntervalSeconds != this.cfg.checkIntervalSeconds;
        this.cfg = newCfg;
        System.out.println("[MONITOR] Config actualizada. checkIntervalSeconds=" + cfg.checkIntervalSeconds);
        if (needReschedule) {
            // reiniciar scheduler con nuevo intervalo
            shutdown();
            start();
        }
    }

    private void checkOnce() {
        try {
            if (!manager.isRunning()) {
                // si no está corriendo, evitamos enviar comandos inútiles
                return;
            }

            long now = System.currentTimeMillis();
            if (now - lastRestartAt < (cfg.minTimeBetweenRestartsSeconds * 1000L)) {
                // evitar reinicios repetidos
                return;
            }

            // enviar comando para que el servidor muestre perf
            manager.sendCommand(cfg.tpsCommand);

            // esperar líneas hasta timeout buscando la línea TPS
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
                            System.out.println("[MONITOR] TPS bajo pero dentro del cooldown de reinicio (" + (sinceLast/1000) + "s).");
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

        } catch (Throwable t) {
            System.out.println("[MONITOR] Error en checkOnce: " + t.getMessage());
        }
    }

    private void doRestart() {
        try {
            lastRestartAt = System.currentTimeMillis();
            // intentar stop gracioso por consola
            manager.sendCommand("stop");
            // esperar shutdown ordenado
            manager.waitForStop();
            if (manager.isRunning()) {
                // si aún corre, forzar
                manager.stop();
            }
            // start de nuevo
            manager.start();
            System.out.println("[MONITOR] Restart completado.");
        } catch (Exception e) {
            System.out.println("[MONITOR] Error en doRestart: " + e.getMessage());
        }
    }

    private void doForceRestart() {
        try {
            lastRestartAt = System.currentTimeMillis();
            // primero intentar stop por consola
            manager.sendCommand("stop");
            manager.waitForStop();
            if (manager.isRunning()) {
                System.out.println("[MONITOR] Stop gracioso falló, forzando stop...");
                manager.stop();
            }
            // arrancar otra vez
            manager.start();
            System.out.println("[MONITOR] Force restart completado.");
        } catch (Exception e) {
            System.out.println("[MONITOR] Error en doForceRestart: " + e.getMessage());
        }
    }
}