package com.cloudshield.backend.service;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class LoginAttemptLimiter {
    private static final int MAX_ATTEMPTS = 10;
    private static final long WINDOW_MILLIS = 10 * 60 * 1000L;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final Clock clock = Clock.systemUTC();
    public synchronized boolean allow(String key) {
        long now = clock.millis();
        windows.entrySet().removeIf(entry -> now - entry.getValue().startedAt >= WINDOW_MILLIS);
        if (windows.size() > 10000) windows.clear();
        Window window = windows.compute(key, (ignored, current) -> current == null || now - current.startedAt >= WINDOW_MILLIS ? new Window(now, 0) : current);
        return window.count < MAX_ATTEMPTS;
    }
    public synchronized void failed(String key) {
        long now = clock.millis();
        Window current = windows.get(key);
        if (current == null || now - current.startedAt >= WINDOW_MILLIS) windows.put(key, new Window(now, 1));
        else windows.put(key, new Window(current.startedAt, current.count + 1));
    }
    public synchronized void succeeded(String key) { windows.remove(key); }
    private record Window(long startedAt, int count) {}
}
