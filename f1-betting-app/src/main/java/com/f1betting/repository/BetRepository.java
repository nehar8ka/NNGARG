package com.f1betting.repository;

import com.f1betting.domain.Bet;
import com.f1betting.enums.BetStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BetRepository extends JpaRepository<Bet, Long> {

    /** All bets placed by a specific user. */
    List<Bet> findByUserId(Long userId);

    /**
     * All bets for a specific session in a given status.
     * Used during outcome settlement to find all PENDING bets for an event.
     */
    @Query("""
            SELECT b FROM Bet b
            JOIN FETCH b.user
            WHERE b.sessionKey = :sessionKey
              AND b.status = :status
            """)
    List<Bet> findBySessionKeyAndStatus(
            @Param("sessionKey") String sessionKey,
            @Param("status") BetStatus status);

    /** Check if any bet exists for a given session (used for validation). */
    boolean existsBySessionKey(String sessionKey);
}
