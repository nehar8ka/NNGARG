package com.f1betting.service;

import com.f1betting.adapter.F1EventProvider;
import com.f1betting.adapter.model.F1Driver;
import com.f1betting.domain.Bet;
import com.f1betting.domain.User;
import com.f1betting.dto.BetDto;
import com.f1betting.dto.PlaceBetRequest;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BettingServiceTest {

    @Mock
    private BetRepository betRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private EventOutcomeRepository eventOutcomeRepository;

    @Mock
    private F1EventProvider f1EventProvider;

    @Spy
    private OddsGenerator oddsGenerator = new OddsGenerator();

    @Spy
    private SessionLockManager sessionLockManager = new SessionLockManager();

    @Mock
    private TransactionTemplate transactionTemplate;

    @Spy
    private ExecutorService f1ValidationExecutor = Executors.newSingleThreadExecutor();

    @InjectMocks
    private BettingService bettingService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(bettingService, "driverValidationTimeoutSeconds", 3L);
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
    }

    @AfterEach
    void tearDown() {
        f1ValidationExecutor.shutdown();
    }

    private PlaceBetRequest createRequest(Long userId, String sessionKey, Integer driverNumber, BigDecimal stakeAmount) {
        PlaceBetRequest request = new PlaceBetRequest();
        request.setUserId(userId);
        request.setSessionKey(sessionKey);
        request.setDriverNumber(driverNumber);
        request.setStakeAmount(stakeAmount);
        return request;
    }

    @Test
    void testPlaceBetSuccessfulPlacement() {
        PlaceBetRequest request = createRequest(1L, "9161", 1, new BigDecimal("20.00"));
        User user = User.builder().id(1L).username("alice").balance(new BigDecimal("100.00")).build();
        F1Driver driver = F1Driver.builder().driverNumber(1).fullName("Max Verstappen").build();

        when(eventOutcomeRepository.existsBySessionKey("9161")).thenReturn(false);
        when(f1EventProvider.getDriversForSession("9161")).thenReturn(List.of(driver));
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(user));
        when(betRepository.save(any(Bet.class))).thenAnswer(invocation -> {
            Bet b = invocation.getArgument(0);
            b.setId(10L);
            return b;
        });

        BetDto result = bettingService.placeBet(request);

        assertNotNull(result);
        assertEquals(10L, result.getId());
        assertEquals(1L, result.getUserId());
        assertEquals("alice", result.getUsername());
        assertEquals("9161", result.getSessionKey());
        assertEquals(1, result.getDriverNumber());
        assertEquals(new BigDecimal("20.00"), result.getStakeAmount());
        assertTrue(result.getOdds() >= 2 && result.getOdds() <= 4);
        assertEquals(result.getStakeAmount().multiply(BigDecimal.valueOf(result.getOdds())), result.getPotentialPayout());
        assertEquals(BetStatus.PENDING, result.getStatus());
        assertEquals(new BigDecimal("80.00"), user.getBalance());

        verify(eventOutcomeRepository, atLeastOnce()).existsBySessionKey("9161");
        verify(f1EventProvider).getDriversForSession("9161");
        verify(userRepository).save(user);
        verify(betRepository).save(any(Bet.class));
    }

    @Test
    void testPlaceBetFailsWhenOutcomeAlreadyRecorded() {
        PlaceBetRequest request = createRequest(1L, "9161", 1, new BigDecimal("20.00"));

        when(eventOutcomeRepository.existsBySessionKey("9161")).thenReturn(true);

        BettingException ex = assertThrows(BettingException.class, () -> bettingService.placeBet(request));
        assertTrue(ex.getMessage().contains("already has a recorded outcome"));

        verify(f1EventProvider, never()).getDriversForSession(anyString());
        verify(userRepository, never()).findByIdForUpdate(anyLong());
        verify(betRepository, never()).save(any(Bet.class));
    }

    @Test
    void testPlaceBetFailsWhenDriverNotRegisteredInSession() {
        PlaceBetRequest request = createRequest(1L, "9161", 99, new BigDecimal("20.00"));
        F1Driver driver = F1Driver.builder().driverNumber(1).fullName("Max Verstappen").build();

        when(eventOutcomeRepository.existsBySessionKey("9161")).thenReturn(false);
        when(f1EventProvider.getDriversForSession("9161")).thenReturn(List.of(driver));

        BettingException ex = assertThrows(BettingException.class, () -> bettingService.placeBet(request));
        assertTrue(ex.getMessage().contains("Driver #99 is not registered"));

        verify(userRepository, never()).findByIdForUpdate(anyLong());
        verify(betRepository, never()).save(any(Bet.class));
    }

    @Test
    void testPlaceBetFailsWhenUserNotFound() {
        PlaceBetRequest request = createRequest(999L, "9161", 1, new BigDecimal("20.00"));
        F1Driver driver = F1Driver.builder().driverNumber(1).fullName("Max Verstappen").build();

        when(eventOutcomeRepository.existsBySessionKey("9161")).thenReturn(false);
        when(f1EventProvider.getDriversForSession("9161")).thenReturn(List.of(driver));
        when(userRepository.findByIdForUpdate(999L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> bettingService.placeBet(request));

        verify(betRepository, never()).save(any(Bet.class));
    }

    @Test
    void testPlaceBetFailsWhenBalanceInsufficient() {
        PlaceBetRequest request = createRequest(1L, "9161", 1, new BigDecimal("150.00"));
        User user = User.builder().id(1L).username("alice").balance(new BigDecimal("100.00")).build();
        F1Driver driver = F1Driver.builder().driverNumber(1).fullName("Max Verstappen").build();

        when(eventOutcomeRepository.existsBySessionKey("9161")).thenReturn(false);
        when(f1EventProvider.getDriversForSession("9161")).thenReturn(List.of(driver));
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(user));

        BettingException ex = assertThrows(BettingException.class, () -> bettingService.placeBet(request));
        assertTrue(ex.getMessage().contains("Insufficient balance"));

        assertEquals(new BigDecimal("100.00"), user.getBalance());
        verify(betRepository, never()).save(any(Bet.class));
    }

    @Test
    void testPlaceBetConcurrentOutcomeRaceDetectedInsideTransaction() {
        PlaceBetRequest request = createRequest(1L, "9161", 1, new BigDecimal("20.00"));
        F1Driver driver = F1Driver.builder().driverNumber(1).fullName("Max Verstappen").build();

        // 1st check before lock passes, 2nd check inside lock fails (outcome was recorded concurrently)
        when(eventOutcomeRepository.existsBySessionKey("9161")).thenReturn(false, true);
        when(f1EventProvider.getDriversForSession("9161")).thenReturn(List.of(driver));

        BettingException ex = assertThrows(BettingException.class, () -> bettingService.placeBet(request));
        assertTrue(ex.getMessage().contains("already has a recorded outcome"));

        verify(userRepository, never()).findByIdForUpdate(anyLong());
        verify(betRepository, never()).save(any(Bet.class));
    }
}
