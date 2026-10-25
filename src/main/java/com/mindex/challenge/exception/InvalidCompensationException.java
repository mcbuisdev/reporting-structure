package com.mindex.challenge.exception;

/**
 * Thrown when a compensation entry is missing a required field or has an invalid value. Maps to 400 Bad Request.
 */
public class InvalidCompensationException extends RuntimeException {

    public InvalidCompensationException(String message) {
        super(message);
    }
}
