package com.mindex.challenge.service.impl;

import com.mindex.challenge.dao.EmployeeRepository;
import com.mindex.challenge.data.Employee;
import com.mindex.challenge.exception.EmployeeNotFoundException;
import com.mindex.challenge.exception.InvalidDirectReportsException;
import com.mindex.challenge.exception.MalformedDirectReportsException;
import com.mindex.challenge.service.EmployeeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class EmployeeServiceImpl implements EmployeeService {

    private static final Logger LOG = LoggerFactory.getLogger(EmployeeServiceImpl.class);

    @Autowired
    private EmployeeRepository employeeRepository;

    @Override
    public Employee create(Employee employee) {
        LOG.debug("Creating employee [{}]", employee);

        employee.setEmployeeId(UUID.randomUUID().toString());
        validateNewDirectReports(employee);
        employeeRepository.insert(employee);

        return employee;
    }

    @Override
    public Employee read(String id) {
        LOG.debug("Reading employee with id [{}]", id);

        Employee employee = employeeRepository.findByEmployeeId(id);

        if (employee == null) {
            throw new EmployeeNotFoundException(id);
        }

        return employee;
    }

    @Override
    public Employee update(Employee employee) {
        LOG.debug("Updating employee [{}]", employee);

        // Updates only apply to existing employees; new employees go through create
        if (!employeeRepository.existsById(employee.getEmployeeId())) {
            throw new EmployeeNotFoundException(employee.getEmployeeId());
        }

        validateUpdatedDirectReports(employee);

        return employeeRepository.save(employee);
    }

    // Direct reports are validated on every write to make the hierarchy more robust. ReportingStructureServiceImpl
    // tolerates bad data (it skips and logs reports that don't exist and cycles), but that only flags a problem after
    // it's stored, and the counts stay wrong until someone fixes the data. Rejecting bad reports here keeps them out
    // at the source. The read side still guards against bad data that arrives some other way, such as the snapshot.

    // A new employee can't be part of a cycle, because nobody can list them as a report yet,
    // so their direct reports only need to exist
    private void validateNewDirectReports(Employee employee) {
        rejectMalformedReports(employee);
        List<String> reportIds = ReportingHierarchy.validReportIds(employee);

        if (reportIds.isEmpty()) {
            return;
        }

        List<String> existingIds = employeeRepository.findByEmployeeIdIn(reportIds).stream()
                .map(Employee::getEmployeeId)
                .collect(Collectors.toList());

        rejectUnknownReports(reportIds, existingIds);
    }

    private void validateUpdatedDirectReports(Employee employee) {
        rejectMalformedReports(employee);
        String employeeId = employee.getEmployeeId();
        List<String> reportIds = ReportingHierarchy.validReportIds(employee);

        if (reportIds.contains(employeeId)) {
            throw new InvalidDirectReportsException("Employee [" + employeeId + "] cannot be their own direct report");
        }

        // Everyone reachable through the direct reports in this update (not the ones currently stored)
        Map<String, List<String>> reportIdsById =
                ReportingHierarchy.fetchReportIds(employeeRepository, employee, new LinkedHashSet<>());

        rejectUnknownReports(reportIds, reportIdsById.keySet());

        // If anyone below the new reports already lists this employee as a report, saving would create a cycle
        for (Map.Entry<String, List<String>> entry : reportIdsById.entrySet()) {
            if (!entry.getKey().equals(employeeId) && entry.getValue().contains(employeeId)) {
                throw new InvalidDirectReportsException("These direct reports would create a reporting cycle: " +
                        "employee [" + entry.getKey() + "] would be below [" + employeeId + "] but already lists " +
                        "them as a direct report");
            }
        }
    }

    // A direct report without an employeeId can't refer to anyone, so the request itself is malformed (400). This runs
    // before the hierarchy rules (409), which only make sense for reports that name someone.
    private static void rejectMalformedReports(Employee employee) {
        List<Employee> directReports = employee.getDirectReports();

        if (directReports == null) {
            return;
        }

        for (int i = 0; i < directReports.size(); i++) {
            Employee report = directReports.get(i);

            if (report == null || report.getEmployeeId() == null || report.getEmployeeId().isBlank()) {
                throw new MalformedDirectReportsException("directReports[" + i + "] has no employeeId");
            }
        }
    }

    private static void rejectUnknownReports(List<String> reportIds, Collection<String> existingIds) {
        List<String> unknownIds = reportIds.stream()
                .filter(reportId -> !existingIds.contains(reportId))
                .distinct()
                .collect(Collectors.toList());

        if (!unknownIds.isEmpty()) {
            throw new InvalidDirectReportsException("Direct reports do not exist: " + unknownIds);
        }
    }
}
