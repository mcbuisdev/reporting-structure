package com.mindex.challenge.data;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * One entry in an employee's compensation history. Entries are only ever added, never changed, so a raise is a new
 * entry with a later effectiveDate. Stored in its own collection rather than inside Employee, so employee updates
 * (which replace the whole employee document) can't erase it, and employee responses never include salaries.
 */
@Document
public class Compensation {
    @Id
    private String compensationId;

    @Indexed
    private String employeeId;

    // Annual amount. Stored as Decimal128 because Spring Data stores BigDecimal as a string by default, which sorts
    // and compares wrongly in the database
    @Field(targetType = FieldType.DECIMAL128)
    private BigDecimal salary;

    private LocalDate effectiveDate;

    // When the entry was recorded; also decides between two entries with the same effectiveDate (the later one wins)
    private Instant createdAt;

    public Compensation() {
    }

    public String getCompensationId() {
        return compensationId;
    }

    public void setCompensationId(String compensationId) {
        this.compensationId = compensationId;
    }

    public String getEmployeeId() {
        return employeeId;
    }

    public void setEmployeeId(String employeeId) {
        this.employeeId = employeeId;
    }

    public BigDecimal getSalary() {
        return salary;
    }

    public void setSalary(BigDecimal salary) {
        this.salary = salary;
    }

    public LocalDate getEffectiveDate() {
        return effectiveDate;
    }

    public void setEffectiveDate(LocalDate effectiveDate) {
        this.effectiveDate = effectiveDate;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
