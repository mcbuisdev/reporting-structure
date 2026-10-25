package com.mindex.challenge.service.impl;

import com.mindex.challenge.dao.EmployeeRepository;
import com.mindex.challenge.data.Employee;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Loads the part of the reporting hierarchy below an employee. Shared by the reporting structure count and by the
 * direct report validation on employee updates.
 */
final class ReportingHierarchy {

    private static final Logger LOG = LoggerFactory.getLogger(ReportingHierarchy.class);

    private ReportingHierarchy() {
    }

    /**
     * Maps everyone under the root to the IDs of their direct reports, querying one level at a time. The root's own
     * reports are taken from the given object, not the database, so callers can check a change before saving it.
     * Only IDs are kept, not full records, to keep memory small. Report IDs that don't match any employee are added
     * to unresolvedReportIds.
     */
    static Map<String, List<String>> fetchReportIds(EmployeeRepository employeeRepository, Employee root,
                                                    Set<String> unresolvedReportIds) {
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

    static List<String> validReportIds(Employee employee) {
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
