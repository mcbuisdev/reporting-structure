package com.mindex.challenge.service.impl;

import com.mindex.challenge.dao.CompensationRepository;
import com.mindex.challenge.dao.EmployeeRepository;
import com.mindex.challenge.data.Compensation;
import com.mindex.challenge.exception.EmployeeNotFoundException;
import com.mindex.challenge.exception.InvalidCompensationException;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class CompensationServiceImplTest {

    private static final String EMPLOYEE_ID = "employee-1";
    private static final Instant NOW = Instant.parse("2026-10-25T15:30:00.123456Z");

    @Mock
    private CompensationRepository compensationRepository;

    @Mock
    private EmployeeRepository employeeRepository;

    @InjectMocks
    private CompensationServiceImpl compensationService;

    @Before
    public void setup() {
        ReflectionTestUtils.setField(compensationService, "clock", Clock.fixed(NOW, ZoneOffset.UTC));

        lenient().when(employeeRepository.existsById(EMPLOYEE_ID)).thenReturn(true);
        lenient().when(compensationRepository.insert(any(Compensation.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    public void testCreateSetsServerOwnedFields() {
        Compensation created = compensationService.create(EMPLOYEE_ID, compensation("85000.50", "2026-11-01"));

        ArgumentCaptor<Compensation> saved = ArgumentCaptor.forClass(Compensation.class);
        verify(compensationRepository).insert(saved.capture());
        assertSame(saved.getValue(), created);

        assertNotNull(created.getCompensationId());
        assertEquals(EMPLOYEE_ID, created.getEmployeeId());
        assertEquals(new BigDecimal("85000.50"), created.getSalary());
        assertEquals(LocalDate.of(2026, 11, 1), created.getEffectiveDate());
        // Truncated to milliseconds, matching what the database stores
        assertEquals(Instant.parse("2026-10-25T15:30:00.123Z"), created.getCreatedAt());
    }

    @Test
    public void testCreateIgnoresServerOwnedFieldsFromClient() {
        Compensation request = compensation("85000", "2026-11-01");
        request.setCompensationId("client-chosen-id");
        request.setEmployeeId("someone-else");
        request.setCreatedAt(Instant.parse("2000-01-01T00:00:00Z"));

        Compensation created = compensationService.create(EMPLOYEE_ID, request);

        assertNotEquals("client-chosen-id", created.getCompensationId());
        assertEquals(EMPLOYEE_ID, created.getEmployeeId());
        assertEquals(Instant.parse("2026-10-25T15:30:00.123Z"), created.getCreatedAt());
    }

    @Test
    public void testCreateAllowsTwoDecimalPlacesAndTrailingZeros() {
        compensationService.create(EMPLOYEE_ID, compensation("85000.99", "2026-11-01"));
        compensationService.create(EMPLOYEE_ID, compensation("85000.500", "2026-11-01"));
    }

    @Test
    public void testCreateForUnknownEmployeeThrowsNotFound() {
        try {
            compensationService.create("missing", compensation("85000", "2026-11-01"));
            fail("Expected EmployeeNotFoundException");
        } catch (EmployeeNotFoundException expected) {
            verify(compensationRepository, never()).insert(any(Compensation.class));
        }
    }

    @Test
    public void testCreateRejectsMissingSalary() {
        assertRejected(compensation(null, "2026-11-01"), "salary is required");
    }

    @Test
    public void testCreateRejectsZeroSalary() {
        assertRejected(compensation("0", "2026-11-01"), "salary must be greater than 0");
    }

    @Test
    public void testCreateRejectsNegativeSalary() {
        assertRejected(compensation("-100", "2026-11-01"), "salary must be greater than 0");
    }

    @Test
    public void testCreateRejectsMoreThanTwoDecimalPlaces() {
        assertRejected(compensation("85000.505", "2026-11-01"), "salary can have at most 2 decimal places");
    }

    @Test
    public void testCreateRejectsMissingEffectiveDate() {
        assertRejected(compensation("85000", null), "effectiveDate is required");
    }

    @Test
    public void testCreateReportsEveryProblemAtOnce() {
        assertRejected(compensation(null, null), "salary is required; effectiveDate is required");
    }

    @Test
    public void testReadCurrentAsksForTodayFromTheClock() {
        Compensation current = compensation("85000", "2026-01-01");
        when(compensationRepository.findCurrent(EMPLOYEE_ID, LocalDate.of(2026, 10, 25)))
                .thenReturn(Optional.of(current));

        assertSame(current, compensationService.readCurrent(EMPLOYEE_ID).orElseThrow());
    }

    @Test
    public void testReadCurrentIsEmptyWhenNothingIsInEffect() {
        when(compensationRepository.findCurrent(EMPLOYEE_ID, LocalDate.of(2026, 10, 25))).thenReturn(Optional.empty());

        assertFalse(compensationService.readCurrent(EMPLOYEE_ID).isPresent());
    }

    @Test(expected = EmployeeNotFoundException.class)
    public void testReadCurrentForUnknownEmployeeThrowsNotFound() {
        compensationService.readCurrent("missing");
    }

    @Test
    public void testReadHistoryReturnsRepositoryHistory() {
        List<Compensation> history = List.of(compensation("90000", "2027-01-01"), compensation("85000", "2026-01-01"));
        when(compensationRepository.findHistory(EMPLOYEE_ID)).thenReturn(history);

        assertSame(history, compensationService.readHistory(EMPLOYEE_ID));
    }

    @Test(expected = EmployeeNotFoundException.class)
    public void testReadHistoryForUnknownEmployeeThrowsNotFound() {
        compensationService.readHistory("missing");
    }

    private void assertRejected(Compensation request, String expectedMessage) {
        try {
            compensationService.create(EMPLOYEE_ID, request);
            fail("Expected InvalidCompensationException");
        } catch (InvalidCompensationException expected) {
            assertEquals(expectedMessage, expected.getMessage());
            verify(compensationRepository, never()).insert(any(Compensation.class));
        }
    }

    private static Compensation compensation(String salary, String effectiveDate) {
        Compensation compensation = new Compensation();
        compensation.setSalary(salary == null ? null : new BigDecimal(salary));
        compensation.setEffectiveDate(effectiveDate == null ? null : LocalDate.parse(effectiveDate));
        return compensation;
    }
}
