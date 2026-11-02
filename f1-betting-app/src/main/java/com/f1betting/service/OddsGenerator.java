package com.f1betting.service;

import org.springframework.stereotype.Component;

import java.util.Random;

/**
 * Generates betting odds for F1 drivers according to business requirements.
 *
 * <p>Requirements specify that odds must be 2, 3, or 4.
 * Extracting this logic into a dedicated component eliminates duplication
 * and makes it easy to substitute with weighted or dynamic odds engines.
 */
@Component
public class OddsGenerator {

    /** Possible odds values per requirements: 2, 3, or 4. */
    private static final int[] POSSIBLE_ODDS = {2, 3, 4};

    private final Random random = new Random();

    /**
     * Generates a random odds value of 2, 3, or 4.
     *
     * @return generated integer odds
     */
    public int generateOdds() {
        return POSSIBLE_ODDS[random.nextInt(POSSIBLE_ODDS.length)];
    }
}
