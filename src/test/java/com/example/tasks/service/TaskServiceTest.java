package com.example.tasks.service;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.tasks.domain.Task;
import com.example.tasks.domain.TaskPriority;
import com.example.tasks.domain.TaskStatus;
import com.example.tasks.dto.TaskRequest;
import com.example.tasks.dto.TaskResponse;
import com.example.tasks.exception.TaskNotFoundException;
import com.example.tasks.repository.TaskRepository;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class TaskServiceTest {

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-09-21T12:00:00Z"), ZoneOffset.UTC);
  private static final LocalDate TODAY = LocalDate.now(CLOCK);
  private static ValidatorFactory validatorFactory;

  @Mock private TaskRepository repository;

  private TaskService service;

  @BeforeAll
  static void createValidator() {
    validatorFactory =
        Validation.byDefaultProvider()
            .configure()
            .clockProvider(() -> CLOCK)
            .buildValidatorFactory();
  }

  @AfterAll
  static void closeValidator() {
    validatorFactory.close();
  }

  @BeforeEach
  void setUp() {
    service = new TaskService(repository, validatorFactory.getValidator());
  }

  @Test
  void createPersistsAllFieldsAndTrimsTitle() {
    TaskRequest request =
        new TaskRequest(
            "  Preparar entrega  ",
            "Revisar el boletín",
            TaskStatus.TODO,
            TaskPriority.HIGH,
            TODAY.plusDays(1));
    when(repository.save(any(Task.class)))
        .thenAnswer(
            invocation -> {
              Task task = invocation.getArgument(0);
              ReflectionTestUtils.setField(task, "id", 1L);
              return task;
            });

    TaskResponse result = service.create(request);

    assertAll(
        () -> assertEquals(1L, result.id()),
        () -> assertEquals("Preparar entrega", result.title()),
        () -> assertEquals(request.description(), result.description()),
        () -> assertEquals(TaskStatus.TODO, result.status()),
        () -> assertEquals(TaskPriority.HIGH, result.priority()),
        () -> assertEquals(TODAY.plusDays(1), result.dueDate()));
    verify(repository).save(any(Task.class));
  }

  @Test
  void createAcceptsDeadlineToday() {
    when(repository.save(any(Task.class))).thenAnswer(invocation -> invocation.getArgument(0));

    TaskResponse result =
        service.create(
            new TaskRequest("Entrega de hoy", null, TaskStatus.TODO, TaskPriority.MEDIUM, TODAY));

    assertEquals(TODAY, result.dueDate());
    verify(repository).save(any(Task.class));
  }

  @Test
  void createAcceptsMaximumFieldLengths() {
    when(repository.save(any(Task.class))).thenAnswer(invocation -> invocation.getArgument(0));

    TaskResponse result =
        service.create(
            new TaskRequest(
                "a".repeat(120), "b".repeat(2000), TaskStatus.TODO, TaskPriority.LOW, TODAY));

    assertEquals(120, result.title().length());
    assertEquals(2000, result.description().length());
  }

  @Test
  void createAllowsMissingDescription() {
    when(repository.save(any(Task.class))).thenAnswer(invocation -> invocation.getArgument(0));

    TaskResponse result =
        service.create(
            new TaskRequest("Sin descripción", null, TaskStatus.TODO, TaskPriority.LOW, TODAY));

    assertNull(result.description());
    assertEquals("Sin descripción", result.title());
    verify(repository).save(any(Task.class));
  }

  @ParameterizedTest(name = "Crear rechaza: {0}")
  @MethodSource("invalidRequests")
  void createRejectsInvalidDataWithoutUsingRepository(
      String rule, TaskRequest request, String invalidField) {
    ConstraintViolationException exception =
        assertThrows(ConstraintViolationException.class, () -> service.create(request));

    assertTrue(
        exception.getConstraintViolations().stream()
            .anyMatch(violation -> violation.getPropertyPath().toString().equals(invalidField)));
    verifyNoInteractions(repository);
  }

  @ParameterizedTest(name = "Actualizar rechaza: {0}")
  @MethodSource("invalidRequests")
  void updateRejectsInvalidDataWithoutChangingExistingTask(
      String rule, TaskRequest request, String invalidField) {
    Task existing = task(1L);
    TaskResponse before = TaskResponse.from(existing);
    when(repository.findById(1L)).thenReturn(Optional.of(existing));

    ConstraintViolationException exception =
        assertThrows(ConstraintViolationException.class, () -> service.update(1L, request));

    assertTrue(
        exception.getConstraintViolations().stream()
            .anyMatch(violation -> violation.getPropertyPath().toString().equals(invalidField)));
    assertEquals(before, TaskResponse.from(existing));
    verify(repository, never()).save(any(Task.class));
  }

  @Test
  void updateReplacesFieldsAndKeepsId() {
    Task existing = task(1L);
    when(repository.findById(1L)).thenReturn(Optional.of(existing));
    when(repository.save(existing)).thenReturn(existing);
    TaskRequest request =
        new TaskRequest("  Tarea actualizada  ", null, TaskStatus.DONE, TaskPriority.HIGH, TODAY);

    TaskResponse result = service.update(1L, request);

    assertEquals(
        new TaskResponse(1L, "Tarea actualizada", null, TaskStatus.DONE, TaskPriority.HIGH, TODAY),
        result);
    assertEquals(result, TaskResponse.from(existing));
    verify(repository).save(existing);
  }

  @Test
  void updateMissingTaskDoesNotCreateIt() {
    when(repository.findById(99L)).thenReturn(Optional.empty());

    assertThrows(TaskNotFoundException.class, () -> service.update(99L, validRequest()));

    verify(repository, never()).save(any(Task.class));
  }

  @Test
  void findMissingTaskThrowsNotFound() {
    when(repository.findById(99L)).thenReturn(Optional.empty());

    TaskNotFoundException exception =
        assertThrows(TaskNotFoundException.class, () -> service.findById(99L));

    assertEquals("No existe la tarea con id 99", exception.getMessage());
  }

  @Test
  void alreadyStoredOverdueTaskCanStillBeRead() {
    Task overdue =
        new Task(
            "Tarea vencida", null, TaskStatus.IN_PROGRESS, TaskPriority.HIGH, TODAY.minusDays(5));
    ReflectionTestUtils.setField(overdue, "id", 7L);
    when(repository.findById(7L)).thenReturn(Optional.of(overdue));

    TaskResponse result = service.findById(7L);

    assertEquals(7L, result.id());
    assertEquals(TODAY.minusDays(5), result.dueDate());
    assertEquals(TaskStatus.IN_PROGRESS, result.status());
  }

  @Test
  void findAllReturnsTasksOrderedById() {
    Task first = task(1L);
    Task second = task(2L);
    when(repository.findAll(Sort.by("id"))).thenReturn(List.of(first, second));

    List<TaskResponse> result = service.findAll(null, null);

    assertEquals(List.of(TaskResponse.from(first), TaskResponse.from(second)), result);
    verify(repository).findAll(Sort.by("id"));
  }

  @Test
  void findAllFiltersByStatusAndOrdersById() {
    Task first = task(1L, TaskStatus.IN_PROGRESS);
    Task second = task(2L, TaskStatus.IN_PROGRESS);
    when(repository.findAllByStatus(TaskStatus.IN_PROGRESS, Sort.by("id")))
        .thenReturn(List.of(first, second));

    List<TaskResponse> result = service.findAll(TaskStatus.IN_PROGRESS, null);

    assertEquals(List.of(TaskResponse.from(first), TaskResponse.from(second)), result);
    verify(repository).findAllByStatus(TaskStatus.IN_PROGRESS, Sort.by("id"));
  }

  @Test
  void findAllFiltersByLowPriorityAndOrdersById() {
    Task first = task(1L, TaskStatus.TODO, TaskPriority.LOW);
    when(repository.findAllByPriority(TaskPriority.LOW, Sort.by("id"))).thenReturn(List.of(first));

    List<TaskResponse> result = service.findAll(null, TaskPriority.LOW);

    assertEquals(List.of(TaskResponse.from(first)), result);
    verify(repository).findAllByPriority(TaskPriority.LOW, Sort.by("id"));
  }

  @Test
  void findAllFiltersByMediumPriorityAndOrdersById() {
    Task first = task(1L, TaskStatus.TODO, TaskPriority.MEDIUM);
    when(repository.findAllByPriority(TaskPriority.MEDIUM, Sort.by("id")))
        .thenReturn(List.of(first));

    List<TaskResponse> result = service.findAll(null, TaskPriority.MEDIUM);

    assertEquals(List.of(TaskResponse.from(first)), result);
    verify(repository).findAllByPriority(TaskPriority.MEDIUM, Sort.by("id"));
  }

  @Test
  void findAllFiltersByHighPriorityAndOrdersById() {
    Task first = task(1L, TaskStatus.TODO, TaskPriority.HIGH);
    when(repository.findAllByPriority(TaskPriority.HIGH, Sort.by("id"))).thenReturn(List.of(first));

    List<TaskResponse> result = service.findAll(null, TaskPriority.HIGH);

    assertEquals(List.of(TaskResponse.from(first)), result);
    verify(repository).findAllByPriority(TaskPriority.HIGH, Sort.by("id"));
  }

  @Test
  void findAllFiltersByStatusAndPriorityCombinedAndOrdersById() {
    Task first = task(1L, TaskStatus.IN_PROGRESS, TaskPriority.HIGH);
    when(repository.findAllByStatusAndPriority(
            TaskStatus.IN_PROGRESS, TaskPriority.HIGH, Sort.by("id")))
        .thenReturn(List.of(first));

    List result = service.findAll(TaskStatus.IN_PROGRESS, TaskPriority.HIGH);

    assertEquals(List.of(TaskResponse.from(first)), result);
    verify(repository)
        .findAllByStatusAndPriority(TaskStatus.IN_PROGRESS, TaskPriority.HIGH, Sort.by("id"));
  }

  @Test
  void findAllReturnsEmptyListWhenThereAreNoTasks() {
    when(repository.findAll(Sort.by("id"))).thenReturn(List.of());

    assertEquals(List.of(), service.findAll(null, null));
  }

  @Test
  void findAllReturnsEmptyListWhenPriorityHasNoMatches() {
    when(repository.findAllByPriority(TaskPriority.HIGH, Sort.by("id"))).thenReturn(List.of());

    List<TaskResponse> result = service.findAll(null, TaskPriority.HIGH);

    assertEquals(List.of(), result);
    verify(repository).findAllByPriority(TaskPriority.HIGH, Sort.by("id"));
  }

  @Test
  void deleteRemovesExistingTask() {
    Task existing = task(1L);
    when(repository.findById(1L)).thenReturn(Optional.of(existing));

    service.delete(1L);

    verify(repository).delete(existing);
  }

  @Test
  void deleteMissingTaskThrowsWithoutDeletingAnything() {
    when(repository.findById(99L)).thenReturn(Optional.empty());

    assertThrows(TaskNotFoundException.class, () -> service.delete(99L));

    verify(repository, never()).delete(any(Task.class));
  }

  private static Stream<Arguments> invalidRequests() {
    return Stream.of(
        Arguments.of(
            "fecha pasada",
            new TaskRequest("Título", null, TaskStatus.TODO, TaskPriority.LOW, TODAY.minusDays(1)),
            "dueDate"),
        Arguments.of(
            "título ausente",
            new TaskRequest(null, null, TaskStatus.TODO, TaskPriority.LOW, TODAY),
            "title"),
        Arguments.of(
            "título vacío",
            new TaskRequest("", null, TaskStatus.TODO, TaskPriority.LOW, TODAY),
            "title"),
        Arguments.of(
            "título en blanco",
            new TaskRequest(" \t\n ", null, TaskStatus.TODO, TaskPriority.LOW, TODAY),
            "title"),
        Arguments.of(
            "título demasiado largo",
            new TaskRequest("a".repeat(121), null, TaskStatus.TODO, TaskPriority.LOW, TODAY),
            "title"),
        Arguments.of(
            "descripción demasiado larga",
            new TaskRequest("Título", "b".repeat(2001), TaskStatus.TODO, TaskPriority.LOW, TODAY),
            "description"),
        Arguments.of(
            "estado ausente",
            new TaskRequest("Título", null, null, TaskPriority.LOW, TODAY),
            "status"),
        Arguments.of(
            "prioridad ausente",
            new TaskRequest("Título", null, TaskStatus.TODO, null, TODAY),
            "priority"),
        Arguments.of(
            "fecha ausente",
            new TaskRequest("Título", null, TaskStatus.TODO, TaskPriority.LOW, null),
            "dueDate"));
  }

  private TaskRequest validRequest() {
    return new TaskRequest(
        "Título", "Descripción", TaskStatus.TODO, TaskPriority.LOW, TODAY.plusDays(1));
  }

  private Task task(Long id) {
    return task(id, TaskStatus.TODO);
  }

  private Task task(Long id, TaskStatus status) {
    Task task =
        new Task(
            "Título original", "Descripción original", status, TaskPriority.LOW, TODAY.plusDays(1));
    ReflectionTestUtils.setField(task, "id", id);
    return task;
  }

  private Task task(Long id, TaskStatus status, TaskPriority priority) {
    Task task =
        new Task("Título original", "Descripción original", status, priority, TODAY.plusDays(1));
    ReflectionTestUtils.setField(task, "id", id);
    return task;
  }
}
