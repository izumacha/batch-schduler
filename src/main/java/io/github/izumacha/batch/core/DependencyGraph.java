package io.github.izumacha.batch.core;

import io.github.izumacha.batch.config.ValidationException;
import io.github.izumacha.batch.model.Batch;
import io.github.izumacha.batch.model.Job;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * A validated, acyclic view of the jobs in a {@link Batch} and their
 * dependencies. Construct one with {@link #build(Batch)}, which performs all
 * structural validation up front and throws a single
 * {@link ValidationException} carrying every detected problem. Once built, the
 * query methods operate on already-validated state and never fail.
 */
public final class DependencyGraph {

    private final Batch batch;
    /** jobId -> validated set of dependency ids (declaration order preserved). */
    private final Map<String, Set<String>> dependencies;
    /** jobId -> declaration index, used for deterministic tie-breaking. */
    private final Map<String, Integer> declarationIndex;

    private DependencyGraph(Batch batch,
                            Map<String, Set<String>> dependencies,
                            Map<String, Integer> declarationIndex) {
        this.batch = batch;
        this.dependencies = dependencies;
        this.declarationIndex = declarationIndex;
    }

    /**
     * Validates the given batch and builds a dependency graph from it. Collects
     * every structural problem and, if any are found, throws a single
     * {@link ValidationException} containing all of them.
     */
    public static DependencyGraph build(Batch batch) {
        if (batch == null) {
            throw new ValidationException(List.of("batch is null"));
        }

        List<String> errors = new ArrayList<>();
        List<Job> jobs = batch.jobs();

        if (jobs.isEmpty()) {
            errors.add("batch contains no jobs");
            throw new ValidationException(errors);
        }

        // Detect duplicate ids and record declaration order of the first occurrence.
        Map<String, Integer> declarationIndex = new LinkedHashMap<>();
        Set<String> reportedDuplicates = new HashSet<>();
        for (int i = 0; i < jobs.size(); i++) {
            String id = jobs.get(i).id();
            if (declarationIndex.containsKey(id)) {
                if (reportedDuplicates.add(id)) {
                    errors.add("duplicate job id: '" + id + "'");
                }
            } else {
                declarationIndex.put(id, i);
            }
        }

        // Per-job checks: empty command, unknown deps, self-deps.
        Map<String, Set<String>> dependencies = new LinkedHashMap<>();
        for (Job job : jobs) {
            String id = job.id();
            // Only build the dependency map for the first declaration of an id.
            if (dependencies.containsKey(id)) {
                continue;
            }
            if (job.command().isEmpty()) {
                errors.add("job '" + id + "' has an empty command");
            }
            Set<String> deps = new LinkedHashSet<>();
            for (String dep : job.dependsOn()) {
                if (dep.equals(id)) {
                    errors.add("job '" + id + "' depends on itself");
                    continue;
                }
                if (!declarationIndex.containsKey(dep)) {
                    errors.add("job '" + id + "' depends on unknown job '" + dep + "'");
                    continue;
                }
                deps.add(dep);
            }
            dependencies.put(id, deps);
        }

        // Cycle detection only makes sense once edges are known to be valid.
        // Run it on the dependency map we built (which excludes self/unknown deps).
        detectCycles(declarationIndex, dependencies, errors);

        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }

        return new DependencyGraph(batch, dependencies, declarationIndex);
    }

    private static void detectCycles(Map<String, Integer> declarationIndex,
                                     Map<String, Set<String>> dependencies,
                                     List<String> errors) {
        // 0 = unvisited, 1 = in progress (on the current DFS path), 2 = done.
        // Iterative DFS (explicit stacks) so a deeply-nested or very long
        // dependency chain cannot overflow the call stack.
        Map<String, Integer> state = new HashMap<>();
        Set<String> reportedCycles = new HashSet<>();
        // Iterate in declaration order for deterministic cycle reporting.
        for (String start : declarationIndex.keySet()) {
            if (state.getOrDefault(start, 0) != 0) {
                continue;
            }
            Deque<Iterator<String>> iterators = new ArrayDeque<>();
            Deque<String> path = new ArrayDeque<>(); // top = node currently being explored

            state.put(start, 1);
            path.push(start);
            iterators.push(dependencies.getOrDefault(start, Set.of()).iterator());

            while (!iterators.isEmpty()) {
                Iterator<String> it = iterators.peek();
                if (it.hasNext()) {
                    String dep = it.next();
                    int depState = state.getOrDefault(dep, 0);
                    if (depState == 0) {
                        state.put(dep, 1);
                        path.push(dep);
                        iterators.push(dependencies.getOrDefault(dep, Set.of()).iterator());
                    } else if (depState == 1) {
                        // Back-edge: dep is on the current path. Extract the cycle.
                        reportCycle(dep, path, reportedCycles, errors);
                    }
                } else {
                    iterators.pop();
                    state.put(path.pop(), 2);
                }
            }
        }
    }

    /** Records the cycle closed by a back-edge to {@code dep} from the current DFS path. */
    private static void reportCycle(String dep,
                                    Deque<String> path,
                                    Set<String> reportedCycles,
                                    List<String> errors) {
        // path is top-first: [current, ..., dep, ...]. Collect current..dep, then
        // reverse to dep..current and close the loop with dep.
        List<String> collected = new ArrayList<>();
        for (String node : path) {
            collected.add(node);
            if (node.equals(dep)) {
                break;
            }
        }
        List<String> cycle = new ArrayList<>(collected.size() + 1);
        for (int i = collected.size() - 1; i >= 0; i--) {
            cycle.add(collected.get(i));
        }
        cycle.add(dep);
        if (reportedCycles.add(canonicalCycleKey(cycle))) {
            errors.add("dependency cycle detected: " + String.join(" -> ", cycle));
        }
    }

    /** Produces a rotation-independent key so a cycle is reported only once. */
    private static String canonicalCycleKey(List<String> path) {
        // Drop the duplicated closing node, then rotate so the smallest id leads.
        List<String> nodes = new ArrayList<>(path.subList(0, path.size() - 1));
        if (nodes.isEmpty()) {
            return "";
        }
        int minIdx = 0;
        for (int i = 1; i < nodes.size(); i++) {
            if (nodes.get(i).compareTo(nodes.get(minIdx)) < 0) {
                minIdx = i;
            }
        }
        List<String> rotated = new ArrayList<>(nodes.size());
        for (int i = 0; i < nodes.size(); i++) {
            rotated.add(nodes.get((minIdx + i) % nodes.size()));
        }
        return String.join("->", rotated);
    }

    /**
     * Returns the jobs in a deterministic topological order (Kahn's algorithm).
     * When several jobs are simultaneously ready, they are emitted in original
     * declaration order.
     */
    public List<Job> topologicalOrder() {
        Map<String, Integer> inDegree = new LinkedHashMap<>();
        // dependents.get(x) = jobs that depend on x.
        Map<String, List<String>> dependents = new LinkedHashMap<>();
        for (String id : declarationIndex.keySet()) {
            inDegree.put(id, 0);
            dependents.put(id, new ArrayList<>());
        }
        for (Map.Entry<String, Set<String>> e : dependencies.entrySet()) {
            String id = e.getKey();
            for (String dep : e.getValue()) {
                inDegree.merge(id, 1, Integer::sum);
                dependents.get(dep).add(id);
            }
        }

        // Ready set ordered by declaration index for determinism. A priority
        // queue keeps the ordering as nodes become ready without re-sorting and
        // without O(n) head removals.
        PriorityQueue<String> ready =
                new PriorityQueue<>(Comparator.comparingInt(declarationIndex::get));
        for (String id : declarationIndex.keySet()) {
            if (inDegree.get(id) == 0) {
                ready.add(id);
            }
        }

        List<Job> order = new ArrayList<>();
        while (!ready.isEmpty()) {
            String id = ready.poll();
            order.add(batch.job(id).orElseThrow());
            for (String dependent : dependents.get(id)) {
                if (inDegree.merge(dependent, -1, Integer::sum) == 0) {
                    ready.add(dependent);
                }
            }
        }

        // Graph is validated acyclic, so this always holds.
        if (order.size() != declarationIndex.size()) {
            throw new IllegalStateException("topological sort failed on a validated graph");
        }
        return order;
    }

    /** The validated set of dependency ids for the given job. */
    public Set<String> dependenciesOf(String jobId) {
        Set<String> deps = dependencies.get(jobId);
        if (deps == null) {
            throw new IllegalArgumentException("unknown job id: '" + jobId + "'");
        }
        return Set.copyOf(deps);
    }

    /** The batch this graph was built from. */
    public Batch batch() {
        return batch;
    }
}
