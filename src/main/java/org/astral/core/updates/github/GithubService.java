package org.astral.core.updates.github;

import org.astral.core.updates.util.HashUtils;
import org.jetbrains.annotations.NotNull;
import org.kohsuke.github.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpClient.Redirect;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

public class GithubService {

    private final Path updatesFile;
    private final Path localMods;
    private final Path localAssets;
    private final GithubConfig cfg;
    private final GitHub github;
    private final HttpClient httpClient;
    private final String token; // puede ser null

    // Si true -> mover archivo anterior a backups/<name>.old.<epoch>
    // Si false -> eliminar archivo anterior (no recomendado porque podrías perder versión)
    private static final boolean MOVE_OLD_TO_BACKUPS = true;

    public GithubService(Path updatesFile, Path localMods, Path localAssets) throws IOException {
        this.updatesFile = updatesFile;
        this.localMods = localMods;
        this.localAssets = localAssets;
        this.cfg = GithubLoader.loadOrCreate(updatesFile);

        this.token = System.getenv("GITHUB_TOKEN");
        if (token != null && !token.isBlank()) {
            this.github = new GitHubBuilder().withOAuthToken(token).build();
            System.out.println("[UPDATES] Usando token de GITHUB_TOKEN para autenticación.");
        } else {
            this.github = new GitHubBuilder().build();
        }

        this.httpClient = HttpClient.newBuilder()
                .followRedirects(Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    public GithubConfig getConfig() {
        return cfg;
    }

    public void checkAllAndDownload() {
        for (Map.Entry<String, GithubConfig.RepoEntry> e : cfg.repos.entrySet()) {
            String repoKey = e.getKey();
            GithubConfig.RepoEntry entry = e.getValue();

            // IGNORAR la entrada de ejemplo literal "example" (u otras variantes de ejemplo)
            if (repoKey != null) {
                String keyLower = repoKey.trim().toLowerCase();
                if (keyLower.startsWith("example")) {
                    System.out.println("[UPDATES] Si necesitas entradas para actualizaciones checa: " + repoKey);
                    System.out.println("[UPDATES] usa la misma esstructura de " + repoKey + " para agreagar tus repos y mantenerlos actualizados");
                    continue;
                }
            }

            // Si no hay entry o link_repo es vacío/placeholder -> ignorar
            if (entry == null) {
                System.out.println("[UPDATES] Entrada nula para clave: " + repoKey + " -> ignorado.");
                continue;
            }

            if (entry.link_repo == null || entry.link_repo.isBlank()) {
                System.out.println("[UPDATES] link_repo vacío para " + repoKey + " -> ignorado.");
                continue;
            }

            String lr = entry.link_repo.trim();
            if (lr.equalsIgnoreCase("owner/repo") || lr.equalsIgnoreCase("user/repo") ) {
                System.out.println("[UPDATES] link_repo es placeholder para " + repoKey + " (" + lr + ") -> ignorado.");
                continue;
            }

            try {
                checkAndDownload(repoKey, entry);
            } catch (Exception ex) {
                System.err.println("[UPDATES] Error en repo " + repoKey + ": " + ex.getMessage());
            }
        }
    }

    private void checkAndDownload(String repoKey, GithubConfig.@NotNull RepoEntry entry) throws Exception {
        GHRepository repo = github.getRepository(entry.link_repo);
        GHRelease latest = repo.getLatestRelease();
        if (latest == null) {
            System.out.println("[UPDATES] No hay release para " + entry.link_repo);
            return;
        }

        // elegir asset que coincida con asset_type
        GHAsset targetAsset = null;
        for (GHAsset asset : latest.getAssets()) {
            if (asset.getName() != null && asset.getName().endsWith(entry.asset_type)) {
                targetAsset = asset;
                break;
            }
        }

        if (targetAsset == null) {
            System.out.println("[UPDATES] No se encontró asset con tipo " + entry.asset_type + " en " + entry.link_repo);
            return;
        }

        // asegurar directorios
        Files.createDirectories(localMods);
        Files.createDirectories(localAssets);

        // decidir nombre final: si name_file_downloaded está definido, úsalo; si no, usa el nombre del asset
        String targetFileName = (entry.name_file_downloaded != null && !entry.name_file_downloaded.isBlank())
                ? entry.name_file_downloaded
                : targetAsset.getName();

        Path finalDir = chooseDir(targetFileName, targetAsset);
        Path finalTarget = finalDir.resolve(targetFileName);
        Path tmpDownload = finalDir.resolve(targetAsset.getName() + ".download");

        // comparar hashes: si downloadedHash no vacío y archivo existe y hashes coinciden -> ignorar
        if (entry.downloadedHash != null && !entry.downloadedHash.isBlank() && Files.exists(finalTarget)) {
            try {
                String localHash = HashUtils.sha256OfFile(finalTarget);
                if (localHash.equalsIgnoreCase(entry.downloadedHash)) {
                    System.out.println("[UPDATES] " + repoKey + " ya instalado y hash coincide -> ignorado.");
                    return;
                } else {
                    System.out.println("[UPDATES] " + repoKey + " hash local difiere -> forzando descarga.");
                }
            } catch (Exception ex) {
                System.out.println("[UPDATES] No se pudo calcular hash local, forzando descarga: " + ex.getMessage());
            }
        } else {
            System.out.println("[UPDATES] " + repoKey + " no tiene hash guardado o archivo no existe -> descargando.");
        }

        // obtener URL de descarga (preferir browser_download_url)
        String browserUrl = null;
        try { browserUrl = targetAsset.getBrowserDownloadUrl(); } catch (Exception ignored) {}
        String downloadUrl;
        if (browserUrl != null && !browserUrl.isBlank()) {
            downloadUrl = browserUrl;
        } else {
            downloadUrl = "https://api.github.com/repos/" +
                    repo.getOwnerName() + "/" + repo.getName() +
                    "/releases/assets/" + targetAsset.getId();
        }

        System.out.println("[UPDATES] Descargando " + targetAsset.getName() + " desde " + downloadUrl + " -> tmp " + tmpDownload);

        // petición
        HttpRequest.Builder rb = HttpRequest.newBuilder()
                .uri(URI.create(downloadUrl))
                .timeout(Duration.ofMinutes(10))
                .GET()
                .header("User-Agent", "astral-updater");

        if (downloadUrl.contains("api.github.com")) rb.header("Accept", "application/octet-stream");
        if (token != null && !token.isBlank()) rb.header("Authorization", "token " + token);

        HttpRequest req = rb.build();

        HttpResponse<InputStream> resp = httpClient.send(req, BodyHandlers.ofInputStream());
        int status = resp.statusCode();
        if (status < 200 || status >= 300) {
            try (InputStream is = resp.body()) { is.transferTo(OutputStream.nullOutputStream()); }
            throw new IOException("HTTP error descargando asset: " + status);
        }

        // guardar a tmp
        try (InputStream in = resp.body()) {
            Files.copy(in, tmpDownload, StandardCopyOption.REPLACE_EXISTING);
        }

        // calcular hash del archivo descargado
        String downloadedHash = HashUtils.sha256OfFile(tmpDownload);

        // Si existe una versión anterior registrada en name_file_downloaded -> moverla/ eliminarla
        if (entry.name_file_downloaded != null && !entry.name_file_downloaded.isBlank()) {
            try {
                Path prevPath = chooseDir(entry.name_file_downloaded, targetAsset).resolve(entry.name_file_downloaded);
                if (Files.exists(prevPath)) {
                    if (MOVE_OLD_TO_BACKUPS) {
                        Path backupsDir = prevPath.getParent().resolve("backups");
                        Files.createDirectories(backupsDir);
                        String backupName = prevPath.getFileName().toString() + ".old." + Instant.now().toEpochMilli();
                        Path backupTarget = backupsDir.resolve(backupName);
                        Files.move(prevPath, backupTarget, StandardCopyOption.REPLACE_EXISTING);
                        System.out.println("[UPDATES] Movido archivo anterior a backups: " + backupTarget);
                    } else {
                        Files.delete(prevPath);
                        System.out.println("[UPDATES] Archivo anterior eliminado: " + prevPath);
                    }
                }
            } catch (Exception ex) {
                System.err.println("[UPDATES] No se pudo mover/eliminar archivo anterior: " + ex.getMessage());
                // continuar: no abortamos la instalación sólo por un fallo moviendo el archivo viejo
            }
        }

        // mover tmp al destino
        try {
            try {
                Files.move(tmpDownload, finalTarget, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException amnse) {
                Files.move(tmpDownload, finalTarget, StandardCopyOption.REPLACE_EXISTING);
            }

            // actualizar datos en la config y guardar
            entry.downloadedHash = downloadedHash;
            entry.name_file_downloaded = targetFileName;
            GithubLoader.save(updatesFile, cfg);

            System.out.println("[UPDATES] Descarga completada e instalada: " + finalTarget + " (sha256: " + downloadedHash + ")");
        } catch (Exception ex) {
            try { Files.deleteIfExists(tmpDownload); } catch (Exception ignored) {}
            throw ex;
        }
    }

    private Path chooseDir(String filename, GHAsset targetAsset) {
        String n = filename != null ? filename.toLowerCase() : (targetAsset.getName() != null ? targetAsset.getName().toLowerCase() : "");
        if (n.endsWith(".zip")) return localAssets;
        if (n.endsWith(".jar")) return localMods;
        String assetName = targetAsset.getName() != null ? targetAsset.getName().toLowerCase() : "";
        if (assetName.endsWith(".zip")) return localAssets;
        return localMods;
    }

    /** Método público para descargar un repo concreto por su clave en updates.yml */
    public void checkAndDownloadRepo(String repoKey) {
        GithubConfig.RepoEntry entry = cfg.repos.get(repoKey);
        if (entry == null) {
            System.out.println("[UPDATES] No existe la entrada en updates.yml: " + repoKey);
            return;
        }
        try {
            checkAndDownload(repoKey, entry);
        } catch (Exception e) {
            System.err.println("[UPDATES] Error descargando repo " + repoKey + ": " + e.getMessage());
        }
    }
}