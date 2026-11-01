package com.f1betting.repository;

import com.f1betting.domain.EventOutcome;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface EventOutcomeRepository extends JpaRepository<EventOutcome, Long> {

    Optional<EventOutcome> findBySessionKey(String sessionKey);

    boolean existsBySessionKey(String sessionKey);
}
