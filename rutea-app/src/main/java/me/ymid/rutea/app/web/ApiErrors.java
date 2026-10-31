package me.ymid.rutea.app.web;

import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import tools.jackson.core.JacksonException;

/**
 * Todo error de la API sale igual: RFC 9457 ({@code application/problem+json}), con {@code detail} en español y
 * diciendo qué se esperaba. También los que arma Spring: JSON mal formado, ruta o método que no existen.
 */
@RestControllerAdvice
class ApiErrors extends ResponseEntityExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail invalid(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(BogotaData.DataMissingException.class)
    ProblemDetail missing(BogotaData.DataMissingException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception e, @Nullable Object body, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        // Spring pasa el cuerpo en null cuando la excepción ya trae el suyo.
        ProblemDetail problem = body instanceof ProblemDetail given ? given
                : e instanceof ErrorResponse response ? response.getBody() : null;
        if (problem != null) {
            String path = request instanceof ServletWebRequest servlet ? servlet.getRequest().getRequestURI() : "";
            String detail = switch (e) {
                case HttpMessageNotReadableException unreadable -> unreadable(unreadable);
                case HttpMediaTypeNotSupportedException media ->
                        "el cuerpo va en JSON, con Content-Type: application/json; llegó " + media.getContentType();
                case HttpRequestMethodNotSupportedException method -> method.getSupportedMethods() == null
                        ? path + " no admite " + method.getMethod()
                        : path + " no admite " + method.getMethod() + "; admite "
                                + String.join(", ", method.getSupportedMethods());
                case NoResourceFoundException ignored -> "no hay nada en " + path;
                case MissingServletRequestParameterException parameter -> "falta el parámetro "
                        + parameter.getParameterName();
                case TypeMismatchException mismatch -> mismatch.getPropertyName() + " no admite el valor "
                        + mismatch.getValue();
                default -> problem.getDetail();
            };
            problem.setDetail(detail);
        }
        return super.handleExceptionInternal(e, problem != null ? problem : body, headers, status, request);
    }

    private static String unreadable(HttpMessageNotReadableException e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof JacksonException json && !json.getPath().isEmpty()) {
                String field = json.getPath().stream()
                        .map(reference -> reference.getPropertyName() != null ? reference.getPropertyName()
                                : "[" + reference.getIndex() + "]")
                        .collect(Collectors.joining("."));
                return "falta el campo " + field + " o no tiene el tipo esperado";
            }
        }
        return e.getMessage() != null && e.getMessage().startsWith("Required request body is missing")
                ? "falta el cuerpo de la petición, en JSON"
                : "el cuerpo no es JSON válido";
    }
}
