package org.astral.core.watcher.mods;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;


public final class WatchEventSuppressor {

    private static final Map<Path, AtomicLong> suppressedUntil = new ConcurrentHashMap<>();

    private WatchEventSuppressor() {}

    public static void suppress(Path path, long millis) {
        if (path == null) return;
        Path p = path.toAbsolutePath().normalize();
        long until = System.currentTimeMillis() + Math.max(0, millis);
        suppressedUntil.compute(p, (_, cur) -> {
            if (cur == null) return new AtomicLong(until);
            cur.set(Math.max(cur.get(), until));
            return cur;
        });
    }

    public static boolean isSuppressed(Path path) {
        if (path == null) return false;
        Path p = path.toAbsolutePath().normalize();
        AtomicLong at = suppressedUntil.get(p);
        if (at == null) return false;
        long val = at.get();
        if (val < System.currentTimeMillis()) {
            suppressedUntil.remove(p);
            return false;
        }
        return true;
    }
}