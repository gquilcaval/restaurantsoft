package com.restos.demo;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Acceso público a la demostración: sin contraseña, con datos ficticios que se regeneran solos. */
@RestController
public class DemoController {
    private final DemoService demo;

    public DemoController(DemoService demo) {
        this.demo = demo;
    }

    @GetMapping("/api/public/demo")
    Map<String, Boolean> status() { return Map.of("enabled", demo.enabled()); }

    @PostMapping("/api/public/demo/start")
    DemoService.DemoAccess start(@RequestBody(required = false) StartRequest request) {
        return demo.start(request == null ? null : request.role());
    }

    record StartRequest(String role) {}
}
