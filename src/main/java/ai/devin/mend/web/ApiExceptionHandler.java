package ai.devin.mend.web;

import ai.devin.mend.exception.DevinApiException;
import ai.devin.mend.exception.IllegalStateTransitionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns the exceptions the {@code /api} surface can raise into {@link ApiError} bodies with a status
 * that says whose fault it was. Scoped to {@link ApiController}: the dashboard's MVC pages keep their
 * own rendered error page, and {@code CredentialAdvice}/{@code EngineAdvice} are model helpers, not
 * error handlers.
 */
@RestControllerAdvice(assignableTypes = ApiController.class)
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> rejected(IllegalArgumentException e) {
        return error(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> invalid(MethodArgumentNotValidException e) {
        String reason = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .sorted()
                .reduce((a, b) -> a + "; " + b)
                .orElse("invalid request");
        return error(HttpStatus.BAD_REQUEST, reason);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> unreadable(HttpMessageNotReadableException e) {
        return error(HttpStatus.BAD_REQUEST, "request body is not valid JSON");
    }

    @ExceptionHandler(IllegalStateTransitionException.class)
    public ResponseEntity<ApiError> conflict(IllegalStateTransitionException e) {
        return error(HttpStatus.CONFLICT, e.getMessage());
    }

    /** Devin's reply is never echoed: the message names the operation, the cause stays in the log. */
    @ExceptionHandler(DevinApiException.class)
    public ResponseEntity<ApiError> upstream(DevinApiException e) {
        log.warn("devin_api_failure {}", e.getMessage(), e);
        return error(HttpStatus.BAD_GATEWAY, e.getMessage());
    }

    private static ResponseEntity<ApiError> error(HttpStatus status, String reason) {
        return ResponseEntity.status(status).body(new ApiError(reason));
    }
}
