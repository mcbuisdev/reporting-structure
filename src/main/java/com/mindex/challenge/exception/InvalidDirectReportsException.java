package com.mindex.challenge.exception;

/**
 * Thrown when an employee's direct reports break the hierarchy rules: they must exist, and must not create a cycle.
 * The request is well formed but conflicts with the data already stored, so it maps to 409 Conflict.
 */
public class InvalidDirectReportsException extends RuntimeException {

    public InvalidDirectReportsException(String message) {
        super(message);
    }
}
