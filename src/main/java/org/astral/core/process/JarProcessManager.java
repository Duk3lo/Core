
package org.astral.core.process;

import org.astral.core.watcher.assets.AssetsArgumentCollector;

import java.io.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class JarProcessManager {

    private final String jarPath;
    private final Path assetsDir;
    private final List<String> baseArgs;

    private Process process;
    private Thread outputThread;

    public JarProcessManager(String jarPath,
                             Path assetsDir,
                             List<String> baseArgs) {

        this.jarPath = jarPath;
        this.assetsDir = assetsDir;
        this.baseArgs = baseArgs;
    }

    public synchronized void start() {

        if (isRunning()) {
            System.out.println("[PROCESS] Ya está corriendo.");
            return;
        }

        try {

            List<String> command = new ArrayList<>();
            command.add("java");
            command.add("-jar");
            command.add(jarPath);
            command.addAll(baseArgs);

            List<String> assetsArgs = AssetsArgumentCollector.collect(assetsDir);
            command.addAll(assetsArgs);

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);

            File jarFile = new File(jarPath);
            File workingDir = jarFile.getParentFile();
            if (workingDir != null && workingDir.exists()) {
                pb.directory(workingDir);
                System.out.println("[PROCESS] Working dir: " + workingDir.getAbsolutePath());
            }

            System.out.println("[PROCESS] Comando: " + String.join(" ", command));

            process = pb.start();

            outputThread = new Thread(() -> {
                try (BufferedReader reader =
                             new BufferedReader(new InputStreamReader(process.getInputStream()))) {

                    String line;
                    while ((line = reader.readLine()) != null) {
                        System.out.println("[SERVER] " + line);
                    }

                } catch (IOException ignored) {
                }
            }, "Server-Output-Reader");

            outputThread.setDaemon(true);
            outputThread.start();

            System.out.println("[PROCESS] Servidor iniciado.");

        } catch (Exception e) {
            System.out.println("[PROCESS] Error iniciando: " + e.getMessage());
        }
    }

    public synchronized void stop() {

        if (process == null) return;

        try {
            if (process.isAlive()) {
                System.out.println("[PROCESS] Deteniendo servidor...");

                process.destroy();

                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    System.out.println("[PROCESS] Forzando cierre...");
                    process.destroyForcibly();
                    process.waitFor(5, TimeUnit.SECONDS);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            // attempt to join reader thread so no resources remain
            if (outputThread != null && outputThread.isAlive()) {
                try {
                    outputThread.join(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            process = null;
            outputThread = null;
        }

        System.out.println("[PROCESS] Servidor detenido.");
    }

    /**
     * Waits for the managed process to stop. If the process does not stop within the default
     * timeout, it will be forcibly destroyed.
     */
    public synchronized void waitForStop() {
        if (process == null) return;

        try {
            // espera razonable para que termine por sí mismo antes de forzar
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                System.out.println("[PROCESS] waitForStop: proceso no terminó en 30s, forzando...");
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            // asegurar limpieza y join del hilo de salida
            if (outputThread != null && outputThread.isAlive()) {
                try {
                    outputThread.join(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            process = null;
            outputThread = null;
        }
    }

    public synchronized void restart() {
        stop();
        start();
    }

    public synchronized boolean isRunning() {
        return process != null && process.isAlive();
    }

    public synchronized void sendCommand(String command) {

        if (!isRunning()) {
            System.out.println("[PROCESS] No está corriendo.");
            return;
        }

        try {
            OutputStreamWriter writer =
                    new OutputStreamWriter(process.getOutputStream());

            writer.write(command + System.lineSeparator());
            writer.flush();

        } catch (IOException e) {
            System.out.println("[PROCESS] Error enviando comando: " + e.getMessage());
        }
    }
}