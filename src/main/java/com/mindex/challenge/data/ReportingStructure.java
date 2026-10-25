package com.mindex.challenge.data;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

public class ReportingStructure {
    private Employee employee;
    private int numberOfReports;

    // Report IDs that don't match any employee, so the data can be reviewed; left out of the JSON when empty
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<String> unresolvedReportIds;

    public ReportingStructure() {
    }

    public ReportingStructure(Employee employee, int numberOfReports, List<String> unresolvedReportIds) {
        this.employee = employee;
        this.numberOfReports = numberOfReports;
        this.unresolvedReportIds = unresolvedReportIds;
    }

    public Employee getEmployee() {
        return employee;
    }

    public void setEmployee(Employee employee) {
        this.employee = employee;
    }

    public int getNumberOfReports() {
        return numberOfReports;
    }

    public void setNumberOfReports(int numberOfReports) {
        this.numberOfReports = numberOfReports;
    }

    public List<String> getUnresolvedReportIds() {
        return unresolvedReportIds;
    }

    public void setUnresolvedReportIds(List<String> unresolvedReportIds) {
        this.unresolvedReportIds = unresolvedReportIds;
    }
}
