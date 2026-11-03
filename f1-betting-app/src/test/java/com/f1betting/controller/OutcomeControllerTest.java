package com.f1betting.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.f1betting.dto.OutcomeResultDto;
import com.f1betting.dto.RecordOutcomeRequest;
import com.f1betting.exception.BettingException;
import com.f1betting.service.OutcomeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class OutcomeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private OutcomeService outcomeService;

    @Test
    void testRecordOutcomeSuccess() throws Exception {
        OutcomeResultDto result = OutcomeResultDto.builder()
                .sessionKey("9157")
                .winnerDriverNumber(1)
                .totalBetsSettled(2)
                .betsWon(1)
                .betsLost(1)
                .totalPaidOut(new BigDecimal("60.00"))
                .settlements(List.of())
                .build();

        when(outcomeService.recordOutcome(eq("9157"), eq(1))).thenReturn(result);

        RecordOutcomeRequest request = new RecordOutcomeRequest(1);

        mockMvc.perform(post("/api/v1/events/9157/outcome")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionKey", is("9157")))
                .andExpect(jsonPath("$.winnerDriverNumber", is(1)))
                .andExpect(jsonPath("$.totalBetsSettled", is(2)))
                .andExpect(jsonPath("$.betsWon", is(1)))
                .andExpect(jsonPath("$.betsLost", is(1)))
                .andExpect(jsonPath("$.totalPaidOut", is(60.00)));
    }

    @Test
    void testRecordOutcomeValidationFailsWhenWinnerDriverNumberNegativeOrZero() throws Exception {
        RecordOutcomeRequest request = new RecordOutcomeRequest(0);

        mockMvc.perform(post("/api/v1/events/9157/outcome")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("Validation Failed")));
    }

    @Test
    void testRecordOutcomeFailsWhenOutcomeAlreadyRecorded() throws Exception {
        when(outcomeService.recordOutcome(eq("9157"), eq(1)))
                .thenThrow(new BettingException("An outcome has already been recorded for session '9157'."));

        RecordOutcomeRequest request = new RecordOutcomeRequest(1);

        mockMvc.perform(post("/api/v1/events/9157/outcome")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error", is("Betting Rule Violation")))
                .andExpect(jsonPath("$.message", containsString("already been recorded")));
    }

    @Test
    void testRecordOutcomeMalformedJsonReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/events/9157/outcome")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ invalid json }".getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("Bad Request")))
                .andExpect(jsonPath("$.message", is("Malformed JSON request body")));
    }
}
