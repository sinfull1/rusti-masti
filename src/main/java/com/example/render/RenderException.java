package com.example.render;

/** Thrown when a render fails (page load error, timeout, or snapshot/encoding failure). */
public class RenderException extends RuntimeException {
    public RenderException(String message) { super(message); }
    public RenderException(String message, Throwable cause) { super(message, cause); }
}