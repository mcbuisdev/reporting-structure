package com.mindex.challenge.dao;

import com.mindex.challenge.data.Compensation;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.test.context.junit4.SpringRunner;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(SpringRunner.class)
@SpringBootTest
public class CompensationRepositoryTest {

    @Autowired
    private CompensationRepository compensationRepository;

    @Autowired
    private MongoTemplate mongoTemplate;

    private String employeeId;

    @Before
    public void setup() {
        // A fresh employee ID per test, since all tests share one in-memory database
        employeeId = UUID.randomUUID().toString();
    }

    @Test
    public void testSalaryIsStoredAsDecimal128() {
        Compensation saved = save("85000.50", LocalDate.of(2026, 11, 1), Instant.parse("2026-10-25T12:00:00Z"));

        Compensation reloaded = compensationRepository.findById(saved.getCompensationId()).orElseThrow();
        assertEquals(new BigDecimal("85000.50"), reloaded.getSalary());

        // Check the raw stored value, not just the Java object, to catch a silent string conversion
        Document raw = mongoTemplate.getCollection("compensation")
                .find(new Document("_id", saved.getCompensationId())).first();
        assertTrue("salary stored as " + raw.get("salary").getClass(), raw.get("salary") instanceof Decimal128);
    }

    @Test
    public void testEffectiveDateRoundTripsUnchanged() {
        Compensation saved = save("1", LocalDate.of(2026, 11, 1), Instant.parse("2026-10-25T12:00:00Z"));

        Compensation reloaded = compensationRepository.findById(saved.getCompensationId()).orElseThrow();
        assertEquals(LocalDate.of(2026, 11, 1), reloaded.getEffectiveDate());
        assertEquals(Instant.parse("2026-10-25T12:00:00Z"), reloaded.getCreatedAt());
    }

    @Test
    public void testFindCurrentSkipsFutureEntriesAndPrefersLaterCorrection() {
        Instant entered = Instant.parse("2026-01-01T09:00:00Z");
        save("50000", LocalDate.of(2020, 1, 1), entered);
        save("60000", LocalDate.of(2026, 1, 1), entered);
        Compensation correction = save("61000", LocalDate.of(2026, 1, 1), entered.plusSeconds(60));
        save("70000", LocalDate.of(2027, 1, 1), entered);

        Compensation current = compensationRepository.findCurrent(employeeId, LocalDate.of(2026, 6, 1)).orElseThrow();

        assertEquals(correction.getCompensationId(), current.getCompensationId());
    }

    @Test
    public void testFindCurrentIncludesEntryEffectiveThatDay() {
        Compensation entry = save("50000", LocalDate.of(2026, 6, 1), Instant.parse("2026-01-01T09:00:00Z"));

        assertEquals(entry.getCompensationId(),
                compensationRepository.findCurrent(employeeId, LocalDate.of(2026, 6, 1)).orElseThrow().getCompensationId());
    }

    @Test
    public void testFindCurrentIsEmptyWhenOnlyFutureEntriesExist() {
        save("50000", LocalDate.of(2027, 1, 1), Instant.parse("2026-01-01T09:00:00Z"));

        assertFalse(compensationRepository.findCurrent(employeeId, LocalDate.of(2026, 6, 1)).isPresent());
    }

    @Test
    public void testFindHistoryReturnsEveryEntryNewestFirst() {
        Instant entered = Instant.parse("2026-01-01T09:00:00Z");
        Compensation old = save("50000", LocalDate.of(2020, 1, 1), entered);
        Compensation original = save("60000", LocalDate.of(2026, 1, 1), entered);
        Compensation correction = save("61000", LocalDate.of(2026, 1, 1), entered.plusSeconds(60));
        Compensation future = save("70000", LocalDate.of(2027, 1, 1), entered);

        List<String> historyIds = compensationRepository.findHistory(employeeId).stream()
                .map(Compensation::getCompensationId)
                .collect(Collectors.toList());

        assertEquals(List.of(future.getCompensationId(), correction.getCompensationId(),
                original.getCompensationId(), old.getCompensationId()), historyIds);
    }

    @Test
    public void testFindHistoryOnlyReturnsThatEmployeesEntries() {
        Compensation mine = save("50000", LocalDate.of(2026, 1, 1), Instant.parse("2026-01-01T09:00:00Z"));
        String otherEmployeeId = employeeId;
        employeeId = UUID.randomUUID().toString();
        save("90000", LocalDate.of(2026, 1, 1), Instant.parse("2026-01-01T09:00:00Z"));

        List<Compensation> history = compensationRepository.findHistory(otherEmployeeId);

        assertEquals(1, history.size());
        assertEquals(mine.getCompensationId(), history.get(0).getCompensationId());
    }

    @Test
    public void testEmployeeIdIsIndexed() {
        boolean indexed = mongoTemplate.indexOps(Compensation.class).getIndexInfo().stream()
                .map(IndexInfo::getIndexFields)
                .anyMatch(fields -> fields.size() == 1 && fields.get(0).getKey().equals("employeeId"));

        assertTrue(indexed);
    }

    private Compensation save(String salary, LocalDate effectiveDate, Instant createdAt) {
        Compensation compensation = new Compensation();
        compensation.setCompensationId(UUID.randomUUID().toString());
        compensation.setEmployeeId(employeeId);
        compensation.setSalary(new BigDecimal(salary));
        compensation.setEffectiveDate(effectiveDate);
        compensation.setCreatedAt(createdAt);
        return compensationRepository.insert(compensation);
    }
}
