package com.mindex.challenge.controller;

import com.mindex.challenge.exception.EmployeeNotFoundException;
import com.mindex.challenge.exception.InvalidDirectReportsException;
import com.mindex.challenge.exception.MalformedDirectReportsException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns the API's own exceptions into RFC 9457 problem detail responses. Spring Boot hides exception messages in error
 * responses by default, but these messages describe what the client got wrong (an unknown ID, a broken hierarchy
 * rule), so returning them is safe and tells the client how to fix the request.
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
}
