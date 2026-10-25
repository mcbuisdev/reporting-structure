package com.mindex.challenge.exception;

/**
 * Thrown when a direct report in a request has no employeeId, so it can't refer to anyone. The request itself is
 * malformed, so it maps to 400 Bad Request (unlike {@link InvalidDirectReportsException}, which is a 409 for
 * well-formed reports that break the hierarchy rules).
 */
public class MalformedDirectReportsException extends RuntimeException {

    public MalformedDirectReportsException(String message) {
        super(message);
    }
}
