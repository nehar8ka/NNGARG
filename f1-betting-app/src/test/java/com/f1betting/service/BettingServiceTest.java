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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
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

    @InjectMocks
    private BettingService bettingService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(bettingService, "driverValidationTimeoutSeconds", 3L);
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

        User user = User.builder()
                .id(1L)
                .username("alice")
                .balance(new BigDecimal("100.00"))
                .build();

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
        assertEquals("alice", result.getUsername());
        assertEquals(new BigDecimal("20.00"), result.getStakeAmount());
        assertEquals(new BigDecimal("80.00"), user.getBalance());
        assertEquals(BetStatus.PENDING, result.getStatus());

        // Verify order: outcome checked, driver checked, then user locked
        verify(eventOutcomeRepository).existsBySessionKey("9161");
        verify(f1EventProvider).getDriversForSession("9161");
        verify(userRepository).findByIdForUpdate(1L);
        verify(userRepository).save(user);
    }

    @Test
    void testPlaceBetRejectsWhenOutcomeAlreadyRecordedWithoutLockingUser() {
        PlaceBetRequest request = createRequest(1L, "9161", 1, new BigDecimal("20.00"));

        when(eventOutcomeRepository.existsBySessionKey("9161")).thenReturn(true);

        BettingException ex = assertThrows(BettingException.class, () -> bettingService.placeBet(request));
        assertTrue(ex.getMessage().contains("already has a recorded outcome"));

        // User row lock must NEVER be acquired when outcome is already recorded
        verify(userRepository, never()).findByIdForUpdate(anyLong());
        verify(f1EventProvider, never()).getDriversForSession(anyString());
    }

    @Test
    void testPlaceBetRejectsWhenDriverNotInSessionWithoutLockingUser() {
        PlaceBetRequest request = createRequest(1L, "9161", 99, new BigDecimal("20.00"));

        F1Driver driver = F1Driver.builder().driverNumber(1).fullName("Max Verstappen").build();

        when(eventOutcomeRepository.existsBySessionKey("9161")).thenReturn(false);
        when(f1EventProvider.getDriversForSession("9161")).thenReturn(List.of(driver));

        BettingException ex = assertThrows(BettingException.class, () -> bettingService.placeBet(request));
        assertTrue(ex.getMessage().contains("Driver #99 is not registered"));

        // User row lock must NEVER be acquired when driver validation fails
        verify(userRepository, never()).findByIdForUpdate(anyLong());
    }

    @Test
    void testPlaceBetRejectsWhenDriverValidationTimesOutWithoutLockingUser() {
        PlaceBetRequest request = createRequest(1L, "9161", 1, new BigDecimal("20.00"));

        ReflectionTestUtils.setField(bettingService, "driverValidationTimeoutSeconds", 1L);

        when(eventOutcomeRepository.existsBySessionKey("9161")).thenReturn(false);
        when(f1EventProvider.getDriversForSession("9161")).thenAnswer(invocation -> {
            Thread.sleep(1500); // simulate slow provider exceeding 1s timeout
            return List.of();
        });

        BettingException ex = assertThrows(BettingException.class, () -> bettingService.placeBet(request));
        assertTrue(ex.getMessage().contains("Timed out validating driver registration"));

        // User row lock must NEVER be acquired on provider timeout
        verify(userRepository, never()).findByIdForUpdate(anyLong());
    }

    @Test
    void testPlaceBetRejectsWhenUserNotFound() {
        PlaceBetRequest request = createRequest(999L, "9161", 1, new BigDecimal("20.00"));

        F1Driver driver = F1Driver.builder().driverNumber(1).fullName("Max Verstappen").build();

        when(eventOutcomeRepository.existsBySessionKey("9161")).thenReturn(false);
        when(f1EventProvider.getDriversForSession("9161")).thenReturn(List.of(driver));
        when(userRepository.findByIdForUpdate(999L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> bettingService.placeBet(request));
    }

    @Test
    void testPlaceBetRejectsWhenInsufficientBalance() {
        PlaceBetRequest request = createRequest(1L, "9161", 1, new BigDecimal("200.00"));

        User user = User.builder()
                .id(1L)
                .username("alice")
                .balance(new BigDecimal("50.00"))
                .build();

        F1Driver driver = F1Driver.builder().driverNumber(1).fullName("Max Verstappen").build();

        when(eventOutcomeRepository.existsBySessionKey("9161")).thenReturn(false);
        when(f1EventProvider.getDriversForSession("9161")).thenReturn(List.of(driver));
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(user));

        BettingException ex = assertThrows(BettingException.class, () -> bettingService.placeBet(request));
        assertTrue(ex.getMessage().contains("Insufficient balance"));
        verify(userRepository, never()).save(any());
        verify(betRepository, never()).save(any());
    }
}
