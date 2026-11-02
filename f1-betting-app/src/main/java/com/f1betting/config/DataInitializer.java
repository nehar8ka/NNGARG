package com.f1betting.config;

import com.f1betting.domain.User;
import com.f1betting.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * Seeds the database with pre-registered users on first startup.
 *
 * <p>Per the requirements, users are already registered and each receives
 * a starting gift balance of 100 EUR. This initialiser is idempotent —
 * it will not re-seed if users already exist.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DataInitializer implements ApplicationRunner {

    private static final BigDecimal STARTING_BALANCE = new BigDecimal("100.00");

    private final UserRepository userRepository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (userRepository.count() > 0) {
            log.info("Users already seeded — skipping DataInitializer.");
            return;
        }

        List<User> users = List.of(
                User.builder().username("alice").balance(STARTING_BALANCE).build(),
                User.builder().username("bob").balance(STARTING_BALANCE).build(),
                User.builder().username("charlie").balance(STARTING_BALANCE).build()
        );

        userRepository.saveAll(users);
        log.info("Seeded {} users, each with {} EUR starting balance.", users.size(), STARTING_BALANCE);
    }
}
