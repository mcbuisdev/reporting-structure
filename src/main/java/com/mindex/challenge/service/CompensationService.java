package com.mindex.challenge.service;

import com.mindex.challenge.data.Compensation;

import java.util.List;
import java.util.Optional;

public interface CompensationService {
    Compensation create(String employeeId, Compensation compensation);

    // Empty when the employee exists but has no compensation in effect yet
    Optional<Compensation> readCurrent(String employeeId);

    // Every entry, including future ones, newest effectiveDate first; empty when the employee has none
    List<Compensation> readHistory(String employeeId);
}
