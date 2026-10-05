package com.cloudshield.backend;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/probe")
public class ProbeController {

    @GetMapping("/heartbeat")
    public String heartbeat() {
        return "CloudShield probe connection successful";
    }
}