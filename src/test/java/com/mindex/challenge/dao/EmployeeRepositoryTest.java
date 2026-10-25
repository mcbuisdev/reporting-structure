package com.mindex.challenge.dao;

import com.mindex.challenge.data.Employee;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;

@RunWith(SpringRunner.class)
@SpringBootTest
public class EmployeeRepositoryTest {

    private static final String PAUL_ID = "b7839309-3348-463b-a7e3-5de1c168beb3";
    private static final String RINGO_ID = "03aa1462-ffa9-4978-901b-7c001562cf6f";

    @Autowired
    private EmployeeRepository employeeRepository;

    @Test
    public void testFindByEmployeeIdInReturnsOnlyExistingEmployees() {
        List<Employee> employees = employeeRepository.findByEmployeeIdIn(Arrays.asList(PAUL_ID, RINGO_ID, "missing"));

        Set<String> employeeIds = employees.stream().map(Employee::getEmployeeId).collect(Collectors.toSet());
        assertEquals(Set.of(PAUL_ID, RINGO_ID), employeeIds);
    }
}
