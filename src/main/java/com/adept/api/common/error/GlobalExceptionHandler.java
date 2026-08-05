package com.adept.api.common.error;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts every exception thrown by controllers and services into an
 * {@code application/problem+json} response (RFC 9457 / RFC 7807) using Spring
 * Framework's built-in {@link ProblemDetail}.
 *
 * <p>Error format contract (from the fixed project decisions):
 * <pre>
 * {
 *   "type":     "https://adept.example/problems/repository-forbidden",
 *   "title":    "Repository access denied",
 *   "status":   403,
 *   "detail":   "The current membership cannot access this repository.",
 *   "instance": "/api/v1/repositories/uuid",
 *   "code":     "REPOSITORY_FORBIDDEN",
 *   "traceId":  "..."
 * }
 * </pre>
 *
 * <p>Rules:
 * <ul>
 *   <li>Never expose stack traces, SQL messages, or internal class names.</li>
 *   <li>{@code detail} must be safe user-facing text, not a Java exception message.</li>
 *   <li>{@code code} is a SCREAMING_SNAKE_CASE identifier the frontend can match.</li>
 *   <li>{@code traceId} is the MDC {@code traceId} value when present, enabling log correlation.</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // Base URI prefix used in the "type" field to categorise problem types.
    private static final String PROBLEM_BASE_URI = "https://adept.example/problems/";

    // -------------------------------------------------------------------------
    // Adept domain exceptions
    // -------------------------------------------------------------------------

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ProblemDetail> handleNotFound(
            NotFoundException ex, HttpServletRequest request) {
        log.debug("Not found [{}]: {}", ex.getCode(), ex.getMessage());
        ProblemDetail problem = buildProblem(
                HttpStatus.NOT_FOUND,
                ex.getCode(),
                ex.getMessage(),
                request.getRequestURI()
        );
        return response(problem);
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ProblemDetail> handleForbidden(
            ForbiddenException ex, HttpServletRequest request) {
        log.debug("Forbidden [{}]: {}", ex.getCode(), ex.getMessage());
        ProblemDetail problem = buildProblem(
                HttpStatus.FORBIDDEN,
                ex.getCode(),
                ex.getMessage(),
                request.getRequestURI()
        );
        return response(problem);
    }

    // -------------------------------------------------------------------------
    // Jakarta Validation — @Valid on @RequestBody
    // -------------------------------------------------------------------------

    /**
     * Handles validation failures on {@code @RequestBody} DTOs annotated with {@code @Valid}.
     * Returns 422 Unprocessable Entity with a per-field breakdown.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidation(
            MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<Map<String, String>> fieldErrors = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(GlobalExceptionHandler::fieldErrorEntry)
                .toList();

        ProblemDetail problem = buildProblem(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "VALIDATION_FAILED",
                "One or more fields failed validation.",
                request.getRequestURI()
        );
        problem.setProperty("fieldErrors", fieldErrors);
        return response(problem);
    }

    /**
     * Handles validation failures triggered directly on controller parameters
     * (e.g. {@code @RequestParam} with {@code @NotBlank}).
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> handleConstraintViolation(
            ConstraintViolationException ex, HttpServletRequest request) {
        List<Map<String, String>> violations = ex.getConstraintViolations()
                .stream()
                .map(GlobalExceptionHandler::violationEntry)
                .toList();

        ProblemDetail problem = buildProblem(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "VALIDATION_FAILED",
                "One or more parameters failed validation.",
                request.getRequestURI()
        );
        problem.setProperty("fieldErrors", violations);
        return response(problem);
    }

    // -------------------------------------------------------------------------
    // Spring MVC structural errors
    // -------------------------------------------------------------------------

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleUnreadableBody(
            HttpMessageNotReadableException ex, HttpServletRequest request) {
        log.debug("Unreadable request body at {}: {}", request.getRequestURI(), ex.getMessage());
        ProblemDetail problem = buildProblem(
                HttpStatus.BAD_REQUEST,
                "MALFORMED_REQUEST_BODY",
                "The request body could not be parsed.",
                request.getRequestURI()
        );
        return response(problem);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ProblemDetail> handleMissingParam(
            MissingServletRequestParameterException ex, HttpServletRequest request) {
        ProblemDetail problem = buildProblem(
                HttpStatus.BAD_REQUEST,
                "MISSING_PARAMETER",
                "Required parameter '" + ex.getParameterName() + "' is missing.",
                request.getRequestURI()
        );
        return response(problem);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ProblemDetail> handleMethodNotAllowed(
            HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
        ProblemDetail problem = buildProblem(
                HttpStatus.METHOD_NOT_ALLOWED,
                "METHOD_NOT_ALLOWED",
                "HTTP method '" + ex.getMethod() + "' is not supported for this endpoint.",
                request.getRequestURI()
        );
        return response(problem);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ProblemDetail> handleNoResourceFound(
            NoResourceFoundException ex, HttpServletRequest request) {
        ProblemDetail problem = buildProblem(
                HttpStatus.NOT_FOUND,
                "ENDPOINT_NOT_FOUND",
                "The requested endpoint does not exist.",
                request.getRequestURI()
        );
        return response(problem);
    }

    // -------------------------------------------------------------------------
    // Catch-all — never expose internal details
    // -------------------------------------------------------------------------

    /**
     * Last-resort handler for any uncaught exception. Logs the full stack trace
     * at ERROR level for operator investigation but returns only a generic 500
     * to the caller so internal details are never exposed.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(
            Exception ex, HttpServletRequest request) {
        log.error("Unexpected error at {}", request.getRequestURI(), ex);
        ProblemDetail problem = buildProblem(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR",
                "An unexpected error occurred. Please try again later.",
                request.getRequestURI()
        );
        return response(problem);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Builds a {@link ProblemDetail} with the standard Adept fields.
     *
     * @param status   HTTP status to use for both the response code and {@code status} field.
     * @param code     stable SCREAMING_SNAKE_CASE identifier included as a custom property.
     * @param detail   safe, user-facing sentence describing what went wrong.
     * @param instance the request URI identifying which resource or endpoint failed.
     */
    private static ProblemDetail buildProblem(
            HttpStatus status, String code, String detail, String instance) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        // "type" links to the category of the problem so clients can bookmark it.
        problem.setType(URI.create(PROBLEM_BASE_URI + toKebabCase(code)));
        problem.setDetail(detail);
        problem.setInstance(URI.create(instance));
        // Custom extensions: "code" for programmatic matching, "traceId" for log correlation.
        problem.setProperty("code", code);
        // traceId is populated if structured logging places it in MDC; otherwise absent.
        String traceId = org.slf4j.MDC.get("traceId");
        if (traceId != null && !traceId.isBlank()) {
            problem.setProperty("traceId", traceId);
        }
        return problem;
    }

    private static ResponseEntity<ProblemDetail> response(ProblemDetail problem) {
        return ResponseEntity
                .status(problem.getStatus())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    private static Map<String, String> fieldErrorEntry(FieldError fe) {
        Map<String, String> entry = new LinkedHashMap<>();
        entry.put("field", fe.getField());
        // defaultMessage is the annotation's message attribute — safe user text.
        entry.put("message", fe.getDefaultMessage());
        return entry;
    }

    private static Map<String, String> violationEntry(ConstraintViolation<?> v) {
        Map<String, String> entry = new LinkedHashMap<>();
        // propertyPath gives the parameter/field name from the constraint path.
        entry.put("field", v.getPropertyPath().toString());
        entry.put("message", v.getMessage());
        return entry;
    }

    /**
     * Converts SCREAMING_SNAKE_CASE to kebab-case for use in the {@code type} URI.
     * For example {@code "REPOSITORY_FORBIDDEN"} becomes {@code "repository-forbidden"}.
     */
    private static String toKebabCase(String code) {
        return code.toLowerCase().replace('_', '-');
    }
}
