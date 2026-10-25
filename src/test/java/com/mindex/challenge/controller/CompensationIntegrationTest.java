package com.mindex.challenge.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.mindex.challenge.data.Compensation;
import com.mindex.challenge.data.Employee;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.junit4.SpringRunner;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(SpringRunner.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class CompensationIntegrationTest {

    private String compensationUrl;
    private String historyUrl;
    private String employeeUrl;
    private String employeeIdUrl;

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Before
    public void setup() {
        compensationUrl = "http://localhost:" + port + "/compensation/{employeeId}";
        historyUrl = "http://localhost:" + port + "/compensation/{employeeId}/history";
        employeeUrl = "http://localhost:" + port + "/employee";
        employeeIdUrl = "http://localhost:" + port + "/employee/{id}";
    }

    // Each test creates its own employee, since all test classes share one in-memory database. The app runs on the
    // real clock, so dates are relative to today.

    @Test
    public void testCreateThenRead() {
        String employeeId = createEmployee();
        LocalDate effectiveDate = LocalDate.now().minusDays(30);

        ResponseEntity<Compensation> createResponse = post(employeeId, "85000.50", effectiveDate);
        assertEquals(HttpStatus.OK, createResponse.getStatusCode());
        Compensation created = createResponse.getBody();
        assertNotNull(created.getCompensationId());
        assertNotNull(created.getCreatedAt());

        Compensation read = read(employeeId);
        assertEquals(created.getCompensationId(), read.getCompensationId());
        assertEquals(employeeId, read.getEmployeeId());
        assertEquals(new BigDecimal("85000.50"), read.getSalary());
        assertEquals(effectiveDate, read.getEffectiveDate());
        assertEquals(created.getCreatedAt(), read.getCreatedAt());

        // The JSON keeps the salary's cents and writes dates as plain ISO strings
        String json = restTemplate.getForObject(compensationUrl, String.class, employeeId);
        assertTrue(json, json.contains("\"salary\":85000.50"));
        assertTrue(json, json.contains("\"effectiveDate\":\"" + effectiveDate + "\""));
    }

    @Test
    public void testReadReturnsEntryInEffectToday() {
        String employeeId = createEmployee();
        LocalDate today = LocalDate.now();

        // Posted out of order on purpose
        post(employeeId, "90000", today.plusDays(30));
        post(employeeId, "70000", today.minusDays(365));
        post(employeeId, "80000", today.minusDays(30));

        assertEquals(new BigDecimal("80000"), read(employeeId).getSalary());
    }

    @Test
    public void testEntryEffectiveTodayIsCurrent() {
        String employeeId = createEmployee();
        post(employeeId, "80000", LocalDate.now().minusDays(30));
        post(employeeId, "85000", LocalDate.now());

        assertEquals(new BigDecimal("85000"), read(employeeId).getSalary());
    }

    @Test
    public void testSameDayCorrectionWins() {
        String employeeId = createEmployee();
        LocalDate effectiveDate = LocalDate.now().minusDays(10);

        Compensation first = post(employeeId, "80000", effectiveDate).getBody();
        // Make sure the second entry gets a later createdAt, even on a very fast machine
        while (!Instant.now().truncatedTo(ChronoUnit.MILLIS).isAfter(first.getCreatedAt())) {
            Thread.onSpinWait();
        }
        Compensation correction = post(employeeId, "81000", effectiveDate).getBody();

        Compensation current = read(employeeId);
        assertEquals(correction.getCompensationId(), current.getCompensationId());
        assertEquals(new BigDecimal("81000"), current.getSalary());
    }

    @Test
    public void testNoCompensationReturnsNoContent() {
        String employeeId = createEmployee();

        ResponseEntity<String> response = restTemplate.getForEntity(compensationUrl, String.class, employeeId);

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        assertNull(response.getBody());
    }

    @Test
    public void testOnlyFutureCompensationReturnsNoContent() {
        String employeeId = createEmployee();
        post(employeeId, "90000", LocalDate.now().plusDays(30));

        ResponseEntity<String> response = restTemplate.getForEntity(compensationUrl, String.class, employeeId);

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
    }

    @Test
    public void testUnknownEmployeeReturnsNotFound() {
        ResponseEntity<JsonNode> postResponse =
                postJson("does-not-exist", "{\"salary\": 85000, \"effectiveDate\": \"2026-01-01\"}");
        assertEquals(HttpStatus.NOT_FOUND, postResponse.getStatusCode());

        ResponseEntity<JsonNode> getResponse = restTemplate.getForEntity(compensationUrl, JsonNode.class, "does-not-exist");
        assertEquals(HttpStatus.NOT_FOUND, getResponse.getStatusCode());
        assertTrue(getResponse.getBody().get("detail").asText().contains("does-not-exist"));
    }

    @Test
    public void testInvalidValuesReturnBadRequest() {
        String employeeId = createEmployee();

        assertBadRequest(employeeId, "{\"salary\": -5, \"effectiveDate\": \"2026-01-01\"}",
                "salary must be greater than 0");
        assertBadRequest(employeeId, "{\"salary\": 85000}", "effectiveDate is required");
        assertBadRequest(employeeId, "{\"salary\": 85000, \"effectiveDate\": \"2026-13-40\"}",
                "Invalid value for effectiveDate");
        assertBadRequest(employeeId, "{\"salary\": \"abc\", \"effectiveDate\": \"2026-01-01\"}",
                "Invalid value for salary");

        // None of them were saved
        assertEquals(HttpStatus.NO_CONTENT,
                restTemplate.getForEntity(compensationUrl, String.class, employeeId).getStatusCode());
    }

    @Test
    public void testServerOwnedFieldsAreIgnored() {
        String employeeId = createEmployee();
        String json = "{\"compensationId\": \"client-chosen-id\", \"employeeId\": \"someone-else\", " +
                "\"createdAt\": \"2000-01-01T00:00:00Z\", \"salary\": 85000, \"effectiveDate\": \"2026-01-01\"}";

        JsonNode created = postJson(employeeId, json).getBody();

        assertNotEquals("client-chosen-id", created.get("compensationId").asText());
        assertEquals(employeeId, created.get("employeeId").asText());
        assertTrue(Instant.parse(created.get("createdAt").asText()).isAfter(Instant.parse("2020-01-01T00:00:00Z")));
    }

    @Test
    public void testEmployeeUpdateLeavesCompensationAlone() {
        String employeeId = createEmployee();
        Compensation compensation = post(employeeId, "85000", LocalDate.now().minusDays(1)).getBody();

        // Replacing the whole employee document must not touch compensation, which is stored separately
        Employee employee = restTemplate.getForObject(employeeIdUrl, Employee.class, employeeId);
        employee.setPosition("Senior Developer");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Employee> updateResponse = restTemplate.exchange(employeeIdUrl, HttpMethod.PUT,
                new HttpEntity<>(employee, headers), Employee.class, employeeId);
        assertEquals(HttpStatus.OK, updateResponse.getStatusCode());

        assertEquals(compensation.getCompensationId(), read(employeeId).getCompensationId());
    }

    @Test
    public void testHistoryReturnsEveryEntryNewestFirst() {
        String employeeId = createEmployee();
        LocalDate today = LocalDate.now();

        // Posted out of order on purpose, including a same-day correction
        Compensation future = post(employeeId, "90000", today.plusDays(30)).getBody();
        Compensation old = post(employeeId, "70000", today.minusDays(365)).getBody();
        Compensation recent = post(employeeId, "80000", today.minusDays(30)).getBody();
        while (!Instant.now().truncatedTo(ChronoUnit.MILLIS).isAfter(recent.getCreatedAt())) {
            Thread.onSpinWait();
        }
        Compensation correction = post(employeeId, "81000", today.minusDays(30)).getBody();

        Compensation[] history = readHistory(employeeId);

        assertEquals(4, history.length);
        assertEquals(future.getCompensationId(), history[0].getCompensationId());
        assertEquals(correction.getCompensationId(), history[1].getCompensationId());
        assertEquals(recent.getCompensationId(), history[2].getCompensationId());
        assertEquals(old.getCompensationId(), history[3].getCompensationId());
    }

    @Test
    public void testHistoryIncludesFutureEntryThatIsNotCurrentYet() {
        String employeeId = createEmployee();
        Compensation future = post(employeeId, "90000", LocalDate.now().plusDays(30)).getBody();

        // Not in effect yet, but everything that was created can still be read back
        assertEquals(HttpStatus.NO_CONTENT,
                restTemplate.getForEntity(compensationUrl, String.class, employeeId).getStatusCode());

        Compensation[] history = readHistory(employeeId);
        assertEquals(1, history.length);
        assertEquals(future.getCompensationId(), history[0].getCompensationId());
        assertEquals(new BigDecimal("90000"), history[0].getSalary());
    }

    @Test
    public void testHistoryForEmployeeWithNoCompensationIsEmpty() {
        String employeeId = createEmployee();

        ResponseEntity<String> response = restTemplate.getForEntity(historyUrl, String.class, employeeId);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("[]", response.getBody());
    }

    @Test
    public void testHistoryForUnknownEmployeeReturnsNotFound() {
        ResponseEntity<JsonNode> response = restTemplate.getForEntity(historyUrl, JsonNode.class, "does-not-exist");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertTrue(response.getBody().get("detail").asText().contains("does-not-exist"));
    }

    private Compensation[] readHistory(String employeeId) {
        ResponseEntity<Compensation[]> response =
                restTemplate.getForEntity(historyUrl, Compensation[].class, employeeId);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        return response.getBody();
    }

    private String createEmployee() {
        Employee employee = new Employee();
        employee.setFirstName("Comp");
        employee.setLastName("Test");
        employee.setDepartment("Engineering");
        employee.setPosition("Developer");

        return restTemplate.postForEntity(employeeUrl, employee, Employee.class).getBody().getEmployeeId();
    }

    private ResponseEntity<Compensation> post(String employeeId, String salary, LocalDate effectiveDate) {
        Compensation compensation = new Compensation();
        compensation.setSalary(new BigDecimal(salary));
        compensation.setEffectiveDate(effectiveDate);

        ResponseEntity<Compensation> response =
                restTemplate.postForEntity(compensationUrl, compensation, Compensation.class, employeeId);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        return response;
    }

    // Raw JSON, for values a Compensation object can't hold, such as a salary of "abc"
    private ResponseEntity<JsonNode> postJson(String employeeId, String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        return restTemplate.postForEntity(compensationUrl, new HttpEntity<>(json, headers), JsonNode.class, employeeId);
    }

    private Compensation read(String employeeId) {
        ResponseEntity<Compensation> response =
                restTemplate.getForEntity(compensationUrl, Compensation.class, employeeId);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        return response.getBody();
    }

    private void assertBadRequest(String employeeId, String json, String expectedDetail) {
        ResponseEntity<JsonNode> response = postJson(employeeId, json);

        assertEquals(json, HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertTrue(response.getBody().toString(), response.getBody().get("detail").asText().contains(expectedDetail));
    }
}
