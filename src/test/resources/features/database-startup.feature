Feature: Database reference integrity at server startup

  Scenario: Restarting with enforced foreign keys
    Given a populated SQLite database with every required foreign key
    When CoreProtect checks reference integrity during startup
    Then no bulk repair statement is executed

  Scenario: Upgrading a legacy schema with a missing foreign key
    Given a populated SQLite database without the target reference constraint
    And an event contains an orphaned target reference
    When CoreProtect checks reference integrity during startup
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
