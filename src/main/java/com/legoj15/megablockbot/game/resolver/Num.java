package com.legoj15.megablockbot.game.resolver;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * An integer-valued loot number provider (a count or a number of rolls) as a probability mass function. Constant,
 * uniform and binomial providers are exact; anything else counts as 1 and is noted. Values at or below zero all mean
 * "nothing drops", which is how the game treats them, so the statistics clamp at zero. Three cases are too big to
 * enumerate and are replaced by something smaller, each remembered in {@link #approximation()} so that the caller, who
 * knows which table the number came from, can note it: a binomial keeps its mean (n times p), a uniform span is
 * replaced by its midpoint, and the sum of two spans by the sum of their means.
 */
final class Num {
    // Beyond these a provider is replaced by its mean; no vanilla table comes close.
    private static final int MAX_SPAN = 4096;
    private static final int MAX_BINOMIAL_N = 1000;

    private static final String WIDE_BINOMIAL = "binomial loot counts too big to enumerate (n above " + MAX_BINOMIAL_N + "), replaced by their mean";
    private static final String WIDE_UNIFORM = "uniform loot counts spanning " + MAX_SPAN + " values or more, replaced by their midpoint";
    private static final String WIDE_SUM = "sums of loot counts too wide to enumerate (set_count with add), replaced by the sum of their means";

    // pmf[i] is P(value == lo + i); sums to 1.
    private final int lo;
    private final double[] pmf;
    // What this value replaced, when it is not exact; null otherwise. A value derived from one that was replaced does
    // not carry it on: the place that built the first one has noted it.
    private final String approximation;

    private Num(int lo, double[] pmf, String approximation) {
        this.lo = lo;
        this.pmf = pmf;
        this.approximation = approximation;
    }

    static Num constant(int value) {
        return new Num(value, new double[] {1.0}, null);
    }

    /** Uniform over the integers lo..hi, as {@code Mth.nextInt} draws them (a reversed pair always gives lo). */
    static Num uniform(int lo, int hi) {
        if (hi <= lo) return constant(lo);
        if ((long) hi - lo >= MAX_SPAN) return new Num((int) (((long) lo + hi) / 2), new double[] {1.0}, WIDE_UNIFORM);
        double[] pmf = new double[hi - lo + 1];
        java.util.Arrays.fill(pmf, 1.0 / pmf.length);
        return new Num(lo, pmf, null);
    }

    /**
     * A value with the given mean and nothing else known of it: the whole number below it, or the one above with the
     * probability of the fraction. For a provider too big to enumerate, whose mean needs no enumeration. Rounding the
     * mean to one whole number would turn a mean of 0.2 into a value that never drops anything.
     */
    static Num ofMean(double mean) {
        if (!(mean > 0)) return constant(0); // also a NaN
        double floor = Math.floor(mean);
        double fraction = mean - floor;
        if (floor >= Integer.MAX_VALUE) return constant(Integer.MAX_VALUE);
        if (fraction < 1e-12) return constant((int) floor);
        return new Num((int) floor, new double[] {1 - fraction, fraction}, null);
    }

    static Num binomial(int n, double p) {
        if (n <= 0 || !(p > 0)) return constant(0); // also a NaN chance
        if (p >= 1) return constant(n);
        if (n > MAX_BINOMIAL_N) return ofMean(n * p).replacing(WIDE_BINOMIAL);
        // Built from the ratio of neighbouring terms, in log space: the first term, (1-p)^n, underflows a double well
        // inside the cap (0.1^500), and a running product that starts at zero stays zero. The largest term is
        // factored out before exponentiating, and the terms are normalized, which also absorbs the rounding.
        double[] logs = new double[n + 1];
        double logOdds = Math.log(p) - Math.log1p(-p);
        logs[0] = n * Math.log1p(-p);
        double max = logs[0];
        for (int k = 1; k <= n; k++) {
            logs[k] = logs[k - 1] + Math.log((double) (n - k + 1) / k) + logOdds;
            max = Math.max(max, logs[k]);
        }
        double[] pmf = new double[n + 1];
        double sum = 0;
        for (int k = 0; k <= n; k++) {
            pmf[k] = Math.exp(logs[k] - max);
            sum += pmf[k];
        }
        for (int k = 0; k <= n; k++) pmf[k] /= sum;
        return new Num(0, pmf, null);
    }

    private Num replacing(String what) {
        return new Num(lo, pmf, what);
    }

    /** What this value stands in for when the provider was too big to enumerate, or null when it is exact. */
    String approximation() {
        return approximation;
    }

    /** Notes under {@code where} (the table the number was read in) what this value replaced, if it replaced anything. */
    Num noted(BuildNotes notes, String where) {
        if (approximation != null) notes.skipped(approximation, where);
        return this;
    }

    /** Sum of two independent values (a {@code set_count} with {@code add}). */
    Num plus(Num other) {
        long span = (long) pmf.length + other.pmf.length - 1;
        if (span > MAX_SPAN) return new Num((int) Math.round(meanRaw() + other.meanRaw()), new double[] {1.0}, WIDE_SUM);
        double[] out = new double[(int) span];
        for (int i = 0; i < pmf.length; i++) {
            for (int j = 0; j < other.pmf.length; j++) out[i + j] += pmf[i] * other.pmf[j];
        }
        return new Num(lo + other.lo, out, null);
    }

    /** Mean without clamping, for providers nested inside another provider's bounds. */
    double meanRaw() {
        double mean = 0;
        for (int i = 0; i < pmf.length; i++) mean += (lo + i) * pmf[i];
        return mean;
    }

    /** Expected number of items: the mean of the value clamped at zero. */
    double mean() {
        double mean = 0;
        for (int i = 0; i < pmf.length; i++) mean += Math.max(0, lo + i) * pmf[i];
        return mean;
    }

    /** Probability that at least one item drops. */
    double pPositive() {
        double p = 0;
        for (int i = 0; i < pmf.length; i++) if (lo + i > 0) p += pmf[i];
        return p;
    }

    /**
     * As a number of rolls: the probability that at least one of the rolls succeeds when each does with probability
     * {@code perRoll}.
     */
    double hitOverRolls(double perRoll) {
        double hit = 0;
        for (int i = 0; i < pmf.length; i++) {
            int rolls = Math.max(0, lo + i);
            if (rolls > 0) hit += pmf[i] * (1 - Math.pow(1 - perRoll, rolls));
        }
        return hit;
    }

    /**
     * Reads a number provider as written in a loot table: a bare number, or an object with a {@code type}. {@code where}
     * is the table it is in, which a note about the provider names.
     */
    static Num parse(JsonElement e, BuildNotes notes, String where) {
        if (e == null || e.isJsonNull()) return constant(1);
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) return constant((int) Math.round(e.getAsDouble()));
        if (!e.isJsonObject()) {
            notes.skipped("loot number providers not evaluated (counted as 1)", "(not a number or object)");
            return constant(1);
        }
        JsonObject o = e.getAsJsonObject();
        String type = LootEval.strip(LootEval.string(o, "type"));
        switch (type) {
            case "constant":
                return parse(o.get("value"), notes, where);
            case "uniform": {
                // ConstantValue.getInt rounds a float; the bounds of a nested provider are taken at their mean.
                int lo = (int) Math.round(parse(o.get("min"), notes, where).meanRaw());
                int hi = (int) Math.round(parse(o.get("max"), notes, where).meanRaw());
                return uniform(lo, hi).noted(notes, where);
            }
            case "binomial": {
                int n = (int) Math.round(parse(o.get("n"), notes, where).meanRaw());
                double p = floatValue(o.get("p"), notes);
                return binomial(n, p).noted(notes, where);
            }
            default:
                notes.skipped("loot number providers not evaluated (counted as 1)", type.isEmpty() ? "?" : type);
                return constant(1);
        }
    }

    /** Reads a number provider as a float (a chance), where rounding to an integer would destroy it. */
    static double floatValue(JsonElement e, BuildNotes notes) {
        if (e == null || e.isJsonNull()) return 0;
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) return e.getAsDouble();
        if (e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            String type = LootEval.strip(LootEval.string(o, "type"));
            if (type.equals("constant")) return floatValue(o.get("value"), notes);
            if (type.equals("uniform")) return (floatValue(o.get("min"), notes) + floatValue(o.get("max"), notes)) / 2;
            notes.skipped("loot chance providers not evaluated (counted as 1)", type.isEmpty() ? "?" : type);
            return 1;
        }
        notes.skipped("loot chance providers not evaluated (counted as 1)", "(not a number or object)");
        return 1;
    }
}
