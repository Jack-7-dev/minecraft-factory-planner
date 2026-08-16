package dev.mfp.core.solver;

import dev.mfp.core.model.MfpKey;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A mutable bag of per-second rates, plus the balancing primitive the solver is built on.
 *
 * <p>Rates only, always per second (plan P2).
 */
public final class ItemFlows {

    /** Rates below this are treated as zero, so floating-point residue never becomes a demand. */
    public static final double EPSILON = 1e-9;

    private final Map<MfpKey, Double> amounts = new LinkedHashMap<>();

    public void add(MfpKey key, double amount) {
        if (amount <= EPSILON) {
            return;
        }
        amounts.merge(key, amount, Double::sum);
    }

    /** Remove up to {@code amount}, returning how much was actually taken. */
    public double take(MfpKey key, double amount) {
        Double present = amounts.get(key);
        if (present == null || amount <= EPSILON) {
            return 0.0;
        }
        double taken = Math.min(present, amount);
        double left = present - taken;
        if (left <= EPSILON) {
            amounts.remove(key);
        } else {
            amounts.put(key, left);
        }
        return taken;
    }

    public double get(MfpKey key) {
        return amounts.getOrDefault(key, 0.0);
    }

    public boolean has(MfpKey key) {
        return amounts.getOrDefault(key, 0.0) > EPSILON;
    }

    public boolean isEmpty() {
        return amounts.isEmpty();
    }

    public Map<MfpKey, Double> asMap() {
        return new LinkedHashMap<>(amounts);
    }

    public java.util.Set<MfpKey> keys() {
        return new java.util.LinkedHashSet<>(amounts.keySet());
    }

    public void addAll(ItemFlows other) {
        other.amounts.forEach(this::add);
    }

    /**
     * Satisfy {@code wanted} from {@code depot} first, sending whatever is left to {@code shortfall}.
     *
     * <p>This is Factory Planner's {@code balance_items}, and it is the whole reason byproducts get
     * reused instead of being thrown away and re-made. Every time a line needs something, it looks
     * in the pile of surplus produced by lines already processed; only the unmet remainder becomes
     * a demand that something further down the plan has to satisfy.
     */
    public static void balance(ItemFlows wanted, ItemFlows depot, ItemFlows shortfall) {
        for (Map.Entry<MfpKey, Double> entry : wanted.asMap().entrySet()) {
            MfpKey key = entry.getKey();
            double needed = entry.getValue();
            double fromDepot = depot.take(key, needed);
            double remaining = needed - fromDepot;
            if (remaining > EPSILON) {
                shortfall.add(key, remaining);
            }
        }
    }
}
