package com.example.tasks.controller;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.tasks.exception.GlobalExceptionHandler;
import com.example.tasks.service.TaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class TaskControllerTest {

  @Mock private TaskService service;

  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    mvc =
        MockMvcBuilders.standaloneSetup(new TaskController(service))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "URGENT", "high"})
  void invalidPriorityReturns400WithProblemDetail(String priority) throws Exception {
    mvc.perform(get("/api/tasks").param("priority", priority))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.title").value("Prioridad no válida"))
        .andExpect(
            jsonPath("$.detail").value("El parámetro 'priority' debe ser LOW, MEDIUM o HIGH"));

    verifyNoInteractions(service);
  }
}
