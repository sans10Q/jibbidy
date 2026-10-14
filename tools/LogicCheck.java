import com.lanterns.GameLogic;
import java.util.*;

/** Dependency-free mirror of GameLogicTest. Run: see README "Run the logic checks without Gradle". */
public class LogicCheck {
    static int pass = 0, fail = 0;
    static void check(String name, boolean ok) { if (ok) pass++; else fail++; System.out.println((ok ? "PASS " : "FAIL ") + name); }
    static List<UUID> ids(int n) { List<UUID> l = new ArrayList<>(); for (int i = 0; i < n; i++) l.add(UUID.randomUUID()); return l; }

    public static void main(String[] a) {
        check("manhunterCount 6->2", GameLogic.manhunterCount(6, 2) == 2);
        check("manhunterCount 12->2", GameLogic.manhunterCount(12, 2) == 2);
        check("manhunterCount 3->1", GameLogic.manhunterCount(3, 2) == 1);
        check("manhunterCount 4,5->1", GameLogic.manhunterCount(4, 5) == 1);
        check("manhunterCount min 1", GameLogic.manhunterCount(10, 0) == 1);

        boolean rolesOk = true;
        for (int i = 0; i < 500; i++) { List<UUID> p = ids(10); Set<UUID> m = GameLogic.assignManhunters(p, 2, new Random(i)); rolesOk &= m.size() == 2 && p.containsAll(m); }
        check("role assignment exact count (500 trials)", rolesOk);

        check("win: 0 manhunters -> innocents", GameLogic.checkWin(0, 5) == GameLogic.Winner.INNOCENTS);
        check("win: 2v2 -> manhunters", GameLogic.checkWin(2, 2) == GameLogic.Winner.MANHUNTERS);
        check("win: 2v1 -> manhunters", GameLogic.checkWin(2, 1) == GameLogic.Winner.MANHUNTERS);
        check("win: 2v3 -> none", GameLogic.checkWin(2, 3) == GameLogic.Winner.NONE);
        check("win: 1v2 -> none", GameLogic.checkWin(1, 2) == GameLogic.Winner.NONE);

        List<UUID> p = ids(5); Set<UUID> living = new LinkedHashSet<>(p);
        Map<UUID, UUID> v = new HashMap<>();
        v.put(p.get(0), p.get(4)); v.put(p.get(1), p.get(4)); v.put(p.get(2), p.get(3)); v.put(p.get(3), GameLogic.SKIP);
        check("vote: clear majority ejects", p.get(4).equals(GameLogic.tally(v, living).ejected()));

        List<UUID> q = ids(4); Set<UUID> lq = new LinkedHashSet<>(q);
        Map<UUID, UUID> t = new HashMap<>(); t.put(q.get(0), q.get(2)); t.put(q.get(1), q.get(3));
        check("vote: tie -> no ejection", GameLogic.tally(t, lq).ejected() == null);

        Map<UUID, UUID> s = new HashMap<>(); s.put(p.get(0), p.get(4)); s.put(p.get(1), GameLogic.SKIP);
        check("vote: 1 vs 1 skip -> none", GameLogic.tally(s, living).ejected() == null);
        s.put(p.get(2), GameLogic.SKIP);
        check("vote: skip majority -> none", GameLogic.tally(s, living).ejected() == null);
        s.put(p.get(3), p.get(4)); s.put(p.get(4), p.get(4));
        check("vote: 3 votes beat 2 skips", p.get(4).equals(GameLogic.tally(s, living).ejected()));

        check("vote: no votes -> none", GameLogic.tally(new HashMap<>(), living).ejected() == null);
        List<UUID> r = ids(4); Set<UUID> lr = new LinkedHashSet<>(r.subList(0, 3));
        Map<UUID, UUID> d = new HashMap<>(); d.put(r.get(3), r.get(0)); d.put(r.get(0), r.get(3));
        check("vote: dead voter/target ignored", GameLogic.tally(d, lr).ejected() == null);

        boolean ringOk = true;
        for (int i = 0; i < 1000; i++) {
            List<UUID> g = ids(6); Set<UUID> m = new HashSet<>(g.subList(0, 2)); UUID ex = g.get(2);
            Optional<UUID> c = GameLogic.pickRingCandidate(g, m, ex, new Random(i));
            ringOk &= c.isPresent() && !m.contains(c.get()) && !c.get().equals(ex);
        }
        check("ring transfer never Manhunter/excluded (1000 trials)", ringOk);
        List<UUID> g3 = ids(3);
        check("ring transfer empty when no innocents", GameLogic.pickRingCandidate(g3, new HashSet<>(g3.subList(0, 2)), g3.get(2), new Random(1)).isEmpty());

        boolean forcedOk = true;
        for (int i = 0; i < 500; i++) {
            List<UUID> f = ids(8);
            Set<UUID> m = GameLogic.assignManhunters(f, 2, Set.of(f.get(0)), Set.of(f.get(1), f.get(2)), new Random(i));
            forcedOk &= m.size() == 2 && m.contains(f.get(0)) && !m.contains(f.get(1)) && !m.contains(f.get(2));
        }
        check("forced Manhunter / forced non-Manhunter respected (500 trials)", forcedOk);
        List<UUID> h = ids(6); Set<UUID> hm = new HashSet<>(h.subList(0, 1));
        check("forced Lantern preferred when valid", h.get(3).equals(GameLogic.pickRingCandidate(h, hm, null, new Random(1), h.get(3)).get()));
        check("forced Lantern ignored if Manhunter", !h.get(0).equals(GameLogic.pickRingCandidate(h, hm, null, new Random(1), h.get(0)).get()));

        check("cooldown 0 when ready", GameLogic.remainingSeconds(100, 100) == 0 && GameLogic.remainingSeconds(90, 100) == 0);
        check("cooldown rounds up", GameLogic.remainingSeconds(101, 100) == 1 && GameLogic.remainingSeconds(160, 100) == 3);

        System.out.println("\n" + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }
}
