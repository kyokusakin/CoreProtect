Feature: Runtime reload safety

  Scenario: Reload is requested while rollback work is queued
    Given the current rollback service has pending server-thread work
    When CoreProtect validates a reload
    Then the reload is rejected before the database is replaced

  Scenario: Candidate collection finishes after a successful reload
    Given a rollback query captured the previous runtime generation
    When a reload installs a new database and service graph
    Then the old query result is considered stale
    And it is not enqueued into the new rollback service

  Scenario: A reload attempt fails before installing new services
    Given a rollback query captured the current runtime generation
    When reload preparation fails
    Then the query result remains valid for the existing runtime

  Scenario: A reload replaces the update checker
    Given the previous update checker owns an executor
    When a new runtime service graph is installed
    Then the previous update checker executor is shut down
