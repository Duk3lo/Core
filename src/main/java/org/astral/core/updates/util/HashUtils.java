package org.astral.core.updates.util;

import org.jetbrains.annotations.NotNull;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;

public final class HashUtils {

    private HashUtils() {}

    public static @NotNull String sha256OfFile(Path p) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream is = Files.newInputStream(p);
             DigestInputStream dis = new DigestInputStream(is, md)) {
            dis.transferTo(OutputStream.nullOutputStream());
        }
        return bytesToHex(md.digest());
    }

    private static @NotNull String bytesToHex(byte @NotNull [] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}