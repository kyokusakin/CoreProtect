Feature: Non-blocking database reference integrity at server startup

  Scenario: Starting with a legacy database
    Given a legacy SQLite database needs full-table integrity maintenance
    When CoreProtect starts its database
    Then required schema setup completes before startup returns
    And full integrity maintenance is queued on the database writer
    And event writes remain ordered after that maintenance

  Scenario: Restarting with enforced foreign keys
    Given a populated SQLite database with every required foreign key
    When scheduled maintenance checks reference integrity
    Then no bulk repair statement is executed

  Scenario: Upgrading a legacy schema with a missing foreign key
    Given a populated SQLite database without the target reference constraint
    And an event contains an orphaned target reference
    When scheduled maintenance checks reference integrity
    Then the orphaned target reference is cleared
    And relations that already have foreign keys are not repaired again

  Scenario: Preparing a legacy schema for integrity repair
    Given a SQLite schema without child reference indexes
    When CoreProtect prepares the reference integrity migration
    Then every child reference used by the migration is indexed

  Scenario: A replacement database fails during reload startup
    Given the replacement SQLite file is corrupt
    When CoreProtect attempts to initialize its schema
    Then startup fails
    And the replacement database connection is closed
