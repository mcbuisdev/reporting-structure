package com.mindex.challenge.controller;

import com.mindex.challenge.data.Compensation;
import com.mindex.challenge.service.CompensationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class CompensationController {
    private static final Logger LOG = LoggerFactory.getLogger(CompensationController.class);

    @Autowired
    private CompensationService compensationService;

    @PostMapping("/compensation/{employeeId}")
    public Compensation create(@PathVariable String employeeId, @RequestBody Compensation compensation) {
        LOG.debug("Received compensation create request for employee id [{}]", employeeId);

        return compensationService.create(employeeId, compensation);
    }

    // 200 with the compensation in effect today, or 204 No Content if the employee has none in effect yet
    @GetMapping("/compensation/{employeeId}")
    public ResponseEntity<Compensation> read(@PathVariable String employeeId) {
        LOG.debug("Received compensation read request for employee id [{}]", employeeId);

        return compensationService.readCurrent(employeeId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    // Every entry ever created, including future ones, so anything posted can be read back; [] if there are none
    @GetMapping("/compensation/{employeeId}/history")
    public List<Compensation> readHistory(@PathVariable String employeeId) {
        LOG.debug("Received compensation history request for employee id [{}]", employeeId);

        return compensationService.readHistory(employeeId);
    }
}
