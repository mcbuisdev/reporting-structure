package com.mindex.challenge.service.impl;

import com.mindex.challenge.dao.EmployeeRepository;
import com.mindex.challenge.data.Employee;
import com.mindex.challenge.data.ReportingStructure;
import com.mindex.challenge.exception.EmployeeNotFoundException;
import com.mindex.challenge.service.ReportingStructureService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Computes reporting structures in two phases:
 * <ol>
 *     <li>Fetch: load everyone under the employee, one database query per level of the hierarchy.</li>
 *     <li>Count: walk the fetched data recursively. Tracking the current chain of managers lets the count tell a
 *     cycle (a report pointing back up the chain) apart from a report shared by two managers.</li>
 * </ol>
 * The hierarchy is not guaranteed to be a clean tree, so cycles, shared reports, and report IDs that don't exist are
 * all handled without failing the request.
 */
@Service
public class ReportingStructureServiceImpl implements ReportingStructureService {

    private static final Logger LOG = LoggerFactory.getLogger(ReportingStructureServiceImpl.class);

    @Autowired
    private EmployeeRepository employeeRepository;

    @Override
    public ReportingStructure read(String employeeId) {
        LOG.debug("Computing reporting structure for employee with id [{}]", employeeId);

        Employee employee = employeeRepository.findByEmployeeId(employeeId);

        if (employee == null) {
            throw new EmployeeNotFoundException(employeeId);
        }

        Set<String> unresolvedReportIds = new LinkedHashSet<>();
        Map<String, List<String>> reportIdsById = fetchReportIds(employee, unresolvedReportIds);

        Set<String> counted = new HashSet<>();
        Set<String> chain = new LinkedHashSet<>();
        chain.add(employee.getEmployeeId());
        countReports(employee.getEmployeeId(), reportIdsById, chain, counted);

        return new ReportingStructure(employee, counted.size(), new ArrayList<>(unresolvedReportIds));
    }

    // Phase 1: maps everyone under the root to the IDs of their direct reports, querying one level at a time.
    // Only IDs are kept, not full records, to keep memory small.
    private Map<String, List<String>> fetchReportIds(Employee root, Set<String> unresolvedReportIds) {
        Map<String, List<String>> reportIdsById = new HashMap<>();
        reportIdsById.put(root.getEmployeeId(), validReportIds(root));

        Set<String> level = new LinkedHashSet<>(reportIdsById.get(root.getEmployeeId()));
        level.removeAll(reportIdsById.keySet());

        while (!level.isEmpty()) {
            Set<String> nextLevel = new LinkedHashSet<>();

            for (Employee report : employeeRepository.findByEmployeeIdIn(level)) {
                List<String> reportIds = validReportIds(report);
                reportIdsById.put(report.getEmployeeId(), reportIds);
                nextLevel.addAll(reportIds);
            }

            for (String reportId : level) {
                if (!reportIdsById.containsKey(reportId)) {
                    unresolvedReportIds.add(reportId);
                }
            }

            // Never fetch anyone twice, which also stops cycles from looping forever here
            nextLevel.removeAll(reportIdsById.keySet());
            nextLevel.removeAll(unresolvedReportIds);
            level = nextLevel;
        }

        return reportIdsById;
    }

    // Phase 2: adds every distinct report under employeeId to counted. chain holds the managers from the root down to
    // employeeId, so a report already in it is a cycle, while one already counted elsewhere is a shared report.
    private void countReports(String employeeId, Map<String, List<String>> reportIdsById,
                              Set<String> chain, Set<String> counted) {
        for (String reportId : reportIdsById.get(employeeId)) {
            if (chain.contains(reportId)) {
                LOG.warn("Reporting cycle: employee [{}] lists [{}] as a direct report, but [{}] is already in the " +
                        "reporting chain {}", employeeId, reportId, reportId, chain);
                continue;
            }

            if (counted.contains(reportId)) {
                LOG.debug("Employee [{}] is already counted; skipping the repeat listed by [{}]", reportId, employeeId);
                continue;
            }

            if (!reportIdsById.containsKey(reportId)) {
                LOG.warn("Employee [{}] lists direct report [{}], which does not exist", employeeId, reportId);
                continue;
            }

            counted.add(reportId);
            chain.add(reportId);
            countReports(reportId, reportIdsById, chain, counted);
            chain.remove(reportId);
        }
    }

    private static List<String> validReportIds(Employee employee) {
        if (employee.getDirectReports() == null) {
            return Collections.emptyList();
        }

        List<String> reportIds = new ArrayList<>();

        for (Employee report : employee.getDirectReports()) {
            String reportId = report == null ? null : report.getEmployeeId();

            if (reportId == null || reportId.isBlank()) {
                LOG.warn("Employee [{}] has a direct report with no employeeId; skipping it", employee.getEmployeeId());
                continue;
            }

            reportIds.add(reportId);
        }

        return reportIds;
    }
}
