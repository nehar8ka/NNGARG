package com.f1betting.controller;

import com.f1betting.adapter.F1EventProvider;
import com.f1betting.adapter.model.F1Driver;
import com.f1betting.adapter.model.F1Session;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class EventControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private F1EventProvider f1EventProvider;

    @Test
    void testListEventsReturnsEventsWithDriverMarket() throws Exception {
        F1Session session = F1Session.builder()
                .sessionKey("9161")
                .sessionName("Race")
                .sessionType("Race")
                .year(2023)
                .countryName("Italy")
                .circuitName("Monza")
                .dateStart(OffsetDateTime.parse("2023-09-03T13:00:00Z"))
                .dateEnd(OffsetDateTime.parse("2023-09-03T15:00:00Z"))
                .build();

        F1Driver driver = F1Driver.builder()
                .driverNumber(1)
                .fullName("Max VERSTAPPEN")
                .teamName("Red Bull Racing")
                .nameAcronym("VER")
                .countryCode("NED")
                .build();

        when(f1EventProvider.getSessions(any())).thenReturn(List.of(session));
        when(f1EventProvider.getDriversForSession("9161")).thenReturn(List.of(driver));

        mockMvc.perform(get("/api/v1/events")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].sessionKey", is("9161")))
                .andExpect(jsonPath("$[0].sessionName", is("Race")))
                .andExpect(jsonPath("$[0].driverMarket", hasSize(1)))
                .andExpect(jsonPath("$[0].driverMarket[0].driverNumber", is(1)))
                .andExpect(jsonPath("$[0].driverMarket[0].fullName", is("Max VERSTAPPEN")))
                .andExpect(jsonPath("$[0].driverMarket[0].odds", anyOf(is(2), is(3), is(4))));
    }
}
