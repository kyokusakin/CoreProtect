Feature: Stable block physics during rollback

  Scenario: A rollback removes a block
    Given the target rollback state is air
    When CoreProtect applies the target state
    Then neighbor physics are disabled for that update

  Scenario: A rollback places a regular block
    Given the target rollback state is not air
    When CoreProtect applies the target state
    Then neighbor physics remain enabled for that update

  Scenario: A chunk contains supports and physics-dependent blocks
    Given a rollback chunk contains a solid support and an attached block
    When CoreProtect plans the chunk rollback
    Then air removals are applied without physics first
    And solid supports are staged without physics second
    And physics-dependent blocks are applied with physics last

  Scenario: Multiple history rows affect the same coordinate
    Given several rollback events target the same block position
    When CoreProtect assigns that position to an apply phase
    Then those events remain adjacent
    And their rollback order is preserved
