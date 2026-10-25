package com.mindex.challenge.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.mindex.challenge.dao.EmployeeRepository;
import com.mindex.challenge.data.Employee;
import com.mindex.challenge.service.EmployeeService;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(SpringRunner.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class EmployeeServiceImplTest {

    private String employeeUrl;
    private String employeeIdUrl;

    @Autowired
    private EmployeeService employeeService;

    @Autowired
    private EmployeeRepository employeeRepository;

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Before
    public void setup() {
        employeeUrl = "http://localhost:" + port + "/employee";
        employeeIdUrl = "http://localhost:" + port + "/employee/{id}";
    }

    @Test
    public void testCreateReadUpdate() {
        Employee testEmployee = new Employee();
        testEmployee.setFirstName("John");
        testEmployee.setLastName("Doe");
        testEmployee.setDepartment("Engineering");
        testEmployee.setPosition("Developer");

        // Create checks
        Employee createdEmployee = restTemplate.postForEntity(employeeUrl, testEmployee, Employee.class).getBody();

        assertNotNull(createdEmployee.getEmployeeId());
        assertEmployeeEquivalence(testEmployee, createdEmployee);


        // Read checks
        Employee readEmployee = restTemplate.getForEntity(employeeIdUrl, Employee.class, createdEmployee.getEmployeeId()).getBody();
        assertEquals(createdEmployee.getEmployeeId(), readEmployee.getEmployeeId());
        assertEmployeeEquivalence(createdEmployee, readEmployee);


        // Update checks
        readEmployee.setPosition("Development Manager");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        Employee updatedEmployee =
                restTemplate.exchange(employeeIdUrl,
                        HttpMethod.PUT,
                        new HttpEntity<Employee>(readEmployee, headers),
                        Employee.class,
                        readEmployee.getEmployeeId()).getBody();

        assertEmployeeEquivalence(readEmployee, updatedEmployee);


        // Read after update checks: the update must replace the stored employee, not add a duplicate
        ResponseEntity<Employee> rereadResponse =
                restTemplate.getForEntity(employeeIdUrl, Employee.class, createdEmployee.getEmployeeId());
        assertEquals(HttpStatus.OK, rereadResponse.getStatusCode());
        assertEmployeeEquivalence(updatedEmployee, rereadResponse.getBody());
    }

    @Test
    public void testReadUnknownEmployeeReturnsNotFound() {
        ResponseEntity<String> response =
                restTemplate.getForEntity(employeeIdUrl, String.class, "does-not-exist");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    public void testUpdateUnknownEmployeeReturnsNotFound() {
        String unknownId = "unknown-employee-for-update";

        Employee testEmployee = new Employee();
        testEmployee.setFirstName("Jane");
        testEmployee.setLastName("Doe");
        testEmployee.setDepartment("Engineering");
        testEmployee.setPosition("Developer");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> response =
                restTemplate.exchange(employeeIdUrl,
                        HttpMethod.PUT,
                        new HttpEntity<Employee>(testEmployee, headers),
                        String.class,
                        unknownId);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());

        // Update must not create the employee as a side effect
        ResponseEntity<String> readResponse = restTemplate.getForEntity(employeeIdUrl, String.class, unknownId);
        assertEquals(HttpStatus.NOT_FOUND, readResponse.getStatusCode());
    }

    @Test
    public void testCreateWithUnknownDirectReportReturnsConflict() {
        long employeeCount = employeeRepository.count();

        Employee testEmployee = newEmployee("Jane");
        testEmployee.setDirectReports(stubs("does-not-exist"));

        ResponseEntity<JsonNode> response = restTemplate.postForEntity(employeeUrl, testEmployee, JsonNode.class);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertTrue(response.getBody().get("detail").asText().contains("does-not-exist"));

        // Nothing was saved
        assertEquals(employeeCount, employeeRepository.count());
    }

    @Test
    public void testCreateWithExistingDirectReports() {
        Employee report = create("Report");

        Employee manager = newEmployee("Manager");
        manager.setDirectReports(stubs(report.getEmployeeId()));

        ResponseEntity<Employee> response = restTemplate.postForEntity(employeeUrl, manager, Employee.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(report.getEmployeeId(), response.getBody().getDirectReports().get(0).getEmployeeId());
    }

    @Test
    public void testUpdateWithUnknownDirectReportReturnsConflict() {
        Employee testEmployee = create("Jane");
        testEmployee.setDirectReports(stubs("does-not-exist"));

        ResponseEntity<JsonNode> response = update(testEmployee);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertTrue(response.getBody().get("detail").asText().contains("does-not-exist"));

        // The stored employee is unchanged
        assertNull(read(testEmployee.getEmployeeId()).getDirectReports());
    }

    @Test
    public void testUpdateWithSelfAsDirectReportReturnsConflict() {
        Employee testEmployee = create("Jane");
        testEmployee.setDirectReports(stubs(testEmployee.getEmployeeId()));

        ResponseEntity<JsonNode> response = update(testEmployee);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertNull(read(testEmployee.getEmployeeId()).getDirectReports());
    }

    @Test
    public void testUpdateThatCreatesCycleReturnsConflict() {
        Employee a = create("A");
        Employee b = create("B");
        Employee c = create("C");

        a.setDirectReports(stubs(b.getEmployeeId()));
        assertEquals(HttpStatus.OK, update(a).getStatusCode());
        b.setDirectReports(stubs(c.getEmployeeId()));
        assertEquals(HttpStatus.OK, update(b).getStatusCode());

        // C listing A would close the loop A -> B -> C -> A
        c.setDirectReports(stubs(a.getEmployeeId()));
        ResponseEntity<JsonNode> response = update(c);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertTrue(response.getBody().get("detail").asText().contains("cycle"));
        assertNull(read(c.getEmployeeId()).getDirectReports());
    }

    @Test
    public void testUpdateWithSharedDirectReportIsAccepted() {
        Employee a = create("A");
        Employee b = create("B");
        Employee shared = create("Shared");

        // Two managers sharing a report is allowed, because it isn't a cycle
        a.setDirectReports(stubs(shared.getEmployeeId()));
        assertEquals(HttpStatus.OK, update(a).getStatusCode());
        b.setDirectReports(stubs(shared.getEmployeeId()));
        assertEquals(HttpStatus.OK, update(b).getStatusCode());
    }

    @Test
    public void testCreateWithDirectReportMissingIdReturnsBadRequest() {
        long employeeCount = employeeRepository.count();

        Employee testEmployee = newEmployee("Jane");
        testEmployee.setDirectReports(List.of(new Employee()));

        ResponseEntity<JsonNode> response = restTemplate.postForEntity(employeeUrl, testEmployee, JsonNode.class);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertTrue(response.getBody().get("detail").asText().contains("directReports[0]"));

        // Nothing was saved
        assertEquals(employeeCount, employeeRepository.count());
    }

    @Test
    public void testUpdateWithBlankDirectReportIdReturnsBadRequest() {
        Employee report = create("Report");
        Employee testEmployee = create("Jane");
        testEmployee.setDirectReports(stubs(report.getEmployeeId(), "  "));

        ResponseEntity<JsonNode> response = update(testEmployee);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertTrue(response.getBody().get("detail").asText().contains("directReports[1]"));
        assertNull(read(testEmployee.getEmployeeId()).getDirectReports());
    }

    @Test
    public void testUpdateWithNullDirectReportReturnsBadRequest() {
        Employee testEmployee = create("Jane");
        testEmployee.setDirectReports(Arrays.asList((Employee) null));

        ResponseEntity<JsonNode> response = update(testEmployee);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNull(read(testEmployee.getEmployeeId()).getDirectReports());
    }

    private Employee create(String firstName) {
        ResponseEntity<Employee> response = restTemplate.postForEntity(employeeUrl, newEmployee(firstName), Employee.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        return response.getBody();
    }

    private Employee read(String employeeId) {
        return restTemplate.getForEntity(employeeIdUrl, Employee.class, employeeId).getBody();
    }

    private ResponseEntity<JsonNode> update(Employee employee) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        return restTemplate.exchange(employeeIdUrl,
                HttpMethod.PUT,
                new HttpEntity<Employee>(employee, headers),
                JsonNode.class,
                employee.getEmployeeId());
    }

    private static Employee newEmployee(String firstName) {
        Employee employee = new Employee();
        employee.setFirstName(firstName);
        employee.setLastName("Doe");
        employee.setDepartment("Engineering");
        employee.setPosition("Developer");
        return employee;
    }

    private static List<Employee> stubs(String... employeeIds) {
        return Arrays.stream(employeeIds).map(employeeId -> {
            Employee stub = new Employee();
            stub.setEmployeeId(employeeId);
            return stub;
        }).collect(Collectors.toList());
    }

    private static void assertEmployeeEquivalence(Employee expected, Employee actual) {
        assertEquals(expected.getFirstName(), actual.getFirstName());
        assertEquals(expected.getLastName(), actual.getLastName());
        assertEquals(expected.getDepartment(), actual.getDepartment());
        assertEquals(expected.getPosition(), actual.getPosition());
    }
}
