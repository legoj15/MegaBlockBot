package com.legoj15.megablockbot.core.goal;

import com.legoj15.megablockbot.core.ToolTier;
import com.legoj15.megablockbot.core.snapshot.ContainerSight;
import com.legoj15.megablockbot.core.snapshot.StackFacts;
import com.legoj15.megablockbot.core.snapshot.StructureReport;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * The registry mapping each {@link Goal} to its postcondition (design 3.4, Goal). An object, not static
 * state: each body owns one, and tests build their own. Built-in kinds are the table in the design;
 * strategies {@link #register} their own under a kind string.
 */
public final class Postconditions {

    private final Map<String, Function<Goal.Custom, Postcondition>> customKinds = new HashMap<>();

    /** Registers (replaces) the factory for a strategy's goal kind. */
    public void register(String customKind, Function<Goal.Custom, Postcondition> factory) {
        customKinds.put(Objects.requireNonNull(customKind, "customKind"),
                Objects.requireNonNull(factory, "factory"));
    }

    /** The postcondition for {@code goal}; an unregistered custom kind evaluates UNKNOWN, never done. */
    public Postcondition of(Goal goal) {
        return switch (goal) {
            case Goal.Custom c -> {
                Function<Goal.Custom, Postcondition> factory = customKinds.get(c.kind());
                yield factory != null ? factory.apply(c)
                        : (Postcondition) (snapshot -> Evaluation.unknown(0, c.kind()));
            }
            case Goal.HaveItem g -> g.check() == null ? plainItemCount(g) : checkedItemCount(g);
            case Goal.HaveToolTier g -> toolTier(g);
            case Goal.Equip g -> equip(g);
            case Goal.Stockpile g -> stockpile(g);
            case Goal.Build g -> build(g);
            case Goal.Reach g -> reach(g);
        };
    }

    private Postcondition plainItemCount(Goal.HaveItem g) {
        return snapshot -> {
            int have = snapshot.count(g.key());
            return have >= g.count()
                    ? Evaluation.satisfied(g.count())
                    : Evaluation.unsatisfied(Math.min(have, g.count()), g.count(), List.of(g.key().id()));
        };
    }

    /**
     * With a check only stacks whose {@code stackFacts} pass it count. When the snapshot carries the key but no
     * facts for it, the snapshot did not look, so the answer is UNKNOWN -- never "zero", which would let a
     * wrong-component stack hide the miss (design 3.4, Component goals).
     */
    private Postcondition checkedItemCount(Goal.HaveItem g) {
        return snapshot -> {
            List<StackFacts> facts = snapshot.stackFacts().get(g.key());
            if (facts == null) {
                return snapshot.count(g.key()) > 0
                        ? Evaluation.unknown(g.count(), g.key().id())
                        : Evaluation.unsatisfied(0, g.count(), List.of(g.key().id()));
            }
            int have = 0;
            for (var f : facts) {
                if (g.check().test(f)) have += f.count();
            }
            return have >= g.count()
                    ? Evaluation.satisfied(g.count())
                    : Evaluation.unsatisfied(Math.min(have, g.count()), g.count(), List.of(g.key().id()));
        };
    }

    private Postcondition toolTier(Goal.HaveToolTier g) {
        return snapshot -> {
            ToolTier best = snapshot.bestTool(g.toolClass());
            return best.atLeast(g.tier())
                    ? Evaluation.satisfied(1)
                    : Evaluation.unsatisfied(0, 1, List.of(g.toolClass() + ">=" + g.tier()));
        };
    }

    private Postcondition equip(Goal.Equip g) {
        return snapshot -> {
            int matched = 0;
            List<String> unmet = new ArrayList<>();
            for (var e : g.slots().entrySet()) {
                if (e.getValue().equals(snapshot.equipped().get(e.getKey()))) matched++;
                else unmet.add(e.getKey().name());
            }
            return matched == g.slots().size()
                    ? Evaluation.satisfied(g.slots().size())
                    : Evaluation.unsatisfied(matched, g.slots().size(), unmet);
        };
    }

    private Postcondition stockpile(Goal.Stockpile g) {
        return snapshot -> {
            String what = g.key().id() + "@" + g.container().encode();
            if (!snapshot.dimension().equals(g.dimension())) return Evaluation.unknown(g.count(), what);
            ContainerSight sight = snapshot.containerAt(g.container());
            if (sight == null || !sight.contentsKnown()) return Evaluation.unknown(g.count(), what);
            int held = sight.count(g.key());
            return held >= g.count()
                    ? Evaluation.satisfied(g.count())
                    : Evaluation.unsatisfied(Math.min(held, g.count()), g.count(), List.of(g.key().id()));
        };
    }

    private Postcondition build(Goal.Build g) {
        return snapshot -> {
            String key = g.blueprint() + "@" + g.anchor().encode();
            StructureReport report = snapshot.structures().get(key);
            // An unknown report still says how much the blueprint asks for and how much of it stands, so a give-up
            // on it can report both instead of a requirement of zero.
            if (report == null) return Evaluation.unknown(0, key);
            if (!report.known()) {
                return new Evaluation(Evaluation.Status.UNKNOWN, report.matched(), report.required(), List.of(key));
            }
            if (report.matched() >= report.required()) return Evaluation.satisfied(report.required());
            List<String> unmet = new ArrayList<>(new TreeSet<>(report.missing().keySet().stream()
                    .map(k -> k.id()).toList()));
            return Evaluation.unsatisfied(report.matched(), report.required(), unmet);
        };
    }

    /** Horizontal distance within radius; vertical tolerance is looser of the radius and two blocks. */
    private Postcondition reach(Goal.Reach g) {
        return snapshot -> {
            boolean dim = snapshot.dimension().equals(g.dimension());
            long distSqXZ = snapshot.pos().distSqXZ(g.pos());
            int dy = Math.abs(snapshot.pos().y() - g.pos().y());
            boolean inside = dim && distSqXZ <= (long) g.radius() * g.radius() && dy <= Math.max(g.radius(), 2);
            return inside ? Evaluation.satisfied(1)
                    : Evaluation.unsatisfied(0, 1, List.of(g.dimension() + "@" + g.pos().encode()));
        };
    }
}
