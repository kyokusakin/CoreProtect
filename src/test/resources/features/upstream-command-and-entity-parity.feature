Feature: Upstream command and entity rollback parity

  Scenario Outline: Lookup-only flags cannot start a rollback or restore
    Given a rollback or restore command containing "<flag>"
    When CoreProtect validates the command
    Then the parameter "<flag>" is rejected before any world mutation starts

    Examples:
      | flag       |
      | #count     |
      | #sum       |
      | #summary   |
      | count      |
      | sum        |

  Scenario: Rollback completion excludes lookup-only flags
    Given a player is entering rollback parameters
    When CoreProtect suggests hashtag flags
    Then count and summary flags are not suggested

  Scenario: Invalid villager levels are restored safely
    Given a logged villager has a profession level above the vanilla maximum
    When CoreProtect sanitizes its NBT for rollback
    Then the restored profession level is capped at 5

  Scenario: Passive mob visual variants survive rollback sanitization
    Given logged entity NBT contains visual and sound variant keys
    When CoreProtect sanitizes its NBT for rollback
    Then those variant keys remain unchanged
