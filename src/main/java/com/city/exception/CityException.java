package com.city.exception;

public class CityException extends RuntimeException {
    public CityException(String message) {
        super(message);
    }

    public CityException(String message, Throwable cause) {
        super(message, cause);
    }
}




