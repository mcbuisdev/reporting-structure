package com.mindex.challenge.dao;

import com.mindex.challenge.data.Compensation;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface CompensationRepository extends MongoRepository<Compensation, String> {

    /**
     * The entry in effect on the given day: the latest effectiveDate on or before it. Of two entries on the same day,
     * the one recorded later wins, and compensationId settles an exact tie so the result is always the same.
     */
    default Optional<Compensation> findCurrent(String employeeId, LocalDate day) {
        return findFirstByEmployeeIdAndEffectiveDateLessThanEqualOrderByEffectiveDateDescCreatedAtDescCompensationIdDesc(
                employeeId, day);
    }

    Optional<Compensation> findFirstByEmployeeIdAndEffectiveDateLessThanEqualOrderByEffectiveDateDescCreatedAtDescCompensationIdDesc(
            String employeeId, LocalDate day);

    /**
     * Every entry for the employee, including future ones, newest effectiveDate first. Same-day entries use the same
     * order as findCurrent, so the entry findCurrent picks also comes first among its day's entries here.
     */
    default List<Compensation> findHistory(String employeeId) {
        return findByEmployeeIdOrderByEffectiveDateDescCreatedAtDescCompensationIdDesc(employeeId);
    }

    List<Compensation> findByEmployeeIdOrderByEffectiveDateDescCreatedAtDescCompensationIdDesc(String employeeId);
}
