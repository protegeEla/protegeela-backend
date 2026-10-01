package com.condertech.protegeela.shared.exception;

import com.condertech.protegeela.shared.response.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {
    @ExceptionHandler(com.condertech.protegeela.auth.AuthRateLimitException.class)
    public ResponseEntity<ApiError> rateLimited(com.condertech.protegeela.auth.AuthRateLimitException exception,
                                               HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "900")
                .body(new ApiError(Instant.now(), 429, "Too Many Requests", exception.getMessage(), request.getRequestURI()));
    }
    @ExceptionHandler(com.condertech.protegeela.support.PlacesException.class)
    public ResponseEntity<ApiError> places(com.condertech.protegeela.support.PlacesException exception,
                                          HttpServletRequest request) {
        return error(exception.status(), exception.getMessage(), request);
    }
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiError> notFound(ResourceNotFoundException exception, HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, exception.getMessage(), request);
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiError> business(BusinessException exception, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, exception.getMessage(), request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> invalidInput(Exception exception, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "Dados de entrada inválidos.", request);
    }

    @ExceptionHandler({ResourceConflictException.class, DataIntegrityViolationException.class})
    public ResponseEntity<ApiError> conflict(Exception exception, HttpServletRequest request) {
        String message = exception instanceof ResourceConflictException
                ? exception.getMessage() : "Operação viola a integridade dos dados.";
        return error(HttpStatus.CONFLICT, message, request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> forbidden(AccessDeniedException exception, HttpServletRequest request) {
        return error(HttpStatus.FORBIDDEN, "Acesso negado.", request);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiError> unauthenticated(AuthenticationException exception, HttpServletRequest request) {
        return error(HttpStatus.UNAUTHORIZED, "Autenticação necessária.", request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected(Exception exception, HttpServletRequest request) {
        // Exception messages and request contents may contain credentials or personal data.
        log.error("Falha não tratada no backend. Tipo: {}", exception.getClass().getName());
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "Erro interno do servidor.", request);
    }

    @Override
    protected ResponseEntity<Object> createResponseEntity(Object body, HttpHeaders headers,
                                                          HttpStatusCode statusCode, WebRequest request) {
        HttpStatus status = HttpStatus.resolve(statusCode.value());
        String reason = status == null ? "HTTP Error" : status.getReasonPhrase();
        String message = statusCode.is5xxServerError() ? "Erro interno do servidor." : switch (statusCode.value()) {
            case 400 -> "Dados de entrada inválidos.";
            case 404 -> "Recurso não encontrado.";
            case 405 -> "Método HTTP não permitido.";
            case 415 -> "Tipo de conteúdo não suportado.";
            default -> "Não foi possível processar a requisição.";
        };
        String path = ((ServletWebRequest) request).getRequest().getRequestURI();
        ApiError error = new ApiError(Instant.now(), statusCode.value(), reason, message, path);
        // Preserve Spring's status and protocol headers (e.g. Allow for HTTP 405).
        return new ResponseEntity<>(error, headers, statusCode);
    }

    private ResponseEntity<ApiError> error(HttpStatus status, String message, HttpServletRequest request) {
        return ResponseEntity.status(status).body(new ApiError(Instant.now(), status.value(),
                status.getReasonPhrase(), message, request.getRequestURI()));
    }
}
