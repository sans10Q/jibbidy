package com.lanterns;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class GameLogicTest {
    private static List<UUID> ids(int n) { List<UUID> l = new ArrayList<>(); for (int i = 0; i < n; i++) l.add(UUID.randomUUID()); return l; }

    @Test void manhunterCountIsClamped() {
        assertEquals(2, GameLogic.manhunterCount(6, 2));
        assertEquals(2, GameLogic.manhunterCount(12, 2));
        assertEquals(1, GameLogic.manhunterCount(3, 2));
        assertEquals(1, GameLogic.manhunterCount(4, 5));
        assertEquals(1, GameLogic.manhunterCount(10, 0));
    }

    @Test void roleAssignmentPicksExactCountFromPlayers() {
        for (int i = 0; i < 200; i++) {
            List<UUID> p = ids(10);
            Set<UUID> m = GameLogic.assignManhunters(p, 2, new Random(i));
            assertEquals(2, m.size());
            assertTrue(p.containsAll(m));
        }
    }

    @Test void winConditions() {
        assertEquals(GameLogic.Winner.INNOCENTS, GameLogic.checkWin(0, 5));
        assertEquals(GameLogic.Winner.MANHUNTERS, GameLogic.checkWin(2, 2));
        assertEquals(GameLogic.Winner.MANHUNTERS, GameLogic.checkWin(2, 1));
        assertEquals(GameLogic.Winner.NONE, GameLogic.checkWin(2, 3));
        assertEquals(GameLogic.Winner.NONE, GameLogic.checkWin(1, 2));
    }

    @Test void clearMajorityEjects() {
        List<UUID> p = ids(5); Set<UUID> living = new LinkedHashSet<>(p);
        Map<UUID, UUID> v = new HashMap<>();
        v.put(p.get(0), p.get(4)); v.put(p.get(1), p.get(4)); v.put(p.get(2), p.get(3)); v.put(p.get(3), GameLogic.SKIP);
        assertEquals(p.get(4), GameLogic.tally(v, living).ejected());
    }

    @Test void tiesProduceNoElimination() {
        List<UUID> p = ids(4); Set<UUID> living = new LinkedHashSet<>(p);
        Map<UUID, UUID> v = new HashMap<>();
        v.put(p.get(0), p.get(2)); v.put(p.get(1), p.get(3));
        assertNull(GameLogic.tally(v, living).ejected());
    }

    @Test void skipsBeatOrTieTopVote() {
        List<UUID> p = ids(5); Set<UUID> living = new LinkedHashSet<>(p);
        Map<UUID, UUID> v = new HashMap<>();
        v.put(p.get(0), p.get(4)); v.put(p.get(1), GameLogic.SKIP);
        assertNull(GameLogic.tally(v, living).ejected());           // 1 vs 1 skip
        v.put(p.get(2), GameLogic.SKIP);
        assertNull(GameLogic.tally(v, living).ejected());           // skip majority
        v.put(p.get(3), p.get(4));
        v.put(p.get(4), p.get(4));
        assertEquals(p.get(4), GameLogic.tally(v, living).ejected()); // 3 > 2 skips
    }

    @Test void noVotesAndDeadVotersAreIgnored() {
        List<UUID> p = ids(4); Set<UUID> living = new LinkedHashSet<>(p.subList(0, 3));
        assertNull(GameLogic.tally(new HashMap<>(), living).ejected());
        Map<UUID, UUID> v = new HashMap<>();
        v.put(p.get(3), p.get(0));        // dead voter
        v.put(p.get(0), p.get(3));        // vote for dead target
        assertNull(GameLogic.tally(v, living).ejected());
    }

    @Test void ringCandidateNeverManhunterOrExcluded() {
        for (int i = 0; i < 300; i++) {
            List<UUID> p = ids(6);
            Set<UUID> m = new HashSet<>(p.subList(0, 2));
            UUID excl = p.get(2);
            Optional<UUID> c = GameLogic.pickRingCandidate(p, m, excl, new Random(i));
            assertTrue(c.isPresent());
            assertFalse(m.contains(c.get()));
            assertNotEquals(excl, c.get());
        }
    }

    @Test void ringCandidateEmptyWhenNoInnocentsLeft() {
        List<UUID> p = ids(3); Set<UUID> m = new HashSet<>(p.subList(0, 2));
        assertTrue(GameLogic.pickRingCandidate(p, m, p.get(2), new Random(1)).isEmpty());
    }

    @Test void forcedRolesAreRespected() {
        for (int i = 0; i < 300; i++) {
            List<UUID> p = ids(8);
            Set<UUID> m = GameLogic.assignManhunters(p, 2, Set.of(p.get(0)), Set.of(p.get(1), p.get(2)), new Random(i));
            assertEquals(2, m.size());
            assertTrue(m.contains(p.get(0)));
            assertFalse(m.contains(p.get(1)));
            assertFalse(m.contains(p.get(2)));
        }
    }

    @Test void forcedLanternIsPreferredWhenValid() {
        List<UUID> p = ids(6); Set<UUID> m = new HashSet<>(p.subList(0, 1));
        assertEquals(p.get(3), GameLogic.pickRingCandidate(p, m, null, new Random(1), p.get(3)).get());
        assertNotEquals(p.get(0), GameLogic.pickRingCandidate(p, m, null, new Random(1), p.get(0)).get()); // Manhunter can't be Lantern
    }

    @Test void cooldownSecondsRoundUp() {
        assertEquals(0, GameLogic.remainingSeconds(100, 100));
        assertEquals(0, GameLogic.remainingSeconds(90, 100));
        assertEquals(1, GameLogic.remainingSeconds(101, 100));
        assertEquals(3, GameLogic.remainingSeconds(160, 100));
    }
}
