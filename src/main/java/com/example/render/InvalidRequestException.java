package com.example.render;

/** Thrown when a request payload violates the size/complexity limits (maps to HTTP 400). */
public class InvalidRequestException extends RuntimeException {
    public InvalidRequestException(String message) {
        super(message);
    }
}
