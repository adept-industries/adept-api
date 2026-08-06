package com.adept.api.common.error;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.adept.api.common.web.TraceIdFilter;
import com.adept.api.security.ratelimit.RateLimitException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;

@RestControllerAdvice
public final class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final ProblemResponseFactory factory;

    public GlobalExceptionHandler(ProblemResponseFactory factory) {
        this.factory = factory;
    }

    @ExceptionHandler(RateLimitException.class)
    ResponseEntity<ProblemDetail> handleRateLimit(RateLimitException exception, HttpServletRequest request) {
        ProblemDetail problem = factory.create(exception.code(), exception.safeDetail(), request);
        return response(problem, exception.retryAfterSeconds());
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> handleApiException(ApiException exception, HttpServletRequest request) {
        return response(factory.create(exception.code(), exception.safeDetail(), request));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> handleValidation(
            MethodArgumentNotValidException exception,
            HttpServletRequest request) {
        List<FieldViolation> violations = exception.getBindingResult().getFieldErrors().stream()
            .map(error -> new FieldViolation(error.getField(), safeValidationMessage(error.getDefaultMessage())))
            .sorted(Comparator.comparing(FieldViolation::field).thenComparing(FieldViolation::message))
            .toList();
        return response(factory.create(
            ProblemCode.VALIDATION_FAILED,
            ProblemCode.VALIDATION_FAILED.defaultDetail(),
            request,
            violations
        ));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ProblemDetail> handleConstraintViolation(
            ConstraintViolationException exception,
            HttpServletRequest request) {
        List<FieldViolation> violations = exception.getConstraintViolations().stream()
            .map(violation -> new FieldViolation(
                violation.getPropertyPath().toString(),
                safeValidationMessage(violation.getMessage())
            ))
            .sorted(Comparator.comparing(FieldViolation::field).thenComparing(FieldViolation::message))
            .toList();
        return response(factory.create(
            ProblemCode.VALIDATION_FAILED,
            ProblemCode.VALIDATION_FAILED.defaultDetail(),
            request,
            violations
        ));
    }

    @ExceptionHandler({HandlerMethodValidationException.class, MissingServletRequestParameterException.class})
    ResponseEntity<ProblemDetail> handleOtherValidation(HttpServletRequest request) {
        return response(factory.create(ProblemCode.VALIDATION_FAILED, request));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ProblemDetail> handleArgumentTypeMismatch(HttpServletRequest request) {
        return response(factory.create(ProblemCode.MALFORMED_REQUEST, request));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> handleUnreadable(
            HttpMessageNotReadableException exception,
            HttpServletRequest request) {
        if (hasCause(exception, PayloadTooLargeException.class)) {
            return response(factory.create(ProblemCode.PAYLOAD_TOO_LARGE, request));
        }
        return response(factory.create(ProblemCode.MALFORMED_REQUEST, request));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ProblemDetail> handleUnsupportedMediaType(HttpServletRequest request) {
        return response(factory.create(ProblemCode.UNSUPPORTED_MEDIA_TYPE, request));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ProblemDetail> handleNotFound(HttpServletRequest request) {
        return response(factory.create(ProblemCode.ENDPOINT_NOT_FOUND, request));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ProblemDetail> handleMethodNotAllowed(HttpServletRequest request) {
        return response(factory.create(ProblemCode.METHOD_NOT_ALLOWED, request));
    }

    @ExceptionHandler({PayloadTooLargeException.class, MaxUploadSizeExceededException.class})
    ResponseEntity<ProblemDetail> handlePayloadTooLarge(HttpServletRequest request) {
        return response(factory.create(ProblemCode.PAYLOAD_TOO_LARGE, request));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ProblemDetail> handleDatabaseConflict(
            DataIntegrityViolationException exception,
            HttpServletRequest request) {
        ProblemCode code = databaseProblemCode(exception);
        return response(factory.create(code, request));
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<ProblemDetail> handleOptimisticConflict(HttpServletRequest request) {
        return response(factory.create(ProblemCode.WORKSPACE_CONFLICT, request));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception exception, HttpServletRequest request) {
        ProblemDetail problem = factory.create(ProblemCode.INTERNAL_ERROR, request);
        String traceId = problem.getProperties().get("traceId").toString();
        log.error(
            "Unexpected API failure traceId={} path={} failureType={}",
            traceId,
            request.getRequestURI(),
            exception.getClass().getName()
        );
        return response(problem);
    }

    private static ProblemCode databaseProblemCode(DataIntegrityViolationException exception) {
        Throwable cause = exception.getMostSpecificCause();
        String internalMessage = cause == null ? "" : String.valueOf(cause.getMessage());
        if (internalMessage.toLowerCase(Locale.ROOT).contains("uq_users_email_lower")) {
            return ProblemCode.EMAIL_ALREADY_EXISTS;
        }
        return ProblemCode.WORKSPACE_CONFLICT;
    }

    private static boolean hasCause(Throwable throwable, Class<? extends Throwable> type) {
        Throwable current = throwable;
        while (current != null) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static String safeValidationMessage(String message) {
        return message == null || message.isBlank() ? "is invalid" : message;
    }

    private static ResponseEntity<ProblemDetail> response(ProblemDetail problem) {
        return response(problem, null);
    }

    private static ResponseEntity<ProblemDetail> response(ProblemDetail problem, Long retryAfterSeconds) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(problem.getStatus())
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .header(
                TraceIdFilter.TRACE_ID_HEADER,
                problem.getProperties().get("traceId").toString()
            );
        if (retryAfterSeconds != null) {
            builder.header(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
        }
        return builder.body(problem);
    }
}
