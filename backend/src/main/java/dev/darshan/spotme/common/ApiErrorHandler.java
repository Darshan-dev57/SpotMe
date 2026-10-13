package dev.darshan.spotme.common;

import dev.darshan.spotme.config.SpotMeProperties;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Every error leaves the API in the same small JSON shape. Errors are also counted here:
 * auth failures, rate limit rejections and rejected uploads (see docs/OBSERVABILITY.md).
 */
@RestControllerAdvice
public class ApiErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiErrorHandler.class);

    static final String UPLOAD_ROUTE = "/api/events/{eventId}/photos";

    private final SpotMeProperties props;
    private final MeterRegistry metrics;

    public ApiErrorHandler(SpotMeProperties props, MeterRegistry metrics) {
        this.props = props;
        this.metrics = metrics;
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, String>> api(ApiException e, HttpServletRequest request) {
        return body(request, e.status(), e.code(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> invalid(MethodArgumentNotValidException e, HttpServletRequest request) {
        var field = e.getBindingResult().getFieldError();
        String msg = field == null ? "Invalid request." : field.getField() + " " + field.getDefaultMessage();
        return body(request, HttpStatus.BAD_REQUEST, "invalid_request", msg);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, String>> tooBig(MaxUploadSizeExceededException e, HttpServletRequest request) {
        return body(request, HttpStatus.PAYLOAD_TOO_LARGE, "file_too_large", "That file is larger than 20 MB.");
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<Map<String, String>> missingPart(MissingServletRequestPartException e, HttpServletRequest request) {
        return body(request, HttpStatus.BAD_REQUEST, "missing_file", "Attach a photo in the \"file\" field.");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Map<String, String>> wrongType(HttpMediaTypeNotSupportedException e, HttpServletRequest request) {
        return body(request, HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported_type", "Send the photo as a file upload.");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> unreadable(HttpMessageNotReadableException e, HttpServletRequest request) {
        return body(request, HttpStatus.BAD_REQUEST, "invalid_request", "The request body could not be read.");
    }

    /** Unknown API routes get JSON; unknown pages get the web UI's own 404 page when it is served. */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<?> noRoute(NoResourceFoundException e, HttpServletRequest request) {
        boolean pageRequest = !request.getRequestURI().startsWith("/api/");
        String webDir = props.webDir();
        if (pageRequest && webDir != null && !webDir.isBlank()) {
            Path notFoundPage = Path.of(webDir, "404.html");
            if (Files.isRegularFile(notFoundPage)) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).contentType(MediaType.TEXT_HTML)
                        .body(new FileSystemResource(notFoundPage));
            }
        }
        return body(request, HttpStatus.NOT_FOUND, "not_found", "Not found.");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, String>> wrongMethod(HttpRequestMethodNotSupportedException e, HttpServletRequest request) {
        return body(request, HttpStatus.METHOD_NOT_ALLOWED, "method_not_allowed", "That method is not allowed here.");
    }

    /** The browser closed a live stream or a download midway: there is nobody left to answer. */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void clientGone() {
        // nothing to send
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> unexpected(Exception e, HttpServletRequest request) {
        // log the type and message only: request bodies may contain faces
        log.error("Unhandled error: {}: {}", e.getClass().getSimpleName(), e.getMessage());
        return body(request, HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "Something went wrong on our side.");
    }

    // the content type is set here so errors are JSON even when the client asked for an event stream
    private ResponseEntity<Map<String, String>> body(HttpServletRequest request, HttpStatus status, String code,
                                                     String message) {
        count(request, status, code);
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("code", code, "message", message));
    }

    // tags are fixed words (error codes, route patterns), never ids, tokens or file names
    private void count(HttpServletRequest request, HttpStatus status, String code) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String route = pattern == null ? "none" : pattern.toString();
        if (status == HttpStatus.UNAUTHORIZED || status == HttpStatus.FORBIDDEN) {
            metrics.counter("spotme.auth.failures", "code", code).increment();
        }
        if (status == HttpStatus.TOO_MANY_REQUESTS) {
            metrics.counter("spotme.rate.limit.rejections", "route", route).increment();
        }
        // a file over the size limit is refused before the route is known, so check the path as well
        boolean upload = UPLOAD_ROUTE.equals(route) || request.getRequestURI().matches("/api/events/[^/]+/photos");
        if (upload && "POST".equals(request.getMethod())) {
            metrics.counter("spotme.uploads", "result", "rejected", "code", code).increment();
        }
    }
}
