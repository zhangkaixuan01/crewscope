package io.crewscope.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.MethodArgumentNotValidException;

class ApiExceptionHandlerJudgeTest {

  @Test
  void emitsAStableEnvelopeWithoutRawExceptionText() {
    MethodArgumentNotValidException error = mock(MethodArgumentNotValidException.class);
    when(error.getMessage()).thenReturn("rejected password=super-secret");

    var response = new ApiExceptionHandler().validation(error);
    assertEquals("VALIDATION_FAILED", response.getBody().get("code"));
    assertEquals("Request validation failed", response.getBody().get("message"));
    assertFalse(response.toString().contains("super-secret"));
  }
}
