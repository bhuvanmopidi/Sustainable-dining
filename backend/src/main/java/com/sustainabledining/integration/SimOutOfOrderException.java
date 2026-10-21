package com.sustainabledining.integration;

public class SimOutOfOrderException extends RuntimeException {
    public SimOutOfOrderException(String message) {
        super(message);
    }
}