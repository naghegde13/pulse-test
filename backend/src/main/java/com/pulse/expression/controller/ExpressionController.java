package com.pulse.expression.controller;

import com.pulse.expression.service.ExpressionValidationService;
import com.pulse.expression.service.ExpressionValidationService.ValidationRequest;
import com.pulse.expression.service.ExpressionValidationService.ValidationResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/expressions")
public class ExpressionController {

    private final ExpressionValidationService validator;

    public ExpressionController(ExpressionValidationService validator) {
        this.validator = validator;
    }

    @PostMapping("/validate")
    public ResponseEntity<ValidationResult> validate(@RequestBody ValidationRequest request) {
        return ResponseEntity.ok(validator.validate(request));
    }
}
