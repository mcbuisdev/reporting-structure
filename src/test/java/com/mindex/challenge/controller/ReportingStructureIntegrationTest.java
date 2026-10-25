package com.mindex.challenge.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.mindex.challenge.dao.EmployeeRepository;
import com.mindex.challenge.data.Employee;
import com.mindex.challenge.data.ReportingStructure;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

@RunWith(SpringRunner.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class ReportingStructureIntegrationTest {

    private static final String JOHN_ID = "16a596ae-edd3-4847-99fe-c4518e82c86f";
    private static final String PAUL_ID = "b7839309-3348-463b-a7e3-5de1c168beb3";
    private static final String RINGO_ID = "03aa1462-ffa9-4978-901b-7c001562cf6f";

    private String reportingStructureUrl;
    private String employeeUrl;
    private String employeeIdUrl;

    @Autowired
    private EmployeeRepository employeeRepository;

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Before
    public void setup() {
        reportingStructureUrl = "http://localhost:" + port + "/reportingStructure/{employeeId}";
        employeeUrl = "http://localhost:" + port + "/employee";
        employeeIdUrl = "http://localhost:" + port + "/employee/{id}";
    }

    // These tests share the in-memory database with other test classes, so they only read the seed employees and
    // create their own employees for anything that changes data.

    @Test
    public void testReadmeExample() {
        ResponseEntity<JsonNode> response = restTemplate.getForEntity(reportingStructureUrl, JsonNode.class, JOHN_ID);

        assertEquals(HttpStatus.OK, response.getStatusCode());

        JsonNode body = response.getBody();
        assertEquals(4, body.get("numberOfReports").asInt());
        assertEquals(JOHN_ID, body.get("employee").get("employeeId").asText());
        assertEquals("John", body.get("employee").get("firstName").asText());
        assertEquals("Lennon", body.get("employee").get("lastName").asText());

        // Nothing is unresolved, so the response has exactly the two fields the README specifies
        assertFalse(body.has("unresolvedReportIds"));
    }

    @Test
    public void testOtherSeedEmployees() {
        assertEquals(2, read(RINGO_ID).getNumberOfReports());
        assertEquals(0, read(PAUL_ID).getNumberOfReports());
    }

    @Test
    public void testUnknownEmployeeReturnsNotFound() {
        ResponseEntity<String> response =
                restTemplate.getForEntity(reportingStructureUrl, String.class, "does-not-exist");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    public void testReflectsUpdatesImmediately() {
        Employee manager = create("Manager");
        Employee report = create("Report");
        assertEquals(0, read(manager.getEmployeeId()).getNumberOfReports());

        manager.setDirectReports(List.of(stub(report.getEmployeeId())));
        restTemplate.put(employeeIdUrl, manager, manager.getEmployeeId());

        // Computed on every request, so the update shows up right away
        assertEquals(1, read(manager.getEmployeeId()).getNumberOfReports());
    }

    @Test
    public void testUnknownReportIdIsListed() {
        // Inserted straight into the database, the way bad data could arrive from outside the API
        Employee employee = new Employee();
        employee.setEmployeeId(UUID.randomUUID().toString());
        employee.setFirstName("Bad");
        employee.setLastName("Data");
        employee.setDirectReports(List.of(stub("made-up-id")));
        employeeRepository.insert(employee);

        ReportingStructure reportingStructure = read(employee.getEmployeeId());

        assertEquals(0, reportingStructure.getNumberOfReports());
        assertEquals(List.of("made-up-id"), reportingStructure.getUnresolvedReportIds());
    }

    private ReportingStructure read(String employeeId) {
        ResponseEntity<ReportingStructure> response =
                restTemplate.getForEntity(reportingStructureUrl, ReportingStructure.class, employeeId);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        return response.getBody();
    }

    private Employee create(String firstName) {
        Employee employee = new Employee();
        employee.setFirstName(firstName);
        employee.setLastName("Test");
        employee.setDepartment("Engineering");
        employee.setPosition("Developer");

        return restTemplate.postForEntity(employeeUrl, employee, Employee.class).getBody();
    }

    private static Employee stub(String employeeId) {
        Employee employee = new Employee();
        employee.setEmployeeId(employeeId);
        return employee;
    }
}
