package com.f1betting.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Records the official outcome of a completed F1 event.
 * Triggers bet settlement when persisted.
 */
@Entity
@Table(name = "f1_event_outcomes")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EventOutcome {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "outcome_seq")
    @SequenceGenerator(name = "outcome_seq", sequenceName = "outcome_seq", allocationSize = 1)
    private Long id;

    /**
     * The OpenF1 session_key of the event that was completed.
     * Unique — an event can only have one recorded outcome.
     */
    @Column(nullable = false, unique = true, length = 50)
    private String sessionKey;

    /** The official car number of the driver who won the event. */
    @Column(nullable = false)
    private Integer winnerDriverNumber;

    @Column(nullable = false)
    private LocalDateTime recordedAt;
}
