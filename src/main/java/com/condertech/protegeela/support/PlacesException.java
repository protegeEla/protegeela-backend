package com.condertech.protegeela.support;

import org.springframework.http.HttpStatus;

public class PlacesException extends RuntimeException {
    private final HttpStatus status;
    public PlacesException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }
    public HttpStatus status() { return status; }
}
