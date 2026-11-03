package com.f1betting.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Manages fine-grained per-session locks to eliminate TOCTOU race conditions between
 * concurrent bet placements and race outcome settlements for the same session.
 */
@Component
public class SessionLockManager {

    private final ConcurrentHashMap<String, ReentrantLock> sessionLocks = new ConcurrentHashMap<>();

    /**
     * Executes the given action holding an exclusive lock for the specified sessionKey.
     *
     * @param sessionKey the session key
     * @param action     the action to execute under lock
     * @param <T>        return type
     * @return result of the action
     */
    public <T> T executeWithLock(String sessionKey, Supplier<T> action) {
        String key = sessionKey != null ? sessionKey : "";
        ReentrantLock lock = sessionLocks.computeIfAbsent(key, k -> new ReentrantLock());
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
            // Evict lock if no other threads are queued to prevent memory growth
            if (!lock.hasQueuedThreads()) {
                sessionLocks.remove(key, lock);
            }
        }
    }
}
