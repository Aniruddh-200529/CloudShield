package com.cloudshield.backend.service;

public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) { super(message); }
}
