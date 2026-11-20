package kr.dmu.hospital.common;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiErrorHandler {
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<?> handle(ApiException error) {
        return ResponseEntity.status(error.getStatus()).body(Map.of("message", error.getMessage()));
    }
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<?> invalid(Exception error) {
        return ResponseEntity.badRequest().body(Map.of("message", "입력 형식을 확인해 주세요."));
    }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> unexpected(Exception error) {
        // Never return upstream URLs, credentials, stack traces, or database internals.
        System.err.println("Request failed: " + error.getClass().getSimpleName());
        return ResponseEntity.internalServerError().body(Map.of("message", "요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요."));
    }
}
