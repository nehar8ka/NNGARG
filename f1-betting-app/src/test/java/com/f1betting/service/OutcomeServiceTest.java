package com.f1betting.service;

import com.f1betting.adapter.F1EventProvider;
import com.f1betting.adapter.model.F1Driver;
import com.f1betting.domain.Bet;
import com.f1betting.domain.EventOutcome;
import com.f1betting.domain.User;
import com.f1betting.dto.OutcomeResultDto;
import com.f1betting.enums.BetStatus;
import com.f1betting.exception.BettingException;
import com.f1betting.exception.ResourceNotFoundException;
import com.f1betting.repository.BetRepository;
import com.f1betting.repository.EventOutcomeRepository;
import com.f1betting.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OutcomeServiceTest {

    @Mock
    private EventOutcomeRepository eventOutcomeRepository;

    @Mock
    private BetRepository betRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private F1EventProvider f1EventProvider;

    @Spy
    private SessionLockManager sessionLockManager = new SessionLockManager();

    @Mock
    private TransactionTemplate transactionTemplate;

    @Spy
    private ExecutorService f1ValidationExecutor = Executors.newSingleThreadExecutor();

    @InjectMocks
    private OutcomeService outcomeService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(outcomeService, "driverValidationTimeoutSeconds", 3L);
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
    }

    @AfterEach
    void tearDown() {
        f1ValidationExecutor.shutdown();
    }

    @Test
    void testRecordOutcomeWinningAndLosingBetsSettled() {
        String sessionKey = "9157";
        Integer winnerNumber = 1;

        F1Driver driver1 = F1Driver.builder().driverNumber(1).fullName("Max Verstappen").build();
        F1Driver driver44 = F1Driver.builder().driverNumber(44).fullName("Lewis Hamilton").build();
        when(f1EventProvider.getDriversForSession(sessionKey)).thenReturn(List.of(driver1, driver44));
        when(eventOutcomeRepository.existsBySessionKey(sessionKey)).thenReturn(false);

        User alice = User.builder().id(1L).username("alice").balance(new BigDecimal("100.00")).build();
        User bob = User.builder().id(2L).username("bob").balance(new BigDecimal("100.00")).build();

        Bet winningBet = Bet.builder()
                .id(101L)
                .user(alice)
                .sessionKey(sessionKey)
                .driverNumber(1)
                .stakeAmount(new BigDecimal("20.00"))
                .odds(3)
                .potentialPayout(new BigDecimal("60.00"))
                .status(BetStatus.PENDING)
                .build();

        Bet losingBet = Bet.builder()
                .id(102L)
                .user(bob)
                .sessionKey(sessionKey)
                .driverNumber(44)
                .stakeAmount(new BigDecimal("10.00"))
                .odds(2)
                .potentialPayout(new BigDecimal("20.00"))
                .status(BetStatus.PENDING)
                .build();

        when(betRepository.findBySessionKeyAndStatus(sessionKey, BetStatus.PENDING))
                .thenReturn(List.of(winningBet, losingBet));
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(alice));

        OutcomeResultDto result = outcomeService.recordOutcome(sessionKey, winnerNumber);

        assertNotNull(result);
        assertEquals(sessionKey, result.getSessionKey());
        assertEquals(winnerNumber, result.getWinnerDriverNumber());
        assertEquals(2, result.getTotalBetsSettled());
        assertEquals(1, result.getBetsWon());
        assertEquals(1, result.getBetsLost());
        assertEquals(new BigDecimal("60.00"), result.getTotalPaidOut());

        // Verify winner status & balance credit
        assertEquals(BetStatus.WON, winningBet.getStatus());
        assertEquals(new BigDecimal("160.00"), alice.getBalance());
        verify(userRepository).save(alice);

        // Verify loser status & balance unchanged
        assertEquals(BetStatus.LOST, losingBet.getStatus());
        verify(userRepository, never()).save(bob);

        verify(eventOutcomeRepository).save(any(EventOutcome.class));
        verify(betRepository, times(2)).save(any(Bet.class));
        verify(sessionLockManager).executeWithLock(eq(sessionKey), any());
    }

    @Test
    void testRecordOutcomeFailsWhenWinnerDriverNotRegistered() {
        String sessionKey = "9157";
        Integer invalidWinner = 99;

        F1Driver driver1 = F1Driver.builder().driverNumber(1).fullName("Max Verstappen").build();
        when(f1EventProvider.getDriversForSession(sessionKey)).thenReturn(List.of(driver1));
        when(eventOutcomeRepository.existsBySessionKey(sessionKey)).thenReturn(false);

        BettingException ex = assertThrows(BettingException.class,
                () -> outcomeService.recordOutcome(sessionKey, invalidWinner));

        assertTrue(ex.getMessage().contains("is not registered in session"));
        verify(eventOutcomeRepository, never()).save(any(EventOutcome.class));
        verify(betRepository, never()).save(any(Bet.class));
    }

    @Test
    void testRecordOutcomeFailsWhenOutcomeAlreadyRecorded() {
        String sessionKey = "9157";
        when(eventOutcomeRepository.existsBySessionKey(sessionKey)).thenReturn(true);

        BettingException ex = assertThrows(BettingException.class,
                () -> outcomeService.recordOutcome(sessionKey, 1));

        assertTrue(ex.getMessage().contains("already has a recorded outcome") || ex.getMessage().contains("already been recorded"));
        verify(f1EventProvider, never()).getDriversForSession(anyString());
        verify(eventOutcomeRepository, never()).save(any(EventOutcome.class));
    }

    @Test
    void testRecordOutcomeHandlesDataIntegrityViolationGracefully() {
        String sessionKey = "9157";
        Integer winnerNumber = 1;

        F1Driver driver1 = F1Driver.builder().driverNumber(1).fullName("Max Verstappen").build();
        when(f1EventProvider.getDriversForSession(sessionKey)).thenReturn(List.of(driver1));
        when(eventOutcomeRepository.existsBySessionKey(sessionKey)).thenReturn(false);

        when(eventOutcomeRepository.save(any(EventOutcome.class)))
                .thenThrow(new DataIntegrityViolationException("Unique constraint violation"));

        BettingException ex = assertThrows(BettingException.class,
                () -> outcomeService.recordOutcome(sessionKey, winnerNumber));

        assertTrue(ex.getMessage().contains("already been recorded"));
    }

    @Test
    void testGetOutcomeSuccess() {
        EventOutcome outcome = EventOutcome.builder()
                .id(1L)
                .sessionKey("9157")
                .winnerDriverNumber(1)
                .recordedAt(LocalDateTime.now())
                .build();

        when(eventOutcomeRepository.findBySessionKey("9157")).thenReturn(Optional.of(outcome));

        EventOutcome result = outcomeService.getOutcome("9157");
        assertNotNull(result);
        assertEquals("9157", result.getSessionKey());
        assertEquals(1, result.getWinnerDriverNumber());
    }

    @Test
    void testGetOutcomeNotFoundThrowsResourceNotFoundException() {
        when(eventOutcomeRepository.findBySessionKey("unknown")).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> outcomeService.getOutcome("unknown"));
    }
}
