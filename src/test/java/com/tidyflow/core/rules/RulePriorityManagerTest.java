package com.tidyflow.core.rules;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RulePriorityManagerTest {

    @Test
    void moveUpAndMoveDownRenumberSequentiallyAndNeverProduceDuplicatePriorities() {
        List<Rule> initial = DefaultRules.create(); // priorities 10, 20, 30, 40, 50
        List<Rule> normalized = RulePriorityManager.normalizePriorities(initial);

        assertEquals(List.of(1, 2, 3, 4, 5), normalized.stream().map(Rule::priority).toList());
        assertEquals("Images", normalized.get(0).name());
        assertEquals("Documents", normalized.get(1).name());

        // Move "Documents" (index 1) up to index 0
        List<Rule> afterMoveUp = RulePriorityManager.moveUp(normalized, 1);
        assertEquals("Documents", afterMoveUp.get(0).name());
        assertEquals("Images", afterMoveUp.get(1).name());
        assertSequentialUniquePriorities(afterMoveUp);

        // Move "Documents" (now index 0) down to index 1
        List<Rule> afterMoveDown = RulePriorityManager.moveDown(afterMoveUp, 0);
        assertEquals("Images", afterMoveDown.get(0).name());
        assertEquals("Documents", afterMoveDown.get(1).name());
        assertSequentialUniquePriorities(afterMoveDown);
    }

    private static void assertSequentialUniquePriorities(List<Rule> rules) {
        Set<Integer> priorities = new HashSet<>();
        for (int i = 0; i < rules.size(); i++) {
            assertEquals(i + 1, rules.get(i).priority());
            priorities.add(rules.get(i).priority());
        }
        assertEquals(rules.size(), priorities.size());
    }
}
