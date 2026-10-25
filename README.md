# Coding Challenge
## What's Provided
A simple [Spring Boot](https://projects.spring.io/spring-boot/) web application has been created and bootstrapped with data. The application contains 
information about all employees at a company. On application start-up, an in-memory Mongo database is bootstrapped with 
a serialized snapshot of the database. While the application runs, the data may be accessed and mutated in the database 
without impacting the snapshot.

### How to Run
The application may be executed by running `gradlew bootRun`.

*Spring Boot 3 requires Java 17 or higher. This project targets Java 17. If you want to change the targeted Java 
version, you can modify the `sourceCompatibility` variable in the `build.gradle` file.*

### How to Use
The following endpoints are available to use:
```
* CREATE
    * HTTP Method: POST 
    * URL: localhost:8080/employee
    * PAYLOAD: Employee
    * RESPONSE: Employee
* READ
    * HTTP Method: GET 
    * URL: localhost:8080/employee/{id}
    * RESPONSE: Employee
* UPDATE
    * HTTP Method: PUT 
    * URL: localhost:8080/employee/{id}
    * PAYLOAD: Employee
    * RESPONSE: Employee
* READ REPORTING STRUCTURE
    * HTTP Method: GET
    * URL: localhost:8080/reportingStructure/{id}
    * RESPONSE: ReportingStructure
* CREATE COMPENSATION
    * HTTP Method: POST
    * URL: localhost:8080/compensation/{id}
    * PAYLOAD: Compensation (salary and effectiveDate)
    * RESPONSE: Compensation
* READ COMPENSATION
    * HTTP Method: GET
    * URL: localhost:8080/compensation/{id}
    * RESPONSE: the Compensation in effect today, or 204 No Content if none is in effect yet
* READ COMPENSATION HISTORY
    * HTTP Method: GET
    * URL: localhost:8080/compensation/{id}/history
    * RESPONSE: every Compensation for the employee, including future ones, newest effectiveDate first
```

The Employee has a JSON schema of:
```json
{
  "title": "Employee",
  "type": "object",
  "properties": {
    "employeeId": {
      "type": "string"
    },
    "firstName": {
      "type": "string"
    },
    "lastName": {
      "type": "string"
    },
    "position": {
      "type": "string"
    },
    "department": {
      "type": "string"
    },
    "directReports": {
      "type": "array",
      "items": {
        "anyOf": [
          {
            "type": "string"
          },
          {
            "type": "object"
          }
        ]
      }
    }
  }
}
```
For all endpoints that require an `id` in the URL, this is the `employeeId` field.

## What to Implement
This coding challenge was designed to allow for flexibility in the approaches you take. While the requirements are 
minimal, we encourage you to explore various design and implementation strategies to create functional features. Keep in
mind that there are multiple valid ways to solve these tasks. What's important is your ability to justify and articulate
the reasoning behind your design choices. We value your thought process and decision-making skills. Also, If you 
identify any areas in the existing codebase that you believe can be enhanced, feel free to make those improvements.

### Task 1
Create a new type called `ReportingStructure` that has two fields: `employee` and `numberOfReports`.

The field `numberOfReports` should equal the total number of reports under a given employee. The number of reports is 
determined by the number of `directReports` for an employee, all of their distinct reports, and so on. For example,
given the following employee structure:
```
                   John Lennon
                 /             \
         Paul McCartney     Ringo Starr
                            /         \
                       Pete Best    George Harrison
```
The `numberOfReports` for employee John Lennon (`employeeId`: 16a596ae-edd3-4847-99fe-c4518e82c86f) would be equal to 4.

This new type should have a new REST endpoint created for it. This new endpoint should accept an `employeeId` and return
the fully filled out `ReportingStructure` for the specified `employeeId`. The values should be computed on the fly and 
will not be persisted.

### Task 2
Create a new type called `Compensation` to represent an employee's compensation details. A `Compensation` should have at 
minimum these two fields: `salary` and `effectiveDate`. Each `Compensation` should be associated with a specific 
`Employee`. How that association is implemented is up to you.

Create two new REST endpoints to create and read `Compensation` information from the database. These endpoints should 
persist and fetch `Compensation` data for a specific `Employee` using the persistence layer.

## Delivery
Please upload your results to a publicly accessible Git repo. Free ones are provided by GitHub and Bitbucket.

---

## Implementation Notes
This section describes what was built and why. The full reasoning behind every decision, including the alternatives
considered, is in [plan.md](plan.md).

### Running the tests
```
gradlew test
```
There are 70 tests in three layers: unit tests with Mockito, repository tests against the in-memory database, and
integration tests over HTTP. They use JUnit 4 like the original tests, because `build.gradle` doesn't enable the JUnit 5
platform, so JUnit 5 tests would silently not run.

### Fixes to the existing code
- **`PUT /employee/{id}` inserted a duplicate instead of updating.** `Employee` had no `@Id`, so every later read of
  that employee failed with HTTP 500. `employeeId` is now the document ID.
- **`PUT` to an unknown ID created a new employee.** Creating employees is the job of `POST`, so it now returns 404.
- **An unknown ID returned 500.** It now returns 404.
- **Copy-pasted log messages** in the employee controller and service are corrected.
- **The `.gradle` cache directory was committed.** It's now ignored, along with `build/` and IntelliJ files.

### Task 1: Reporting structure
```json
GET /reportingStructure/16a596ae-edd3-4847-99fe-c4518e82c86f
{ "employee": { "employeeId": "16a596ae-...", "firstName": "John", ... }, "numberOfReports": 4 }
```
- **Two phases.** A loop fetches the hierarchy one level per query, keeping only report IDs to save memory. Then a
  recursive walk counts each distinct person once. The number of queries depends on how many levels the org chart has,
  not how many people are in it: one for the requested employee, plus one per level below them.
- **Bad data doesn't break the count.** Nothing in the original code enforced a clean tree, so:
  - **Someone with two managers** is counted once.
  - **Cycles** (A → B → A) and **self-references** stop the walk and log a warning. Because the walk knows the current
    chain of managers, it can tell a cycle apart from a shared report.
  - **Report IDs that don't exist** are skipped and listed in an `unresolvedReportIds` field so the data can be fixed.
    That field is left out when empty, so a normal response has exactly the two fields specified above.
- **Bad data is rejected when it's written.** Employee `POST` and `PUT` reject direct reports with no `employeeId`
  (400). They also reject reports that don't exist, an employee listing themselves, and changes that would create a
  cycle (409). The read side still guards against bad data that arrives some other way, such as the snapshot.

### Task 2: Compensation
```json
POST /compensation/b7839309-3348-463b-a7e3-5de1c168beb3
{ "salary": 85000.50, "effectiveDate": "2026-01-01" }

200 OK
{ "compensationId": "3f3a112a-...", "employeeId": "b7839309-...", "salary": 85000.50,
  "effectiveDate": "2026-01-01", "createdAt": "2026-10-25T18:38:19.466Z" }
```
- **History is kept.** Every `POST` adds a new entry, and nothing is overwritten. A raise is a new entry with a later
  `effectiveDate`.
- **`GET` returns the compensation in effect today:** the entry with the latest `effectiveDate` on or before today.
  A raise scheduled for next month isn't current yet. If the employee exists but nothing is in effect yet, the
  response is `204 No Content`. A 404 always means the employee doesn't exist.
- **`GET /compensation/{id}/history` returns every entry**, including future ones, newest `effectiveDate` first, or
  `[]` if there are none. Anything that was created can always be read back, even a raise that isn't in effect yet.
- **Two entries on the same day:** the one recorded later (`createdAt`) wins, so posting again acts as a correction,
  and both stay in the history.
- **Stored in its own collection, linked by `employeeId`.** Not stored inside `Employee`, because `PUT /employee`
  replaces the whole employee document and would erase it, and every employee response would expose salaries.
- **Types:** the salary is a `BigDecimal` (never `double` for money), stored as Mongo's `Decimal128`, since Spring Data
  would otherwise store it as a string. A test checks the raw stored type. `effectiveDate` is a `LocalDate` because a
  salary takes effect on a day, not a time.
- **Validation (400):** the salary is required, must be greater than 0, and can have at most 2 decimal places. The
  effective date is required. Every problem is reported at once. Values that can't be parsed, such as a date of
  `"2026-13-40"`, return the name of the field.
- **Server-owned fields** (`compensationId`, `employeeId`, `createdAt`) are ignored if a client sends them.

### Errors
Errors from the API's own checks, and request bodies that can't be parsed, use the standard
[RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) problem-details format, including the reason. Errors Spring produces
on its own, such as an unknown URL, still use Spring's default format.
```json
{ "type": "about:blank", "title": "Conflict", "status": 409,
  "detail": "Direct reports do not exist: [x-999]", "instance": "/employee" }
```
| Status | Meaning |
|---|---|
| 400 | The request is malformed or a field is invalid |
| 404 | The employee doesn't exist |
| 409 | The request is well formed but conflicts with stored data (a hierarchy rule) |

### Next steps, depending on the business
- **Access control for salaries.** The app has no authentication, so anyone can read anyone's compensation. The calling
  application may already restrict this, but it needs confirming before production use.
- **Very large orgs:** if profiling shows the reporting structure query matters, fetch only the ID fields and split
  large ID lists into chunks. See the memory notes in [plan.md](plan.md).
