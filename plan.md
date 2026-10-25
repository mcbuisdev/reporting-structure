# Plan

## Task 1: ReportingStructure

### Goal
Add a read-only endpoint that, given an `employeeId`, returns:

```json
{
  "employee": { ...the employee's record... },
  "numberOfReports": 4
}
```

`numberOfReports` counts **every distinct employee below** the given employee: their direct reports, those people's
direct reports, and so on. It is computed on every request and never saved to the database.

### How the data is shaped
- Each employee's `directReports` stores **stubs that hold only an `employeeId`**, not full records
  (see `employee_database.json`). To count the whole tree we look up each stub's ID and follow its `directReports`.
- Employees with no reports have **no `directReports` field at all**, so the list is `null`, not empty.
- Nothing in the code enforces a strict tree. `PUT /employee/{id}` accepts any `directReports` list, so the data can
  contain cycles, shared reports, self-references, and IDs that don't exist.

---

### Problems found in the existing code (verified 2026-10-25)

> **Status:** B1, B2, B3 and B5 are fixed. Each regression test failed before its fix and passes after it.
> B4 is a rule for writing tests, not a fix.

| # | Problem | How it was verified | Fix |
|---|---|---|---|
| B1 ✅ | **`PUT /employee/{id}` inserted a duplicate document instead of updating.** `Employee` had no `@Id`, so every later `GET` for that ID failed with HTTP 500. | Ran the app: GET → 200, PUT → 200, GET → **500**. | `@Id` on `employeeId`. `testCreateReadUpdate` now reads the employee again after the PUT. |
| B2 ✅ | **An unknown ID returned HTTP 500** (a plain `RuntimeException`). | `GET /employee/does-not-exist` → **500**. | `EmployeeNotFoundException` with `@ResponseStatus(NOT_FOUND)`. Test: `testReadUnknownEmployeeReturnsNotFound`. |
| B3 ✅ | **Log messages were copy-pasted and wrong** ("create" in read and update). | Read the code. | Corrected. |
| B4 | **JUnit 5 tests would silently not run.** `build.gradle` has no `useJUnitPlatform()`. | Read `build.gradle` and the existing tests. | Rule: new tests use **JUnit 4** (`org.junit.Test`, `@RunWith`). |
| B5 ✅ | **`PUT` to an unknown ID created a new employee.** Creating employees is the job of `POST`. If the business wants upserts, that's a separate request. | Test: PUT unknown → 200 and the employee was created. | `update()` returns 404 if the ID doesn't exist. Test: `testUpdateUnknownEmployeeReturnsNotFound`. |

---

### Decisions (all settled)

| # | Question | Decision |
|---|---|---|
| D1 ✅ | HTTP method? | **GET.** Read-only, idempotent, nothing is saved. |
| D2 ✅ | URL? | **`GET /reportingStructure/{employeeId}`**, a separate type with its own controller. Either URL would work technically, but separating them sets the pattern for later: each type has its own place for future endpoints. Compensation will be its own entity in Task 2 and will follow the same pattern. The path variable is named `employeeId` so the URL is clear about which ID it takes. |
| D3 ✅ | What shape is the `employee` field? | **The employee's record as stored, with `directReports` as ID stubs.** No expanded tree. We'd confirm with the business what the callers need. If they want the full tree, the code is in the **Appendix**. |
| D4 ✅ | Count someone reachable through two managers twice? | **No.** Each distinct ID is counted once. |
| D5 ✅ | Cycles (A → B → A)? | **Two layers.** (1) When reading: stop at the repeat and log a warning, because it's bad data someone should fix. We need this even with (2), because bad data can also come from the snapshot file or other writers. (2) When writing: `PUT /employee/{id}` rejects a `directReports` list that would create a cycle, includes the employee themselves, or names IDs that don't exist. The code will carry a comment explaining why (keep bad data out at the source). See step 5 below. |
| D6 ✅ | An employee lists themselves as a report? | **Never count the requested employee.** |
| D7 ✅ | A report ID that doesn't exist? | **Skip it, log a warning, and list it in a new `unresolvedReportIds` field** so the data can be reviewed and fixed. Proposed detail: leave the field out of the JSON when it's empty (`@JsonInclude(NON_EMPTY)`), so a normal response has exactly the two fields the README specifies. |
| D8 ✅ | A null or blank ID in `directReports`? | **Skip it and log a warning.** It isn't added to `unresolvedReportIds` because there's no ID to show. |
| D9 ✅ | The requested employee doesn't exist? | **404.** Already done (B2). |
| D10 ✅ | Recursion or a loop? | **Both (option D).** A loop fetches the tree one level at a time, then recursion counts over the fetched data. See "Why option D" below. |
| D11 ✅ | How many database queries? | **One for the requested employee, plus one per level of reports below them.** The README example takes 3. |
| D12 ✅ | Fix the existing bugs first? | **Done** (B1, B2, B3, B5). |

#### D10/D11 options (chose D)

| Option | How it works | Queries for the README example | Queries for a 10,000-person company (depth 8, ~1,500 managers) | Can it tell a cycle from a shared report? |
|---|---|---|---|---|
| **A** | Recursion, one query per employee | 5 | ~10,000 | Yes |
| **B** | Recursion, one query per manager to fetch all of their direct reports | 3 | ~1,500 | Yes |
| **C** | Loop, one query per level of the tree | 3 | ~9 | No |
| **D** | Two phases: a loop fetches the tree one level per query into a map, then recursion counts over the map in memory | 3 | ~9 | Yes |

#### Why option D

D is the only option that meets all three requirements:

| Requirement | A | B | C | D |
|---|---|---|---|---|
| Few database queries (D11) | ❌ one per employee | ⚠️ one per manager | ✅ one per level | ✅ one per level |
| Tell a cycle from a shared report, so cycles can be logged (D5) | ✅ | ✅ | ❌ | ✅ |
| Count distinct reports (D4) and collect unresolved IDs (D7) | ✅ | ✅ | ✅ | ✅ |

Reasons to give a reviewer:
1. **Database round trips are the main cost, not the traversal.** Per employee, the in-memory work takes
   nanoseconds. A query is a network round trip, typically a millisecond or more. If each round trip takes 1 ms, option A
   spends about 10 seconds on a 10,000-person org, and option D about 9 ms. Choosing recursion or a loop doesn't change
   that. The number of queries does.
2. **The bad-data requirements need recursion.** Recursion knows the current path, so it can tell a cycle (a report
   pointing back to someone on the path) from a shared report (someone reached through two managers). Option C can't
   tell them apart, so it would either warn about every shared report or never warn about cycles.
3. **Database access and counting logic are separated.** Phase 1 is the only code that talks to the database. Phase 2
   works only on the fetched map, so all the counting rules (cycles, shared reports, missing IDs) live in one place with
   no database code mixed in.
4. **It's reusable.** Step 5's PUT validation needs to answer "can employee X already be reached from these new
   reports?" That is phase 1 started from the new reports, followed by checking whether X is in the map.

Costs we accept:
- **More code** than option A: two methods instead of one.
- **Memory** for the employee's subtree while the request runs (see below).
- **Recursion depth equals the depth of the org chart.** Real orgs are about 10–20 levels deep, and Java's default stack
  handles thousands of levels. Only a corrupt chain thousands of levels deep would be a risk.

#### Memory

The map stores **only what counting needs: each employee's ID and the IDs of their direct reports**
(`Map<String, List<String>>`), not full records. Each level's fetched `Employee` objects are dropped as soon as their IDs
are copied out, so the garbage collector can free them.

Rough estimate for a 64-bit JVM (worked out from typical object sizes, not measured):

| Per employee in the subtree | Approx. size |
|---|---|
| Employee ID string (36-character UUID), shared by the map key, report lists, and counted set | ~80 bytes |
| Map entry and its list of report IDs | ~100 bytes |
| Counted-set entry | ~40 bytes |
| **Total** | **~200–300 bytes** |

| Subtree size | Memory while the request runs |
|---|---|
| 5 (this dataset) | negligible |
| 10,000 | ~2–3 MB |
| 100,000 | ~25 MB |

- **Every option grows the same way.** Counting distinct people means remembering who has been counted, so even
  option A keeps a set of every ID. Option D's map uses about 2–3 times as much as that set alone.
- **There is a short-lived peak per query.** Each level's query result is fully loaded as `Employee` objects before its
  IDs are copied out. The widest level of the tree sets the peak.
- **Memory is per request.** It's freed when the request ends, so it only matters for very large orgs requested often,
  for example the CEO's structure under heavy traffic.

If profiling ever shows memory matters, in order of effort:
1. **Fetch only `employeeId` and `directReports`** in phase 1 (a Mongo field projection), so names and positions never
   load.
2. **Split large ID lists into chunks**, for example 1,000 IDs per query. This caps the peak at the cost of a few extra
   queries.
3. **Cache counts per employee** and clear the cache on every PUT. This is a much bigger change, so only if needed.

---

### Design

New and changed files (all under `src/main/java/com/mindex/challenge/`):

| File | What it holds |
|---|---|
| `data/ReportingStructure.java` | `Employee employee`, `int numberOfReports`, and `List<String> unresolvedReportIds` (left out of the JSON when empty, D7). It is not saved to Mongo. No-arg constructor plus getters and setters, like `Employee`. |
| `service/ReportingStructureService.java` | Interface: `ReportingStructure read(String employeeId)` |
| `service/impl/ReportingStructureServiceImpl.java` | Option D: phase 1 fetches, phase 2 counts. |
| `service/impl/ReportingHierarchy.java` | Phase 1 (the fetch), shared with the step 5 validation. |
| `exception/InvalidDirectReportsException.java` | A broken hierarchy rule on POST or PUT, returned as 409 (step 5). |
| `exception/MalformedDirectReportsException.java` | A direct report with no `employeeId` on POST or PUT, returned as 400 (step 5). |
| `controller/ApiExceptionHandler.java` | Returns the API's errors as problem details that include the reason (step 5). |
| `dao/EmployeeRepository.java` | Add `List<Employee> findByEmployeeIdIn(Collection<String> ids)` for phase 1. |
| `controller/ReportingStructureController.java` | `GET /reportingStructure/{employeeId}` (D2) |
| `README.md` | Document the new endpoint under "How to Use". |

Counting logic (option D):

```
read(employeeId):
    root = repository.findByEmployeeId(employeeId)
    if root is null: throw EmployeeNotFoundException                         # D9

    # Phase 1: fetch, one query per level (loop)
    reportIdsById = { root.employeeId: validReportIds(root) }                # Map<String, List<String>>
    unresolved    = []                                                       # keeps order, no duplicates
    frontier      = unique IDs in root's reports that aren't in reportIdsById
    while frontier is not empty:
        found = repository.findByEmployeeIdIn(frontier)
        for each employee in found:
            reportIdsById[employee.employeeId] = validReportIds(employee)
        for each id in frontier that wasn't found:
            add id to unresolved                                             # D7
        frontier = unique report IDs of found, minus IDs in reportIdsById or unresolved

    # Phase 2: count, recursion over the map (no queries)
    counted = {}
    countReports(root.employeeId, path = { root.employeeId }, counted)
    return new ReportingStructure(root, counted.size, unresolved)

countReports(id, path, counted):
    for each reportId in reportIdsById[id]:
        if path contains reportId: log a cycle warning; continue             # D5, D6
        if counted contains reportId: continue                               # D4: shared report (debug log; can be legitimate)
        if reportIdsById doesn't contain reportId:                           # D7
            log a warning naming the manager who lists it; continue          # (phase 2 knows the manager, phase 1 doesn't)
        counted.add(reportId)
        path.add(reportId)
        countReports(reportId, path, counted)
        path.remove(reportId)

validReportIds(employee):
    the employeeIds of its directReports (an empty list if directReports is null),
    skipping null or blank IDs with a warning                                # D8
```

---

### Tests (JUnit 4, see B4)

**Unit tests:** `ReportingStructureServiceImplTest`, with Mockito replacing `EmployeeRepository`.

| Case | Expected |
|---|---|
| Employee whose `directReports` is `null` | 0 |
| Employee whose `directReports` is empty | 0 |
| README example (John → Paul, Ringo → Pete, George) | 4 |
| Shared report (A → B, A → C, B → D, C → D) | 3, with D counted once (D4) |
| Cycle (A → B → A) | A = 1, B = 1, it finishes, and a warning is logged (D5) |
| Self-reference (A → A) | 0 (D6) |
| Report ID that doesn't exist | not counted, listed in `unresolvedReportIds` (D7) |
| Null or blank ID in a report entry | skipped, not listed (D8) |
| Unknown requested ID | throws `EmployeeNotFoundException` (D9) |
| Result contains the requested employee | `employee` is that same employee |
| Nothing is saved | the repository's `save`/`insert` is never called |
| Number of queries | 1 `findByEmployeeId` plus 1 `findByEmployeeIdIn` per level, so the README example is 1 + 2 (Mockito `verify(..., times(n))`) |
| Shared report vs. cycle | the shared report is not logged as a cycle, and the cycle is (log captured with a Logback `ListAppender`) |
| Deep chain of 1,000 levels | 999, with no stack overflow. That's far deeper than any real org, so it's a safety margin for recursion. |

The mocked repository answers from an in-memory map, so each test builds its hierarchy directly with an
`addEmployee("A", "B", "C")` helper.

**Repository test:** `EmployeeRepositoryTest` checks that `findByEmployeeIdIn` works against the real in-memory database
and leaves out IDs that don't exist. The unit tests mock the repository, so they can't confirm this.

**Integration tests:** `ReportingStructureIntegrationTest`, with `@SpringBootTest` and `TestRestTemplate`.

| Case | Expected |
|---|---|
| `GET /reportingStructure/{John}` | 200, `numberOfReports` = 4, employee fields match, **no `unresolvedReportIds` field** in the JSON |
| Ringo / Paul | 2 / 0 |
| Unknown ID | 404 |
| Fresh results: create E1 and E2, PUT E1 with E2 as a report, then GET E1's reporting structure | 1 |
| Report ID that doesn't exist: create E3 whose `directReports` includes a made-up ID | 0, and `unresolvedReportIds` contains that ID |

> ⚠️ Test isolation: all test classes share one in-memory database. Tests that change data must **create their
> own employees** and leave the seed employees alone.

---

### Order of work (one commit per step)
1. ✅ **Fix B1:** `@Id` on `employeeId`, plus the regression test. Fix the B3 log messages.
2. ✅ **Fix B2 and B5:** `EmployeeNotFoundException` for unknown IDs on read and update, plus tests.
3. ✅ **`ReportingStructure`** type, service (option D), and unit tests: 13 service tests and 1 repository test.
4. ✅ **Controller** and integration tests: 5 tests in `ReportingStructureIntegrationTest`. Also checked by hand on the
   running app: John → 4, Ringo → 2, unknown ID → 404.
5. ✅ **Validate direct reports on writes (D5, layer 2).**
   - **`PUT /employee/{id}`** rejects new `directReports` that include the employee themselves, name an ID that
     doesn't exist, or would create a cycle (someone below the new reports already lists this employee).
   - **`POST /employee`** rejects direct reports that don't exist. A brand-new employee can't be in a cycle, because
     nobody can list them yet.
   - **Status: 409 Conflict** (decided 2026-10-25). The request is well formed but breaks the hierarchy rules for the
     data already stored, so it's a conflict rather than a malformed request (400).
   - **Error bodies give the reason.** Spring Boot hides exception messages by default, which would make a 409 say only
     "Conflict." `ApiExceptionHandler` returns RFC 9457 problem details instead, for example
     `{"status":409,"detail":"Direct reports do not exist: [x-999]",...}`. The 404 responses use the same format now,
     so every error has one consistent shape.
   - **Shared code.** Phase 1 moved from `ReportingStructureServiceImpl` into `ReportingHierarchy`, which both services
     use. Its root's reports are taken from the incoming object, so validation checks the change before saving it.
   - **Malformed reports return 400** (decided 2026-10-25). A direct report with no `employeeId` (`{}`, `""`, blank, or
     `null`) can't refer to anyone, so the request itself is malformed. This is checked first, before the 409 rules.
     The detail names the bad entry, for example `"directReports[1] has no employeeId"`. The read side still skips such
     entries with a warning, for bad data that arrives some other way.
   - **Tests:** 9 in `EmployeeServiceImplTest`, written first and seen failing:
     - POST with an unknown report: 409, and nothing is saved.
     - POST with valid reports: accepted.
     - PUT with an unknown report, a self-reference, or a cycle: 409, and the stored employee is unchanged.
     - PUT with a shared report (two managers): still accepted.
     - POST with `{}`, and PUT with a blank ID or a `null` entry: 400, and nothing changes.
6. **README:** document the endpoint, the PUT validation, the design decisions and the query tradeoffs.

Run `./gradlew build` after each step, and make sure the test count goes up (to catch B4).
---

### Appendix: backup code for an expanded tree (D3)

If the business wants the response to include the whole tree of reports with full records, add this to
`ReportingStructureServiceImpl`. Expose it as an option, for example `GET /reportingStructure/{employeeId}?expand=true`,
so the default response stays small.

```java
// lookup = however the traversal fetches employees (a repository call, or the map built in option D)
private Employee expand(Employee employee, Function<String, Employee> lookup, Set<String> onPath) {
    // Work on a copy so the fetched records are never modified
    Employee copy = new Employee();
    copy.setEmployeeId(employee.getEmployeeId());
    copy.setFirstName(employee.getFirstName());
    copy.setLastName(employee.getLastName());
    copy.setPosition(employee.getPosition());
    copy.setDepartment(employee.getDepartment());

    if (employee.getDirectReports() == null) {
        return copy;
    }

    onPath.add(employee.getEmployeeId());
    List<Employee> expandedReports = new ArrayList<>();
    for (Employee stub : employee.getDirectReports()) {
        String reportId = stub.getEmployeeId();
        // Skip bad IDs, and stop at a cycle so the JSON stays finite
        if (reportId == null || reportId.isBlank() || onPath.contains(reportId)) {
            continue;
        }
        Employee report = lookup.apply(reportId);
        if (report != null) {
            expandedReports.add(expand(report, lookup, onPath));
        }
    }
    onPath.remove(employee.getEmployeeId());

    copy.setDirectReports(expandedReports);
    return copy;
}

// Usage: new ReportingStructure(expand(root, lookup, new HashSet<>()), count, unresolvedIds)
```

Notes:
- Someone with two managers appears under both. That's finite and accurate. Only cycles are cut off.
- To keep memory small, option D's map holds only report IDs. Expanding needs full records, so phase 1 would keep the
  fetched `Employee` objects too (`Map<String, Employee>`). That costs more memory but no extra queries. Using a
  repository call as `lookup` instead would query every employee again.
- The response size grows with the org, which is why it should be opt-in.

---

## Task 2: Compensation
_To be planned when Task 1 is complete._

---

## End of challenge: revisit before submitting
- **Empty fields in the JSON** (decided 2026-10-25: leave as is for now). The direct-report stubs in responses print
  every empty field (`"firstName":null, "lastName":null, ...`). This already happens in `GET /employee/{id}`. Adding
  `@JsonInclude(NON_NULL)` to `Employee` would shorten them to `{"employeeId": "..."}`, but it changes the JSON of the
  existing endpoints.
