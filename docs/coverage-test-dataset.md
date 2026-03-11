# GremlinQueryCoverageTest — Dataset Specification

Dataset used for result assertions in `GremlinQueryCoverageTest`.
Each entity has a stable `key` property used as a surrogate ID in assertions
(RIDs are not fixed across runs).

---

## Projects

| key   | name            | isArchived | lead  |
|-------|-----------------|------------|-------|
| ENG   | Engineering     | false      | Alice |
| OPS   | Operations      | false      | Carol |
| INFRA | Infrastructure  | false      | Bob   |
| ARC   | Archive         | true       | —     |

---

## Users / Employees / Managers

All are stored as `User`; `Employee` and `Manager` are subtypes.

| name  | active | type     | department  | salary | reportsCount |
|-------|--------|----------|-------------|--------|--------------|
| Alice | true   | Employee | Engineering | 100000 | —            |
| Bob   | true   | Employee | Engineering | 90000  | —            |
| Carol | false  | Employee | Operations  | 85000  | —            |
| Dave  | true   | User     | —           | —      | —            |
| Eve   | true   | Manager  | Engineering | 120000 | 5            |

---

## Sprints

| key | name     | state  | velocity | project |
|-----|----------|--------|----------|---------|
| S1  | Sprint 1 | active | 40       | ENG     |
| S2  | Sprint 2 | closed | 35       | ENG     |
| S3  | Sprint 3 | active | 50       | OPS     |

---

## Tags

| name        | color  |
|-------------|--------|
| bug         | red    |
| feature     | blue   |
| performance | orange |

---

## Issues

| key     | summary                              | priority | status      | estimate | project | sprint | assignee | tags        | parent  |
|---------|--------------------------------------|----------|-------------|----------|---------|--------|----------|-------------|---------|
| ENG-1   | Bug: login page crash                | critical | open        | 5        | ENG     | S1     | Alice    | bug         | —       |
| ENG-2   | Bug: dashboard crash                 | high     | open        | 3        | ENG     | S1     | Bob      | bug         | —       |
| ENG-3   | Feature: user login flow             | medium   | in-progress | 8        | ENG     | S1     | Alice    | feature     | —       |
| ENG-4   | Performance issue on login           | high     | resolved    | 2        | ENG     | S2     | Bob      | performance | —       |
| ENG-5   | Add OAuth login support              | low      | open        | 13       | ENG     | —      | Alice    | —           | —       |
| ENG-6   | Fix null pointer crash               | critical | open        | 1        | ENG     | S1     | —        | bug         | —       |
| ENG-7   | Memory leak on startup crash         | high     | in-progress | 5        | ENG     | S2     | Eve      | —           | —       |
| ENG-8   | Improve search performance           | medium   | open        | 8        | ENG     | —      | Bob      | performance | —       |
| ENG-9   | Update dependencies                  | low      | resolved    | 3        | ENG     | —      | —        | —           | —       |
| ENG-10  | Bug: export fails for large datasets | high     | open        | 8        | ENG     | S1     | Alice    | bug         | —       |
| ENG-11  | Feature: dark mode                   | low      | open        | 13       | ENG     | —      | —        | feature     | —       |
| ENG-12  | Subtask: implement login UI          | medium   | in-progress | 3        | ENG     | S1     | Alice    | —           | ENG-3   |
| ENG-13  | Subtask: add login validation        | medium   | open        | 2        | ENG     | S1     | —        | —           | ENG-3   |
| ENG-14  | Subtask: OAuth callback handling     | medium   | open        | 3        | ENG     | —      | —        | —           | ENG-5   |
| OPS-1   | Fix authentication bypass            | critical | in-progress | 2        | OPS     | —      | Carol    | bug         | —       |
| OPS-2   | Add monitoring dashboard             | high     | open        | 5        | OPS     | S3     | Carol    | —           | —       |
| OPS-3   | Database migration script            | medium   | resolved    | 8        | OPS     | —      | Carol    | —           | —       |
| OPS-4   | Bug: report generation crash         | critical | open        | 3        | OPS     | S3     | —        | bug         | —       |
| OPS-5   | Add rate limiting                    | medium   | resolved    | 5        | OPS     | —      | Carol    | —           | —       |
| INFRA-1 | Setup CI pipeline                    | medium   | closed      | 5        | INFRA   | —      | Bob      | —           | —       |
| INFRA-2 | Configure load balancer              | high     | resolved    | 8        | INFRA   | —      | Bob      | —           | —       |
| INFRA-3 | Deploy to staging crash              | high     | open        | 2        | INFRA   | —      | —        | bug         | —       |
| INFRA-4 | Write API documentation              | low      | open        | 5        | INFRA   | —      | —        | —           | —       |
| ARC-1   | Legacy cleanup                       | low      | closed      | 3        | ARC     | —      | —        | —           | —       |

---

## Expected results for selected queries

Spot-check table — full per-query expectations live in the test itself.

| Query | Expected keys |
|-------|---------------|
| Q01 `priority=critical` | ENG-1, ENG-6, OPS-1, OPS-4 |
| Q02 `status=open` | ENG-1, ENG-2, ENG-5, ENG-6, ENG-8, ENG-10, ENG-11, ENG-13, ENG-14, OPS-2, OPS-4, INFRA-3, INFRA-4 |
| Q03 `estimate in [1,8]` | all except ENG-5 (13), ENG-11 (13) |
| Q04 `archived projects` | ARC |
| Q05 `department=Engineering employees` | Alice, Bob, Eve |
| Q07 `summary contains "login"` | ENG-1, ENG-3, ENG-4, ENG-5, ENG-12, ENG-13 |
| Q08 `summary starts with "Bug:"` | ENG-1, ENG-2, ENG-10, OPS-4 |
| Q09 `summary ends with "crash"` | ENG-1, ENG-2, ENG-6, ENG-7, OPS-4, INFRA-3 |
| Q10 `active users` | Alice, Bob, Dave, Eve |
| Q66 `issues whose project lead is in Engineering` | all ENG-* + all INFRA-* (lead=Alice and lead=Bob, both Engineering) |
