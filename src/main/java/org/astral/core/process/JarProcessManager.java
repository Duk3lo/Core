package org.astral.core.process;

import org.astral.core.watcher.assets.AssetsArgumentCollector;

import java.io.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class JarProcessManager {

    private final String jarPath;
    private final Path assetsDir;
    private final List<String> baseArgs;

    private Process process;

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
                System.out.println("[PROCESS] Working dir establecido en: " + workingDir.getAbsolutePath());
            }

            System.out.println("[PROCESS] Comando: " + String.join(" ", command));

            process = pb.start();

            new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        System.out.println("[SERVER] " + line);
                    }
                } catch (IOException ignored) {}
            }).start();

            System.out.println("[PROCESS] Servidor iniciado.");

        } catch (Exception e) {
            System.out.println("[PROCESS] Error iniciando: " + e.getMessage());
        }
    }

    public synchronized void stop() {
        if (process != null && process.isAlive()) {
            process.destroy();
        }
    }

    public synchronized void restart() {
        stop();
        waitForStop();
        start();
    }

    public synchronized void waitForStop() {
        if (process != null) {
            try {
                process.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
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
            OutputStream outputStream = process.getOutputStream();
            OutputStreamWriter writer = new OutputStreamWriter(outputStream);
            writer.write(command + System.lineSeparator());
            writer.flush();
        } catch (IOException e) {
            System.out.println("[PROCESS] Error enviando comando: " + e.getMessage());
        }
    }

}