package io.crewscope.evaluation;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps validation failures to a public API error envelope. */
@RestControllerAdvice
public final class ApiExceptionHandler {

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<Map<String, String>> validation(MethodArgumentNotValidException error) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(Map.of("code", "VALIDATION_FAILED", "message", error.getMessage()));
  }
}
