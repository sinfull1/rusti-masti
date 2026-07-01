package com.example.render;

/** Thrown when the service is at capacity and sheds the request (maps to HTTP 503). */
public class RejectedRenderException extends RuntimeException {
    public RejectedRenderException(String message) { super(message); }
}