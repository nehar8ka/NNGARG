package com.f1betting.adapter;

import com.f1betting.adapter.model.F1Driver;
import com.f1betting.adapter.model.F1Session;
import com.f1betting.adapter.model.SessionFilter;

import java.util.List;

/**
 * Abstraction layer for any F1 data provider.
 *
 * <p>All application code depends only on this interface.
 * To switch from OpenF1 to another provider, implement this interface
 * and register the new bean — no other code changes are required.
 */
public interface F1EventProvider {

    /**
     * Retrieves a list of F1 sessions (events) matching the given filter criteria.
     *
     * @param filter optional filter criteria; individual fields may be null
     * @return list of sessions, never null
     */
    List<F1Session> getSessions(SessionFilter filter);

    /**
     * Retrieves all drivers registered for a specific session.
     *
     * @param sessionKey the unique session identifier from the provider
     * @return list of drivers, never null
     */
    List<F1Driver> getDriversForSession(String sessionKey);

    /**
     * Retrieves a baseline grid of F1 drivers using a single request or cached roster.
     * Used for bulk event enrichment to prevent N+1 queries.
     *
     * @return list of baseline drivers, never null
     */
    List<F1Driver> getBaselineDriverGrid();
}
