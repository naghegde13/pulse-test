package com.pulse.e2e.coverage;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class ScenarioMatrixGenerator {

    public List<ScenarioCase> generate(List<ScenarioDimension> dimensions, int strength) {
        var normalized = List.copyOf(dimensions);
        if (normalized.isEmpty()) {
            return List.of();
        }
        if (strength < 1 || strength > normalized.size()) {
            throw new IllegalArgumentException("strength must be between 1 and number of dimensions");
        }

        var candidates = enumerateCandidates(normalized);
        var uncovered = enumerateRequiredTuples(normalized, strength);
        if (uncovered.isEmpty()) {
            return List.of();
        }

        var selected = new ArrayList<Map<String, String>>();
        while (!uncovered.isEmpty()) {
            Map<String, String> best = null;
            int bestCoverage = -1;
            for (var candidate : candidates) {
                int coverage = coverageOf(candidate, uncovered);
                if (coverage > bestCoverage) {
                    bestCoverage = coverage;
                    best = candidate;
                }
            }
            if (best == null || bestCoverage <= 0) {
                throw new IllegalStateException("Unable to cover remaining tuples: " + uncovered.size());
            }
            selected.add(best);
            var selectedCandidate = best;
            uncovered.removeIf(tuple -> tuple.matches(selectedCandidate));
        }

        return IntStream.range(0, selected.size())
                .mapToObj(index -> new ScenarioCase(
                        "case-%02d".formatted(index + 1),
                        selected.get(index)))
                .toList();
    }

    public Set<CoverageTuple> coveredTuples(List<ScenarioCase> scenarioCases, int strength) {
        if (scenarioCases.isEmpty()) {
            return Set.of();
        }
        var dimensionNames = new ArrayList<>(scenarioCases.get(0).selections().keySet());
        var indexCombos = chooseIndices(dimensionNames.size(), strength);
        return scenarioCases.stream()
                .flatMap(scenarioCase -> indexCombos.stream().map(combo -> tupleForScenario(scenarioCase, dimensionNames, combo)))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public int cartesianProductSize(List<ScenarioDimension> dimensions) {
        return dimensions.stream()
                .mapToInt(dimension -> dimension.values().size())
                .reduce(1, Math::multiplyExact);
    }

    private List<Map<String, String>> enumerateCandidates(List<ScenarioDimension> dimensions) {
        var candidates = new ArrayList<Map<String, String>>();
        enumerate(dimensions, 0, new LinkedHashMap<>(), candidates);
        return candidates;
    }

    private void enumerate(List<ScenarioDimension> dimensions,
                           int index,
                           LinkedHashMap<String, String> current,
                           List<Map<String, String>> output) {
        if (index == dimensions.size()) {
            output.add(Map.copyOf(current));
            return;
        }
        var dimension = dimensions.get(index);
        for (String value : dimension.values()) {
            current.put(dimension.name(), value);
            enumerate(dimensions, index + 1, current, output);
        }
        current.remove(dimension.name());
    }

    private Set<CoverageTuple> enumerateRequiredTuples(List<ScenarioDimension> dimensions, int strength) {
        var required = new LinkedHashSet<CoverageTuple>();
        var combos = chooseIndices(dimensions.size(), strength);
        for (var combo : combos) {
            enumerateTupleValues(dimensions, combo, 0, new LinkedHashMap<>(), required);
        }
        return required;
    }

    private void enumerateTupleValues(List<ScenarioDimension> dimensions,
                                      int[] indices,
                                      int position,
                                      LinkedHashMap<String, String> current,
                                      Set<CoverageTuple> output) {
        if (position == indices.length) {
            output.add(CoverageTuple.of(current));
            return;
        }
        var dimension = dimensions.get(indices[position]);
        for (String value : dimension.values()) {
            current.put(dimension.name(), value);
            enumerateTupleValues(dimensions, indices, position + 1, current, output);
        }
        current.remove(dimension.name());
    }

    private int coverageOf(Map<String, String> candidate, Set<CoverageTuple> uncovered) {
        int count = 0;
        for (CoverageTuple tuple : uncovered) {
            if (tuple.matches(candidate)) {
                count++;
            }
        }
        return count;
    }

    private CoverageTuple tupleForScenario(ScenarioCase scenarioCase, List<String> dimensionNames, int[] indices) {
        var tuple = new LinkedHashMap<String, String>();
        for (int index : indices) {
            var name = dimensionNames.get(index);
            tuple.put(name, scenarioCase.selections().get(name));
        }
        return CoverageTuple.of(tuple);
    }

    private List<int[]> chooseIndices(int total, int strength) {
        var combos = new ArrayList<int[]>();
        choose(total, strength, 0, 0, new int[strength], combos);
        return combos;
    }

    private void choose(int total, int strength, int start, int depth, int[] current, List<int[]> output) {
        if (depth == strength) {
            output.add(Arrays.copyOf(current, current.length));
            return;
        }
        for (int i = start; i <= total - (strength - depth); i++) {
            current[depth] = i;
            choose(total, strength, i + 1, depth + 1, current, output);
        }
    }

    public record ScenarioCase(String id, Map<String, String> selections) {
        public ScenarioCase {
            selections = Map.copyOf(selections);
        }
    }

    public record CoverageTuple(Map<String, String> selections) {
        public CoverageTuple {
            selections = Map.copyOf(selections);
        }

        static CoverageTuple of(Map<String, String> selections) {
            return new CoverageTuple(new LinkedHashMap<>(selections));
        }

        boolean matches(Map<String, String> candidate) {
            return selections.entrySet().stream()
                    .allMatch(entry -> Objects.equals(candidate.get(entry.getKey()), entry.getValue()));
        }
    }
}
