package com.mindex.challenge.service.impl;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.mindex.challenge.dao.EmployeeRepository;
import com.mindex.challenge.data.Employee;
import com.mindex.challenge.data.ReportingStructure;
import com.mindex.challenge.exception.EmployeeNotFoundException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@RunWith(MockitoJUnitRunner.class)
public class ReportingStructureServiceImplTest {

    @Mock
    private EmployeeRepository employeeRepository;

    @InjectMocks
    private ReportingStructureServiceImpl reportingStructureService;

    private final Map<String, Employee> database = new HashMap<>();

    private Logger serviceLogger;
    private ListAppender<ILoggingEvent> logAppender;

    @Before
    public void setup() {
        // The mocked repository answers from an in-memory map, so each test can build any hierarchy shape
        lenient().when(employeeRepository.findByEmployeeId(anyString()))
                .thenAnswer(invocation -> database.get(invocation.<String>getArgument(0)));
        lenient().when(employeeRepository.findByEmployeeIdIn(anyCollection()))
                .thenAnswer(invocation -> {
                    Collection<String> employeeIds = invocation.getArgument(0);
                    return employeeIds.stream()
                            .map(database::get)
                            .filter(Objects::nonNull)
                            .collect(Collectors.toList());
                });

        serviceLogger = (Logger) LoggerFactory.getLogger(ReportingStructureServiceImpl.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        serviceLogger.addAppender(logAppender);
    }

    @After
    public void tearDown() {
        serviceLogger.detachAppender(logAppender);
    }

    @Test
    public void testNullDirectReports() {
        addEmployee("A");

        ReportingStructure reportingStructure = reportingStructureService.read("A");

        assertEquals(0, reportingStructure.getNumberOfReports());
        assertTrue(reportingStructure.getUnresolvedReportIds().isEmpty());
        verify(employeeRepository, never()).findByEmployeeIdIn(anyCollection());
    }

    @Test
    public void testEmptyDirectReports() {
        addEmployee("A");
        database.get("A").setDirectReports(new ArrayList<>());

        assertEquals(0, reportingStructureService.read("A").getNumberOfReports());
    }

    @Test
    public void testReadmeExample() {
        addEmployee("John", "Paul", "Ringo");
        addEmployee("Paul");
        addEmployee("Ringo", "Pete", "George");
        addEmployee("Pete");
        addEmployee("George");

        ReportingStructure reportingStructure = reportingStructureService.read("John");

        assertSame(database.get("John"), reportingStructure.getEmployee());
        assertEquals(4, reportingStructure.getNumberOfReports());
        assertTrue(reportingStructure.getUnresolvedReportIds().isEmpty());
        assertTrue(warnings().isEmpty());

        // One query for John, then one per level: [Paul, Ringo] and [Pete, George]
        verify(employeeRepository, times(1)).findByEmployeeId("John");
        verify(employeeRepository, times(2)).findByEmployeeIdIn(anyCollection());
    }

    @Test
    public void testNothingIsSaved() {
        addEmployee("A", "B");
        addEmployee("B");

        reportingStructureService.read("A");

        // Only reads happen: no save, insert, or delete
        verify(employeeRepository).findByEmployeeId("A");
        verify(employeeRepository).findByEmployeeIdIn(anyCollection());
        verifyNoMoreInteractions(employeeRepository);
    }

    @Test
    public void testSharedReportIsCountedOnce() {
        addEmployee("A", "B", "C");
        addEmployee("B", "D");
        addEmployee("C", "D");
        addEmployee("D");

        assertEquals(3, reportingStructureService.read("A").getNumberOfReports());

        // Someone with two managers is not a cycle, so there is nothing to warn about
        assertTrue(warnings().isEmpty());

        // D is fetched once: [B, C], then [D]
        verify(employeeRepository, times(2)).findByEmployeeIdIn(anyCollection());
    }

    @Test
    public void testReportListedTwiceBySameManagerIsCountedOnce() {
        addEmployee("A", "B", "B");
        addEmployee("B");

        assertEquals(1, reportingStructureService.read("A").getNumberOfReports());
    }

    @Test
    public void testCycleBackToRequestedEmployee() {
        addEmployee("A", "B");
        addEmployee("B", "A");

        assertEquals(1, reportingStructureService.read("A").getNumberOfReports());
        assertEquals(1, reportingStructureService.read("B").getNumberOfReports());

        List<String> warnings = warnings();
        assertEquals(2, warnings.size());
        assertTrue(warnings.stream().allMatch(warning -> warning.startsWith("Reporting cycle")));
    }

    @Test
    public void testCycleBelowRequestedEmployee() {
        addEmployee("A", "B");
        addEmployee("B", "C");
        addEmployee("C", "B");

        assertEquals(2, reportingStructureService.read("A").getNumberOfReports());

        List<String> warnings = warnings();
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("employee [C] lists [B]"));
    }

    @Test
    public void testEmployeeListingThemselvesIsNotCounted() {
        addEmployee("A", "A");

        assertEquals(0, reportingStructureService.read("A").getNumberOfReports());
        assertEquals(1, warnings().size());
    }

    @Test
    public void testUnknownReportIdsAreSkippedAndListed() {
        addEmployee("A", "B", "missing-1");
        addEmployee("B", "missing-2");

        ReportingStructure reportingStructure = reportingStructureService.read("A");

        assertEquals(1, reportingStructure.getNumberOfReports());
        assertEquals(Arrays.asList("missing-1", "missing-2"), reportingStructure.getUnresolvedReportIds());

        List<String> warnings = warnings();
        assertEquals(2, warnings.size());
        assertTrue(warnings.stream().allMatch(warning -> warning.endsWith("which does not exist")));
    }

    @Test
    public void testReportsWithoutAnIdAreSkipped() {
        addEmployee("B");
        addEmployee("A");
        database.get("A").setDirectReports(Arrays.asList(stub(null), stub(""), stub("  "), null, stub("B")));

        ReportingStructure reportingStructure = reportingStructureService.read("A");

        assertEquals(1, reportingStructure.getNumberOfReports());
        assertTrue(reportingStructure.getUnresolvedReportIds().isEmpty());
        assertEquals(4, warnings().size());
    }

    @Test(expected = EmployeeNotFoundException.class)
    public void testUnknownEmployeeThrowsNotFound() {
        reportingStructureService.read("missing");
    }

    @Test
    public void testDeepChain() {
        // Far deeper than any real org chart, as a safety margin for the recursion
        int depth = 1000;
        for (int i = 0; i < depth - 1; i++) {
            addEmployee("E" + i, "E" + (i + 1));
        }
        addEmployee("E" + (depth - 1));

        assertEquals(depth - 1, reportingStructureService.read("E0").getNumberOfReports());
    }

    private void addEmployee(String employeeId, String... reportIds) {
        Employee employee = stub(employeeId);
        employee.setFirstName(employeeId);

        if (reportIds.length > 0) {
            employee.setDirectReports(Arrays.stream(reportIds)
                    .map(ReportingStructureServiceImplTest::stub)
                    .collect(Collectors.toList()));
        }

        database.put(employeeId, employee);
    }

    private static Employee stub(String employeeId) {
        Employee employee = new Employee();
        employee.setEmployeeId(employeeId);
        return employee;
    }

    private List<String> warnings() {
        return logAppender.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.toList());
    }
}
