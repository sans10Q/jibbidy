package com.lanterns;

import java.util.*;

/** Pure game rules. No Minecraft imports so they can be unit-tested anywhere. */
public final class GameLogic {
    private GameLogic() {}

    public static final UUID SKIP = new UUID(0L, 0L);

    public enum Winner { NONE, INNOCENTS, MANHUNTERS }

    public record TallyResult(UUID ejected, Map<UUID, Integer> counts, int skips) {}

    /** Manhunter count clamped so they can never start with parity: at least 1, at most (n-1)/2. */
    public static int manhunterCount(int players, int configured) {
        int max = Math.max(1, (players - 1) / 2);
        return Math.max(1, Math.min(configured, max));
    }

    public static Set<UUID> assignManhunters(Collection<UUID> players, int count, Random rng) {
        return assignManhunters(players, count, Set.of(), Set.of(), rng);
    }

    /**
     * Admin-forced roles: players in forcedYes are Manhunters (up to count), players in forcedNo are
     * never Manhunters unless there is literally nobody else. The rest is random.
     */
    public static Set<UUID> assignManhunters(Collection<UUID> players, int count, Set<UUID> forcedYes,
                                             Set<UUID> forcedNo, Random rng) {
        Set<UUID> chosen = new LinkedHashSet<>();
        for (UUID id : players)
            if (forcedYes.contains(id) && !forcedNo.contains(id) && chosen.size() < count) chosen.add(id);
        List<UUID> pool = new ArrayList<>();
        List<UUID> last = new ArrayList<>();
        for (UUID id : players) {
            if (chosen.contains(id)) continue;
            (forcedNo.contains(id) ? last : pool).add(id);
        }
        Collections.shuffle(pool, rng);
        Collections.shuffle(last, rng);
        pool.addAll(last);
        for (UUID id : pool) { if (chosen.size() >= count) break; chosen.add(id); }
        return chosen;
    }

    /** Living Manhunters vs ALL living non-Manhunters (the Lantern counts as innocent). */
    public static Winner checkWin(int livingManhunters, int livingNonManhunters) {
        if (livingManhunters <= 0) return Winner.INNOCENTS;
        if (livingManhunters >= livingNonManhunters) return Winner.MANHUNTERS;
        return Winner.NONE;
    }

    /**
     * Ties and skip-ties produce no elimination. Votes from non-living voters and
     * votes for non-living targets are ignored. Abstaining (no vote) is not a skip.
     */
    public static TallyResult tally(Map<UUID, UUID> votes, Set<UUID> living) {
        Map<UUID, Integer> counts = new LinkedHashMap<>();
        for (UUID id : living) counts.put(id, 0);
        int skips = 0;
        for (Map.Entry<UUID, UUID> e : votes.entrySet()) {
            if (!living.contains(e.getKey())) continue;
            UUID t = e.getValue();
            if (SKIP.equals(t)) skips++;
            else if (counts.containsKey(t)) counts.merge(t, 1, Integer::sum);
        }
        int max = 0;
        for (int c : counts.values()) max = Math.max(max, c);
        if (max == 0 || skips >= max) return new TallyResult(null, counts, skips);
        UUID top = null;
        for (Map.Entry<UUID, Integer> e : counts.entrySet()) {
            if (e.getValue() == max) {
                if (top != null) return new TallyResult(null, counts, skips); // tie
                top = e.getKey();
            }
        }
        return new TallyResult(top, counts, skips);
    }

    /** Ring candidate: random living non-Manhunter, never the excluded player. */
    public static Optional<UUID> pickRingCandidate(Collection<UUID> living, Set<UUID> manhunters,
                                                   UUID exclude, Random rng, UUID preferred) {
        if (preferred != null && living.contains(preferred) && !manhunters.contains(preferred) && !preferred.equals(exclude))
            return Optional.of(preferred);
        return pickRingCandidate(living, manhunters, exclude, rng);
    }

    public static Optional<UUID> pickRingCandidate(Collection<UUID> living, Set<UUID> manhunters,
                                                   UUID exclude, Random rng) {
        List<UUID> pool = new ArrayList<>();
        for (UUID id : living) {
            if (!manhunters.contains(id) && !id.equals(exclude)) pool.add(id);
        }
        if (pool.isEmpty()) return Optional.empty();
        return Optional.of(pool.get(rng.nextInt(pool.size())));
    }

    public static long remainingSeconds(long readyAtTick, long nowTick) {
        long left = readyAtTick - nowTick;
        return left <= 0 ? 0 : (left + 19) / 20;
    }
}
