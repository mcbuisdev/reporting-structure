package com.mindex.challenge.controller;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.mindex.challenge.exception.EmployeeNotFoundException;
import com.mindex.challenge.exception.InvalidCompensationException;
import com.mindex.challenge.exception.InvalidDirectReportsException;
import com.mindex.challenge.exception.MalformedDirectReportsException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns the API's own exceptions into RFC 9457 problem detail responses. Spring Boot hides exception messages in error
 * responses by default, but these messages describe what the client got wrong (an unknown ID, a broken hierarchy
 * rule, an invalid field), so returning them is safe and tells the client how to fix the request.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(EmployeeNotFoundException.class)
    public ProblemDetail handleEmployeeNotFound(EmployeeNotFoundException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    @ExceptionHandler(MalformedDirectReportsException.class)
    public ProblemDetail handleMalformedDirectReports(MalformedDirectReportsException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    @ExceptionHandler(InvalidDirectReportsException.class)
    public ProblemDetail handleInvalidDirectReports(InvalidDirectReportsException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
    }

    @ExceptionHandler(InvalidCompensationException.class)
    public ProblemDetail handleInvalidCompensation(InvalidCompensationException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    // A body that can't be parsed, such as a salary of "abc" or a date of "2026-13-40", is rejected before it reaches
    // the services. Jackson's own message names internal Java classes, so only the offending field is passed on.
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleUnreadableBody(HttpMessageNotReadableException exception) {
        String detail = "Request body is missing or isn't valid JSON";

        if (exception.getCause() instanceof MismatchedInputException mismatch && !mismatch.getPath().isEmpty()) {
            detail = "Invalid value for " + fieldPath(mismatch);
        }

        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
    }

    // For example "salary", or "directReports[0].employeeId"
    private static String fieldPath(MismatchedInputException mismatch) {
        StringBuilder path = new StringBuilder();

        for (JsonMappingException.Reference reference : mismatch.getPath()) {
            if (reference.getFieldName() != null) {
                path.append(path.length() > 0 ? "." : "").append(reference.getFieldName());
            } else {
                path.append('[').append(reference.getIndex()).append(']');
            }
        }

        return path.toString();
    }
}
