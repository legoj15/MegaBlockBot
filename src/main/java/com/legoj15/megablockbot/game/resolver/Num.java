package com.legoj15.megablockbot.game.resolver;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * An integer-valued loot number provider (a count or a number of rolls) as a probability mass function. Constant,
 * uniform and binomial providers are exact; anything else counts as 1 and is noted. Values at or below zero all mean
 * "nothing drops", which is how the game treats them, so the statistics clamp at zero.
 */
final class Num {
    // Beyond these a provider is replaced by its mean; no vanilla table comes close.
    private static final int MAX_SPAN = 4096;
    private static final int MAX_BINOMIAL_N = 1000;

    // pmf[i] is P(value == lo + i); sums to 1.
    private final int lo;
    private final double[] pmf;

    private Num(int lo, double[] pmf) {
        this.lo = lo;
        this.pmf = pmf;
    }

    static Num constant(int value) {
        return new Num(value, new double[] {1.0});
    }

    /** Uniform over the integers lo..hi, as {@code Mth.nextInt} draws them (a reversed pair always gives lo). */
    static Num uniform(int lo, int hi) {
        if (hi <= lo) return constant(lo);
        if ((long) hi - lo >= MAX_SPAN) return constant((int) (((long) lo + hi) / 2));
        double[] pmf = new double[hi - lo + 1];
        java.util.Arrays.fill(pmf, 1.0 / pmf.length);
        return new Num(lo, pmf);
    }

    static Num binomial(int n, double p) {
        if (n <= 0 || !(p > 0)) return constant(0); // also a NaN chance
        if (p >= 1) return constant(n);
        if (n > MAX_BINOMIAL_N) return constant((int) Math.round(n * p));
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
        return new Num(0, pmf);
    }

    /** Sum of two independent values (a {@code set_count} with {@code add}). */
    Num plus(Num other) {
        long span = (long) pmf.length + other.pmf.length - 1;
        if (span > MAX_SPAN) return constant((int) Math.round(meanRaw() + other.meanRaw()));
        double[] out = new double[(int) span];
        for (int i = 0; i < pmf.length; i++) {
            for (int j = 0; j < other.pmf.length; j++) out[i + j] += pmf[i] * other.pmf[j];
        }
        return new Num(lo + other.lo, out);
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

    /** Reads a number provider as written in a loot table: a bare number, or an object with a {@code type}. */
    static Num parse(JsonElement e, BuildNotes notes) {
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
                return parse(o.get("value"), notes);
            case "uniform": {
                // ConstantValue.getInt rounds a float; the bounds of a nested provider are taken at their mean.
                int lo = (int) Math.round(parse(o.get("min"), notes).meanRaw());
                int hi = (int) Math.round(parse(o.get("max"), notes).meanRaw());
                return uniform(lo, hi);
            }
            case "binomial": {
                int n = (int) Math.round(parse(o.get("n"), notes).meanRaw());
                return binomial(n, floatValue(o.get("p"), notes));
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
