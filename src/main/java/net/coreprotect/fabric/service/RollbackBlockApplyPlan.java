package net.coreprotect.fabric.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

final class RollbackBlockApplyPlan<K, E> {
    private final Map<K, List<E>> eventsByPosition = new LinkedHashMap<>();

    void add(K position, E event) {
        eventsByPosition.computeIfAbsent(position, ignored -> new ArrayList<>()).add(event);
    }

    List<Group<E>> orderedGroups(BiFunction<K, List<E>, Phase> classifier) {
        List<Group<E>> air = new ArrayList<>();
        List<Group<E>> stable = new ArrayList<>();
        List<Group<E>> physics = new ArrayList<>();

        for (Map.Entry<K, List<E>> entry : eventsByPosition.entrySet()) {
            Phase phase = classifier.apply(entry.getKey(), entry.getValue());
            Group<E> group = new Group<>(entry.getValue(), phase);
            switch (phase) {
                case AIR -> air.add(group);
                case STABLE -> stable.add(group);
                case PHYSICS -> physics.add(group);
            }
        }

        List<Group<E>> ordered = new ArrayList<>(eventsByPosition.size());
        ordered.addAll(air);
        ordered.addAll(stable);
        ordered.addAll(physics);
        return ordered;
    }

    enum Phase {
        AIR,
        STABLE,
        PHYSICS
    }

    record Group<E>(List<E> events, Phase phase) {
        Group {
            events = List.copyOf(events);
        }
    }
}
