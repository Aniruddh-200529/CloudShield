package com.cloudshield.backend.service;

public class MfaConfigurationException extends RuntimeException {
    public MfaConfigurationException() {
        super("MFA encryption is not configured correctly");
    }
}
