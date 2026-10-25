package com.mindex.challenge.service.impl;

import com.mindex.challenge.dao.CompensationRepository;
import com.mindex.challenge.dao.EmployeeRepository;
import com.mindex.challenge.data.Compensation;
import com.mindex.challenge.exception.EmployeeNotFoundException;
import com.mindex.challenge.exception.InvalidCompensationException;
import com.mindex.challenge.service.CompensationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class CompensationServiceImpl implements CompensationService {

    private static final Logger LOG = LoggerFactory.getLogger(CompensationServiceImpl.class);

    @Autowired
    private CompensationRepository compensationRepository;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private Clock clock;

    @Override
    public Compensation create(String employeeId, Compensation compensation) {
        // Salaries are sensitive, so they're kept out of the logs
        LOG.debug("Creating compensation effective [{}] for employee with id [{}]",
                compensation.getEffectiveDate(), employeeId);

        requireEmployee(employeeId);
        validate(compensation);

        // Copy only the fields a client may set; the server owns the rest, so anything else in the request is ignored
        Compensation entry = new Compensation();
        entry.setCompensationId(UUID.randomUUID().toString());
        entry.setEmployeeId(employeeId);
        entry.setSalary(compensation.getSalary());
        entry.setEffectiveDate(compensation.getEffectiveDate());
        // Millisecond precision, matching what the database stores, so the response equals what a later read returns
        entry.setCreatedAt(Instant.now(clock).truncatedTo(ChronoUnit.MILLIS));

        return compensationRepository.insert(entry);
    }

    @Override
    public Optional<Compensation> readCurrent(String employeeId) {
        LOG.debug("Reading current compensation for employee with id [{}]", employeeId);

        requireEmployee(employeeId);

        return compensationRepository.findCurrent(employeeId, LocalDate.now(clock));
    }

    @Override
    public List<Compensation> readHistory(String employeeId) {
        LOG.debug("Reading compensation history for employee with id [{}]", employeeId);

        requireEmployee(employeeId);

        return compensationRepository.findHistory(employeeId);
    }

    // Compensation lives in its own collection and Mongo has no foreign keys, so the employee is checked here
    private void requireEmployee(String employeeId) {
        if (!employeeRepository.existsById(employeeId)) {
            throw new EmployeeNotFoundException(employeeId);
        }
    }

    // Reports every problem at once, so a client can fix the request in one pass
    private static void validate(Compensation compensation) {
        List<String> problems = new ArrayList<>();
        BigDecimal salary = compensation.getSalary();

        if (salary == null) {
            problems.add("salary is required");
        } else if (salary.signum() <= 0) {
            problems.add("salary must be greater than 0");
        } else if (salary.stripTrailingZeros().scale() > 2) {
            problems.add("salary can have at most 2 decimal places");
        }

        if (compensation.getEffectiveDate() == null) {
            problems.add("effectiveDate is required");
        }

        if (!problems.isEmpty()) {
            throw new InvalidCompensationException(String.join("; ", problems));
        }
    }
}
