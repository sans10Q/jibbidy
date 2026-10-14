package com.lanterns;

import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.boss.ServerBossBar;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.UpdateSelectedSlotS2CPacket;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;

import java.util.*;

/** Server-authoritative murder-mystery state machine. */
public class GameManager {

    public enum Phase { IDLE, PLAYING, DISCUSSION, VOTING }
    public enum Role { INNOCENT, MANHUNTER }

    static class PlayerData {
        final UUID id; final String name;
        Role role = Role.INNOCENT;
        boolean alive = true;
        GameMode prevMode = GameMode.SURVIVAL;
        RegistryKey<World> retWorld; Vec3d retPos; float retYaw, retPitch;
        int emergencyUsed = 0;
        int mode = 0;
        PlayerData(UUID id, String name) { this.id = id; this.name = name; }
    }

    static class Body {
        UUID victim; String victimName; RegistryKey<World> world; Vec3d pos;
        long diedAt; boolean duringBlackout; UUID killer; UUID standId;
        List<String> pool = new ArrayList<>(); String killerSide = "north";
        Set<Integer> cluesGiven = new HashSet<>();
    }

    /** Ring ability state lives with the RING, not the bearer, so transfers preserve everything. */
    static class RingState {
        boolean verdictUsed = false;        // once per entire game
        boolean selfShieldUsed = false;     // once per entire game
        boolean shieldUsedThisRound = false;// refreshed when a meeting ends
        long residualReadyAt = 0;
        UUID shieldTarget = null; long shieldEndsAt = 0;
    }

    private static final String[] LANTERN_MODES = {"Verdict of Will", "Residual Will", "Last Light"};

    private final MinecraftServer server;
    private final Random rng = new Random();

    private Phase phase = Phase.IDLE;
    private final Map<UUID, PlayerData> players = new LinkedHashMap<>();
    private final Map<UUID, Long> killReady = new HashMap<>();
    private final Map<UUID, Long> blackoutReady = new HashMap<>();
    private final List<Body> bodies = new ArrayList<>();
    private RingState ring = new RingState();
    private UUID lantern = null;
    private boolean ringGranted = false;
    private long now = 0, blackoutEndsAt = 0, discussionEndsAt, votingEndsAt, meetingStartedAt,
            lastMeetingEndedAt, gameStartedAt;
    private final Map<UUID, UUID> votes = new HashMap<>();
    private int meetingsHeld = 0;
    // --- smoothness helpers
    private final Map<UUID, Long> barQuietUntil = new HashMap<>(); // keeps ability feedback readable
    private final Set<UUID> killPing = new HashSet<>(), blackPing = new HashSet<>();
    private ServerBossBar bossBar;
    /** Admin-forced roles ("manhunter" / "innocent" / "lantern"); persist until cleared or server restart. */
    private final Map<UUID, String> forced = new HashMap<>();

    public GameManager(MinecraftServer server) { this.server = server; }

    // ------------------------------------------------------------------ helpers

    public Phase phase() { return phase; }
    public boolean isRunning() { return phase != Phase.IDLE; }

    private static MutableText prefix() { return Text.literal("[Lanterns] ").formatted(Formatting.GREEN, Formatting.BOLD); }
    private static Text tag(String s, Formatting f) { return prefix().append(Text.literal(s).formatted(f)); }
    private void tell(ServerPlayerEntity p, String s, Formatting f) { p.sendMessage(tag(s, f), false); }
    private void bar(ServerPlayerEntity p, String s, Formatting f) {
        barQuietUntil.put(p.getUuid(), now + 40); // status line pauses for 2s so feedback is readable
        p.sendMessage(Text.literal(s).formatted(f), true);
    }
    private void broadcast(String s, Formatting f) { server.getPlayerManager().broadcast(tag(s, f), false); }

    private void title(ServerPlayerEntity p, String t, String sub, Formatting f) {
        p.networkHandler.sendPacket(new TitleFadeS2CPacket(10, 100, 20));
        p.networkHandler.sendPacket(new SubtitleS2CPacket(Text.literal(sub).formatted(Formatting.GRAY)));
        p.networkHandler.sendPacket(new TitleS2CPacket(Text.literal(t).formatted(f, Formatting.BOLD)));
    }

    private ServerPlayerEntity online(UUID id) { return server.getPlayerManager().getPlayer(id); }

    private boolean isAlive(UUID id) { PlayerData d = players.get(id); return d != null && d.alive; }
    private boolean isManhunter(UUID id) { PlayerData d = players.get(id); return d != null && d.role == Role.MANHUNTER; }

    private List<ServerPlayerEntity> livingOnline() {
        List<ServerPlayerEntity> out = new ArrayList<>();
        for (PlayerData d : players.values()) {
            if (!d.alive) continue;
            ServerPlayerEntity p = online(d.id);
            if (p != null) out.add(p);
        }
        return out;
    }

    private Set<UUID> livingIds() {
        Set<UUID> s = new LinkedHashSet<>();
        for (PlayerData d : players.values()) if (d.alive) s.add(d.id);
        return s;
    }

    private Set<UUID> manhunterIds() {
        Set<UUID> s = new HashSet<>();
        for (PlayerData d : players.values()) if (d.role == Role.MANHUNTER) s.add(d.id);
        return s;
    }

    private void sound(ServerPlayerEntity p, SoundEvent e, float pitch) {
        p.playSoundToPlayer(e, SoundCategory.PLAYERS, 1.0f, pitch);
    }

    private void soundAll(SoundEvent e, float pitch) {
        for (PlayerData d : players.values()) {
            ServerPlayerEntity p = online(d.id);
            if (p != null) sound(p, e, pitch);
        }
    }

    private boolean hasRing(ServerPlayerEntity p) {
        for (int i = 0; i < p.getInventory().size(); i++)
            if (p.getInventory().getStack(i).isOf(ModItems.POWER_RING)) return true;
        return false;
    }

    private boolean hasCore(ServerPlayerEntity p) {
        for (int i = 0; i < p.getInventory().size(); i++)
            if (p.getInventory().getStack(i).isOf(ModItems.BLACKOUT_CORE)) return true;
        return false;
    }

    private void removeCore(ServerPlayerEntity p) {
        for (int i = 0; i < p.getInventory().size(); i++)
            if (p.getInventory().getStack(i).isOf(ModItems.BLACKOUT_CORE)) p.getInventory().setStack(i, ItemStack.EMPTY);
    }

    private void giveCore(ServerPlayerEntity p) {
        if (hasCore(p)) return;
        putInLastSlot(p, new ItemStack(ModItems.BLACKOUT_CORE));
    }

    /** Always hotbar slot 9, and never leave the player holding it, so nobody spots the item on spawn. */
    private void putInLastSlot(ServerPlayerEntity p, ItemStack stack) {
        PlayerInventory inv = p.getInventory();
        ItemStack displaced = inv.getStack(8);
        inv.setStack(8, stack);
        if (!displaced.isEmpty() && !inv.insertStack(displaced)) p.dropItem(displaced, false);
        if (inv.selectedSlot == 8) {
            inv.selectedSlot = 0;
            p.networkHandler.sendPacket(new UpdateSelectedSlotS2CPacket(0));
        }
    }

    /** Right-click with the Blackout Core. */
    public void onCoreUse(ServerPlayerEntity p) {
        String err = blackout(p);
        if (err != null) bar(p, err, Formatting.RED);
    }

    private void giveRing(ServerPlayerEntity p) {
        if (hasRing(p)) return;
        putInLastSlot(p, new ItemStack(ModItems.POWER_RING));
    }

    private void removeRings(ServerPlayerEntity p) {
        for (int i = 0; i < p.getInventory().size(); i++)
            if (p.getInventory().getStack(i).isOf(ModItems.POWER_RING)) p.getInventory().setStack(i, ItemStack.EMPTY);
    }

    private void teleportTo(ServerPlayerEntity p, RegistryKey<World> key, Vec3d pos, float yaw, float pitch) {
        ServerWorld w = server.getWorld(key);
        if (w != null) p.teleport(w, pos.x, pos.y, pos.z, yaw, pitch);
    }

    /** Look-direction targeting among living, eligible players. */
    private ServerPlayerEntity lookTarget(ServerPlayerEntity src, double range, java.util.function.Predicate<ServerPlayerEntity> ok) {
        Vec3d eye = src.getEyePos();
        Vec3d look = src.getRotationVec(1.0f).normalize();
        ServerPlayerEntity best = null;
        double bestDot = 0.93;
        for (ServerPlayerEntity o : src.getServerWorld().getPlayers()) {
            if (o == src || !isAlive(o.getUuid()) || !ok.test(o)) continue;
            Vec3d center = o.getPos().add(0, o.getHeight() * 0.5, 0);
            Vec3d dir = center.subtract(eye);
            double dist = dir.length();
            if (dist > range || dist < 0.01) continue;
            double dot = dir.normalize().dotProduct(look);
            if (dot > bestDot && src.canSee(o)) { bestDot = dot; best = o; }
        }
        return best;
    }

    /** Shows the caller their own role (title + chat). Private to that player. */
    public void showRole(ServerPlayerEntity p) {
        PlayerData d = players.get(p.getUuid());
        if (phase == Phase.IDLE || d == null) { tell(p, "You are not in a game.", Formatting.GRAY); return; }
        if (!d.alive) { tell(p, "You are dead. You are a ghost.", Formatting.GRAY); return; }
        if (d.role == Role.MANHUNTER) {
            List<String> allies = new ArrayList<>();
            for (PlayerData o : players.values()) if (o.role == Role.MANHUNTER && !o.id.equals(d.id)) allies.add(o.name);
            title(p, "YOU ARE A MANHUNTER", "Eliminate everyone. Stay hidden.", Formatting.DARK_RED);
            tell(p, "YOU ARE A MANHUNTER. " + (allies.isEmpty() ? "You are working alone." : "Fellow Manhunter(s): " + String.join(", ", allies)), Formatting.RED);
            tell(p, "Punch a player to kill them (cooldown applies). Right-click your Blackout Core for System Blackout.", Formatting.GRAY);
        } else {
            title(p, "YOU ARE INNOCENT", "Find the Manhunter before they find you.", Formatting.GREEN);
            tell(p, "YOU ARE INNOCENT. Report bodies, discuss, and vote out the Manhunter(s).", Formatting.GREEN);
            if (d.id.equals(lantern)) tell(p, "You also bear the Green Lantern ring.", Formatting.DARK_GREEN);
        }
    }

    // ------------------------------------------------------------------ start / stop

    /** @return error message or null on success */
    public String start(boolean force) {
        if (phase != Phase.IDLE) return "A game is already running.";
        Config c = Config.get();
        if (!c.meetingPoint.set) return "Set a meeting point first with /lantern setmeetingpoint.";
        if (server.getWorld(c.meetingPoint.key()) == null) return "The saved meeting dimension no longer exists.";
        List<ServerPlayerEntity> list = new ArrayList<>(server.getPlayerManager().getPlayerList());
        int min = force ? 3 : c.minPlayers;
        if (list.size() < min) return "Need at least " + min + " players (have " + list.size() + ").";

        players.clear(); bodies.clear(); votes.clear(); killReady.clear(); blackoutReady.clear();
        barQuietUntil.clear(); killPing.clear(); blackPing.clear(); removeBossBar();
        ring = new RingState(); lantern = null; ringGranted = false; meetingsHeld = 0; blackoutEndsAt = 0;
        now = 0; gameStartedAt = 0; lastMeetingEndedAt = 0;

        List<UUID> ids = new ArrayList<>();
        for (ServerPlayerEntity p : list) ids.add(p.getUuid());
        int mCount = GameLogic.manhunterCount(ids.size(), c.manhunters);
        Set<UUID> forcedYes = new HashSet<>(), forcedNo = new HashSet<>();
        for (Map.Entry<UUID, String> e : forced.entrySet()) {
            if (e.getValue().equals("manhunter")) forcedYes.add(e.getKey()); else forcedNo.add(e.getKey());
        }
        Set<UUID> mh = GameLogic.assignManhunters(ids, mCount, forcedYes, forcedNo, rng);

        for (ServerPlayerEntity p : list) {
            PlayerData d = new PlayerData(p.getUuid(), p.getName().getString());
            d.prevMode = p.interactionManager.getGameMode();
            d.role = mh.contains(p.getUuid()) ? Role.MANHUNTER : Role.INNOCENT;
            players.put(d.id, d);
            p.changeGameMode(GameMode.ADVENTURE);
            p.clearStatusEffects();
            p.setHealth(p.getMaxHealth());
            p.getHungerManager().setFoodLevel(20);
            removeRings(p); removeCore(p); // nobody has a ring until the first meeting ends
            if (d.role == Role.MANHUNTER) {
                giveCore(p);
                p.getItemCooldownManager().set(ModItems.BLACKOUT_CORE, c.initialKillCooldownSec * 20);
                killReady.put(d.id, (long) c.initialKillCooldownSec * 20);
                blackoutReady.put(d.id, (long) c.initialKillCooldownSec * 20);
            }
        }
        phase = Phase.PLAYING;

        for (PlayerData d : players.values()) {
            ServerPlayerEntity p = online(d.id);
            if (p == null) continue;
            showRole(p);
            sound(p, SoundEvents.ENTITY_WARDEN_HEARTBEAT, 0.8f);
        }
        broadcast("The game has begun. " + mCount + " Manhunter(s) walk among you.", Formatting.YELLOW);
        return null;
    }

    public void stop(String reason) { if (phase != Phase.IDLE) endGame(GameLogic.Winner.NONE, reason); }

    private void restorePlayer(ServerPlayerEntity p, PlayerData d) {
        removeRings(p);
        removeCore(p);
        p.removeStatusEffect(StatusEffects.DARKNESS);
        if (d != null) p.changeGameMode(d.prevMode);
    }

    private void endGame(GameLogic.Winner w, String reason) {
        removeBossBar();
        MutableText header = Text.literal("");
        switch (w) {
            case INNOCENTS -> broadcast("INNOCENTS WIN! " + reason, Formatting.GREEN);
            case MANHUNTERS -> broadcast("MANHUNTERS WIN! " + reason, Formatting.DARK_RED);
            default -> broadcast("Game ended. " + reason, Formatting.YELLOW);
        }
        // Final role reveal (only at game end).
        StringBuilder mhs = new StringBuilder();
        for (PlayerData d : players.values()) if (d.role == Role.MANHUNTER) { if (mhs.length() > 0) mhs.append(", "); mhs.append(d.name); }
        broadcast("Manhunters were: " + mhs, Formatting.RED);
        if (lantern != null && players.containsKey(lantern))
            broadcast("The Green Lantern at the end: " + players.get(lantern).name, Formatting.GREEN);

        for (PlayerData d : players.values()) {
            ServerPlayerEntity p = online(d.id);
            if (p != null) { restorePlayer(p, d); title(p, w == GameLogic.Winner.NONE ? "GAME OVER" : (w == GameLogic.Winner.INNOCENTS ? "INNOCENTS WIN" : "MANHUNTERS WIN"), "", Formatting.GOLD); }
        }
        clearBodies();
        phase = Phase.IDLE;
        players.clear(); votes.clear(); killReady.clear(); blackoutReady.clear();
        lantern = null; blackoutEndsAt = 0;
    }

    /** Called on server shutdown. */
    public void shutdown() {
        if (phase == Phase.IDLE) return;
        for (PlayerData d : players.values()) { ServerPlayerEntity p = online(d.id); if (p != null) restorePlayer(p, d); }
        clearBodies();
        removeBossBar();
        phase = Phase.IDLE;
    }

    // ------------------------------------------------------------------ win checks

    private boolean checkWinAndEnd() {
        int m = 0, o = 0;
        for (PlayerData d : players.values()) if (d.alive) { if (d.role == Role.MANHUNTER) m++; else o++; }
        GameLogic.Winner w = GameLogic.checkWin(m, o);
        if (w == GameLogic.Winner.INNOCENTS) { endGame(w, "All Manhunters have been eliminated."); return true; }
        if (w == GameLogic.Winner.MANHUNTERS) { endGame(w, "The Manhunters now equal or outnumber everyone else."); return true; }
        return false;
    }

    // ------------------------------------------------------------------ tick

    public void tick() {
        if (phase == Phase.IDLE) return;
        now++;

        if (phase == Phase.PLAYING) {
            if (ring.shieldTarget != null && now >= ring.shieldEndsAt) ring.shieldTarget = null;
        } else {
            if (now % 5 == 0) { enforceMeetingPosition(); updateBossBar(); }
            if (phase == Phase.DISCUSSION && now >= discussionEndsAt) beginVoting();
            else if (phase == Phase.VOTING) {
                boolean all = true;
                for (UUID id : livingIds()) if (!votes.containsKey(id)) { all = false; break; }
                if (all && votingEndsAt - now > 60) {
                    votingEndsAt = now + 60; // short beat before results instead of an instant cut
                    broadcast("All votes are in. Results in 3 seconds...", Formatting.YELLOW);
                }
                if (now >= votingEndsAt) { resolveVoting(); return; }
            }
        }
        if (phase == Phase.IDLE) return;

        if (now % 10 == 0) {
            for (PlayerData d : players.values()) {
                ServerPlayerEntity p = online(d.id);
                if (p == null || !d.alive) continue;
                if (now >= barQuietUntil.getOrDefault(d.id, 0L))
                    p.sendMessage(Text.literal(statusLine(d)).formatted(d.role == Role.MANHUNTER ? Formatting.RED : Formatting.GREEN), true);
                if (phase == Phase.PLAYING && d.role == Role.MANHUNTER) {
                    if (now >= killReady.getOrDefault(d.id, 0L)) {
                        if (killPing.add(d.id)) { sound(p, SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f); bar(p, "Kill ready", Formatting.RED); }
                    } else killPing.remove(d.id);
                    if (now >= blackoutReady.getOrDefault(d.id, 0L)) {
                        if (blackPing.add(d.id)) { sound(p, SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, 0.7f); bar(p, "Blackout ready", Formatting.DARK_PURPLE); }
                    } else blackPing.remove(d.id);
                }
            }
        }
        if (now % 20 == 0) {
            for (PlayerData d : players.values()) {
                ServerPlayerEntity p = online(d.id);
                if (p == null) continue;
                if (d.alive && d.id.equals(lantern)) { if (!hasRing(p)) giveRing(p); }
                else if (hasRing(p)) removeRings(p); // only the current Lantern may hold a ring
                if (d.alive && d.role == Role.MANHUNTER) { if (!hasCore(p)) giveCore(p); }
                else if (hasCore(p)) removeCore(p);   // only living Manhunters hold the core
                if (d.alive) p.getHungerManager().setFoodLevel(20);
            }
        }
    }

    private void enforceMeetingPosition() {
        Config.MeetingPoint mp = Config.get().meetingPoint;
        Vec3d pos = new Vec3d(mp.x, mp.y, mp.z);
        for (ServerPlayerEntity p : livingOnline()) {
            boolean sameWorld = p.getWorld().getRegistryKey().equals(mp.key());
            if (!sameWorld || p.getPos().squaredDistanceTo(pos) > Config.get().meetingFreeRadius * Config.get().meetingFreeRadius) teleportTo(p, mp.key(), pos, p.getYaw(), p.getPitch());
        }
    }

    private String statusLine(PlayerData d) {
        String base = "Role: " + (d.role == Role.MANHUNTER ? "MANHUNTER" : "INNOCENT");
        if (phase != Phase.PLAYING) return base + "  |  " + (phase == Phase.VOTING ? "Voting" : "Meeting");
        if (d.role == Role.MANHUNTER)
            return base + "  |  Kill: " + cd(killReady.getOrDefault(d.id, 0L)) + "  |  Blackout Core: " + cd(blackoutReady.getOrDefault(d.id, 0L));
        if (d.id.equals(lantern)) {
            return base + " (Green Lantern)  |  Mode: " + LANTERN_MODES[d.mode % LANTERN_MODES.length]
                + "  |  Verdict: " + (ring.verdictUsed ? "SPENT" : "ready")
                + "  |  Residual: " + cd(ring.residualReadyAt)
                + "  |  Last Light: " + (ring.shieldUsedThisRound ? "spent this round" : "ready")
                + (ring.selfShieldUsed ? "" : " (self ok)");
        }
        return base;
    }

    private String cd(long readyAt) {
        long s = GameLogic.remainingSeconds(readyAt, now);
        return s == 0 ? "READY" : s + "s";
    }

    private String[] modesFor(PlayerData d) {
        return d.id.equals(lantern) ? LANTERN_MODES : null;
    }

    // ------------------------------------------------------------------ ring input

    public void onRingUse(ServerPlayerEntity p) {
        PlayerData d = players.get(p.getUuid());
        if (phase == Phase.IDLE || d == null || !d.alive) { bar(p, "The ring is inert.", Formatting.GRAY); return; }
        if (phase != Phase.PLAYING) { bar(p, "The ring is silent during meetings.", Formatting.GRAY); return; }
        String[] modes = modesFor(d);
        if (modes == null) { bar(p, "The ring is dormant.", Formatting.GRAY); return; }
        if (p.isSneaking()) {
            d.mode = (d.mode + 1) % modes.length;
            bar(p, "Mode: " + modes[d.mode], Formatting.GREEN);
            sound(p, SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, 1.6f);
            return;
        }
        String m = modes[d.mode % modes.length];
        switch (m) {
            case "Verdict of Will" -> verdict(p);
            case "Residual Will" -> residual(p);
            case "Last Light" -> shield(p);
            default -> {}
        }
    }

    // ------------------------------------------------------------------ Manhunter actions

    /** Called when a player punches another player. Returns true to cancel the hit (no PvP ever). */
    public boolean onAttack(ServerPlayerEntity attacker, ServerPlayerEntity target) {
        if (phase == Phase.IDLE) return false;
        PlayerData ad = players.get(attacker.getUuid());
        if (ad == null) return false;
        if (ad.alive && ad.role == Role.MANHUNTER && phase == Phase.PLAYING) tryKill(attacker, target);
        return true;
    }

    private void tryKill(ServerPlayerEntity killer, ServerPlayerEntity target) {
        Config c = Config.get();
        long ready = killReady.getOrDefault(killer.getUuid(), 0L);
        if (now < ready) { bar(killer, "Kill ready in " + GameLogic.remainingSeconds(ready, now) + "s", Formatting.RED); return; }
        PlayerData td = players.get(target.getUuid());
        if (td == null || !td.alive) return;
        if (td.role == Role.MANHUNTER) { bar(killer, "That is your ally.", Formatting.GRAY); return; }
        if (killer.squaredDistanceTo(target) > c.killRange * c.killRange) return;

        killReady.put(killer.getUuid(), now + (long) c.killCooldownSec * 20);

        if (ring.shieldTarget != null && ring.shieldTarget.equals(target.getUuid()) && now < ring.shieldEndsAt) {
            ring.shieldTarget = null; // shield consumed
            ServerWorld w = target.getServerWorld();
            w.spawnParticles(ParticleTypes.HAPPY_VILLAGER, target.getX(), target.getY() + 1, target.getZ(), 60, 0.6, 0.9, 0.6, 0.15);
            w.spawnParticles(ParticleTypes.END_ROD, target.getX(), target.getY() + 1, target.getZ(), 30, 0.5, 0.8, 0.5, 0.1);
            w.playSound(null, target.getX(), target.getY(), target.getZ(), SoundEvents.ITEM_TOTEM_USE, SoundCategory.PLAYERS, 1.0f, 1.4f);
            bar(killer, "A green light turned your strike aside!", Formatting.GREEN);
            return;
        }
        eliminateByKill(target, killer);
    }

    private void eliminateByKill(ServerPlayerEntity victim, ServerPlayerEntity killer) {
        PlayerData vd = players.get(victim.getUuid());
        if (vd == null || !vd.alive) return; // simultaneous-kill guard
        spawnBody(victim, killer);
        vd.alive = false;
        votes.remove(victim.getUuid());
        victim.changeGameMode(GameMode.SPECTATOR);
        title(victim, "YOU WERE KILLED", "You are now a ghost. Do not reveal what you know.", Formatting.DARK_RED);
        if (Config.get().revealRoleOnDeath) broadcast(vd.name + " was " + vd.role + ".", Formatting.GRAY);

        if (victim.getUuid().equals(lantern)) {
            endGame(GameLogic.Winner.MANHUNTERS, "The Green Lantern has fallen.");
            return;
        }
        checkWinAndEnd();
    }

    private void spawnBody(ServerPlayerEntity victim, ServerPlayerEntity killer) {
        Body b = new Body();
        b.victim = victim.getUuid(); b.victimName = victim.getName().getString();
        b.world = victim.getWorld().getRegistryKey(); b.pos = victim.getPos();
        b.diedAt = now; b.duringBlackout = now < blackoutEndsAt; b.killer = killer.getUuid();
        b.killerSide = Direction.getFacing(killer.getX() - victim.getX(), 0, killer.getZ() - victim.getZ()).asString();

        // Pool of suspects: the killer plus up to two other living players (truthful but inconclusive).
        List<String> others = new ArrayList<>();
        for (PlayerData d : players.values())
            if (d.alive && !d.id.equals(victim.getUuid()) && !d.id.equals(killer.getUuid())) others.add(d.name);
        Collections.shuffle(others, rng);
        b.pool.add(players.get(killer.getUuid()).name);
        for (int i = 0; i < Math.min(2, others.size()); i++) b.pool.add(others.get(i));
        Collections.shuffle(b.pool, rng);

        ServerWorld w = victim.getServerWorld();
        ArmorStandEntity s = new ArmorStandEntity(w, victim.getX(), victim.getY(), victim.getZ());
        s.setYaw(victim.getYaw());
        s.setInvulnerable(true);
        s.equipStack(EquipmentSlot.HEAD, new ItemStack(Items.PLAYER_HEAD));
        s.equipStack(EquipmentSlot.CHEST, new ItemStack(Items.LEATHER_CHESTPLATE));
        s.setCustomName(Text.literal("Unidentified Body").formatted(Formatting.DARK_RED));
        s.setCustomNameVisible(true);
        s.addCommandTag("lanterns_body");
        w.spawnEntity(s);
        b.standId = s.getUuid();
        bodies.add(b);
        w.playSound(null, victim.getX(), victim.getY(), victim.getZ(), SoundEvents.ENTITY_PLAYER_DEATH, SoundCategory.PLAYERS, 1.0f, 0.8f);
        w.spawnParticles(ParticleTypes.SOUL, victim.getX(), victim.getY() + 1, victim.getZ(), 25, 0.3, 0.6, 0.3, 0.03);
        w.spawnParticles(ParticleTypes.SMOKE, victim.getX(), victim.getY() + 0.5, victim.getZ(), 20, 0.3, 0.3, 0.3, 0.02);
    }

    private void clearBodies() {
        for (Body b : bodies) {
            ServerWorld w = server.getWorld(b.world);
            if (w == null || b.standId == null) continue;
            Entity e = w.getEntity(b.standId);
            if (e != null) e.discard();
        }
        bodies.clear();
    }

    /** Blackout Core use. Returns an error string, or null on success. */
    public String blackout(ServerPlayerEntity p) {
        PlayerData d = players.get(p.getUuid());
        if (phase != Phase.PLAYING || d == null || !d.alive || d.role != Role.MANHUNTER) return "Nothing happens.";
        Config c = Config.get();
        long ready = blackoutReady.getOrDefault(p.getUuid(), 0L);
        if (now < ready) return "Blackout ready in " + GameLogic.remainingSeconds(ready, now) + "s";
        if (now < blackoutEndsAt) return "A blackout is already active.";
        blackoutReady.put(p.getUuid(), now + (long) c.blackoutCooldownSec * 20);
        p.getItemCooldownManager().set(ModItems.BLACKOUT_CORE, c.blackoutCooldownSec * 20);
        blackoutEndsAt = now + (long) c.blackoutDurationSec * 20;
        // Everyone (including the activator) is affected, so it never reveals who triggered it.
        for (ServerPlayerEntity o : livingOnline())
            o.addStatusEffect(new StatusEffectInstance(StatusEffects.DARKNESS, c.blackoutDurationSec * 20, 0, false, false, false));
        soundAll(SoundEvents.BLOCK_BEACON_DEACTIVATE, 0.6f);
        broadcast("SYSTEM BLACKOUT! The lights fail...", Formatting.DARK_GRAY);
        return null;
    }

    private void endBlackout() {
        blackoutEndsAt = 0;
        for (ServerPlayerEntity o : livingOnline()) o.removeStatusEffect(StatusEffects.DARKNESS);
    }

    // ------------------------------------------------------------------ Lantern actions

    private void verdict(ServerPlayerEntity p) {
        if (ring.verdictUsed) { bar(p, "The Verdict of Will has already been spent.", Formatting.RED); return; }
        ServerPlayerEntity t = lookTarget(p, 30, o -> true);
        if (t == null) { bar(p, "Look directly at a living player.", Formatting.GRAY); return; }
        ring.verdictUsed = true; // consumed permanently
        boolean mh = isManhunter(t.getUuid());
        tell(p, "VERDICT OF WILL: " + t.getName().getString() + " is " + (mh ? "a MANHUNTER." : "INNOCENT."), mh ? Formatting.RED : Formatting.GREEN);
        sound(p, SoundEvents.BLOCK_BEACON_ACTIVATE, 1.5f);
        p.getServerWorld().spawnParticles(p, ParticleTypes.END_ROD, true, t.getX(), t.getY() + 1, t.getZ(), 15, 0.3, 0.5, 0.3, 0.02);
    }

    private void residual(ServerPlayerEntity p) {
        if (now < ring.residualReadyAt) { bar(p, "Residual Will ready in " + GameLogic.remainingSeconds(ring.residualReadyAt, now) + "s", Formatting.RED); return; }
        Body best = null; double bd = 12 * 12;
        for (Body b : bodies) {
            if (!b.world.equals(p.getWorld().getRegistryKey())) continue;
            double dist = b.pos.squaredDistanceTo(p.getPos());
            if (dist < bd) { bd = dist; best = b; }
        }
        if (best == null) { bar(p, "No fresh trace nearby.", Formatting.GRAY); return; }
        List<Integer> avail = new ArrayList<>(List.of(0, 1, 2, 3));
        avail.removeAll(best.cluesGiven);
        if (avail.isEmpty()) { bar(p, "Nothing more can be read from this scene.", Formatting.GRAY); return; }
        int pick = avail.get(rng.nextInt(avail.size()));
        best.cluesGiven.add(pick);
        ring.residualReadyAt = now + (long) Config.get().residualCooldownSec * 20;
        String msg = switch (pick) {
            case 0 -> { long secs = Math.max(5, Math.round((now - best.diedAt) / 20.0 / 10.0) * 10); yield "The will-residue is roughly " + secs + " seconds old."; }
            case 1 -> best.duringBlackout ? "Darkness clung to this death." : "The lights were steady when this happened.";
            case 2 -> "A malevolent trace here belongs to one of: " + String.join(", ", best.pool) + ".";
            default -> "The attacker stood to the " + best.killerSide + " of the victim.";
        };
        tell(p, "RESIDUAL WILL: " + msg, Formatting.AQUA);
        sound(p, SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, 1.0f);
        p.getServerWorld().spawnParticles(p, ParticleTypes.SOUL_FIRE_FLAME, true, best.pos.x, best.pos.y + 0.5, best.pos.z, 25, 0.5, 0.5, 0.5, 0.02);
    }

    private void shield(ServerPlayerEntity p) {
        if (ring.shieldUsedThisRound) { bar(p, "Last Light is spent until the next meeting ends.", Formatting.RED); return; }
        ServerPlayerEntity t = lookTarget(p, 10, o -> true);
        boolean self = t == null;
        if (self && ring.selfShieldUsed) { bar(p, "You may shield yourself only once per game. Look at another player.", Formatting.RED); return; }
        ServerPlayerEntity who = self ? p : t;
        ring.shieldUsedThisRound = true;
        if (self) ring.selfShieldUsed = true;
        ring.shieldTarget = who.getUuid();
        ring.shieldEndsAt = now + (long) Config.get().shieldDurationSec * 20;
        bar(p, "Last Light shields " + (self ? "you" : who.getName().getString()) + " for " + Config.get().shieldDurationSec + "s.", Formatting.GREEN);
        sound(p, SoundEvents.BLOCK_BEACON_POWER_SELECT, 1.2f);
        p.getServerWorld().spawnParticles(p, ParticleTypes.HAPPY_VILLAGER, true, who.getX(), who.getY() + 1, who.getZ(), 10, 0.3, 0.5, 0.3, 0.0);
    }

    // ------------------------------------------------------------------ ring transfer

    private void transferRing(UUID exclude) { transferRing(exclude, null); }

    private void transferRing(UUID exclude, UUID preferred) {
        Optional<UUID> next = GameLogic.pickRingCandidate(livingIds(), manhunterIds(), exclude, rng, preferred);
        UUID old = lantern;
        lantern = next.orElse(null);
        if (old != null) { ServerPlayerEntity op = online(old); if (op != null) removeRings(op); }
        if (lantern != null) {
            PlayerData d = players.get(lantern);
            d.mode = 0;
            ServerPlayerEntity p = online(lantern);
            if (p != null) {
                giveRing(p);
                title(p, "THE RING CHOOSES YOU", "You are the Green Lantern. Tell no one.", Formatting.GREEN);
                tell(p, "You bear the ring (hotbar/inventory). Right-click = use, sneak + right-click = change mode. Verdict: 1 use per game. Tell no one.", Formatting.GREEN);
                sound(p, SoundEvents.BLOCK_BEACON_ACTIVATE, 1.0f);
            }
        }
    }

    // ------------------------------------------------------------------ meetings

    public String report(ServerPlayerEntity p, Entity clicked) {
        if (phase != Phase.PLAYING) return "Reports are only possible during play.";
        PlayerData d = players.get(p.getUuid());
        if (d == null || !d.alive) return "Only living players can report bodies.";
        Body found = null;
        if (clicked != null) {
            for (Body b : bodies) if (clicked.getUuid().equals(b.standId)) found = b;
        } else {
            double bd = Config.get().reportRange * Config.get().reportRange;
            for (Body b : bodies) {
                if (!b.world.equals(p.getWorld().getRegistryKey())) continue;
                double dist = b.pos.squaredDistanceTo(p.getPos());
                if (dist <= bd) { bd = dist; found = b; }
            }
        }
        if (found == null) return "No body within reach.";
        long since = (now - found.diedAt) / 20;
        startMeeting("BODY REPORTED", p.getName().getString() + " found the body of " + found.victimName
                + " (died roughly " + (Math.round(since / 10.0) * 10) + "s ago).");
        return null;
    }

    public String emergency(ServerPlayerEntity p) {
        Config c = Config.get();
        if (phase != Phase.PLAYING) return "An emergency meeting can't be called right now.";
        PlayerData d = players.get(p.getUuid());
        if (d == null || !d.alive) return "Only living players can call meetings.";
        if (now - gameStartedAt < (long) c.meetingGraceSec * 20) return "Too early in the round.";
        if (meetingsHeld > 0 && now - lastMeetingEndedAt < (long) c.emergencyCooldownSec * 20) return "Emergency meetings are on cooldown.";
        if (d.emergencyUsed >= c.emergencyMeetingsPerPlayer) return "You have no emergency meetings left.";
        d.emergencyUsed++;
        startMeeting("EMERGENCY MEETING", p.getName().getString() + " called an emergency meeting.");
        return null;
    }

    private void startMeeting(String headline, String detail) {
        if (phase != Phase.PLAYING) return; // no overlapping meetings
        Config c = Config.get();
        Config.MeetingPoint mp = c.meetingPoint;
        phase = Phase.DISCUSSION;
        meetingStartedAt = now;
        discussionEndsAt = now + (long) c.discussionSec * 20;
        votingEndsAt = discussionEndsAt + (long) c.votingSec * 20;
        votes.clear();
        ring.shieldTarget = null;
        if (now < blackoutEndsAt) endBlackout();
        Vec3d pos = new Vec3d(mp.x, mp.y, mp.z);
        for (ServerPlayerEntity p : livingOnline()) {
            PlayerData d = players.get(p.getUuid());
            d.retWorld = p.getWorld().getRegistryKey(); d.retPos = p.getPos(); d.retYaw = p.getYaw(); d.retPitch = p.getPitch();
            teleportTo(p, mp.key(), pos, mp.yaw, mp.pitch);
            title(p, headline, detail, Formatting.GOLD);
            sound(p, SoundEvents.BLOCK_BELL_USE, 0.8f);
        }
        broadcast(headline + ": " + detail, Formatting.GOLD);
        broadcast("Discuss! Voting opens in " + c.discussionSec + "s. Bluff, accuse, defend.", Formatting.YELLOW);
        showBossBar();
    }

    private void beginVoting() {
        phase = Phase.VOTING;
        broadcast("VOTING OPEN (" + Config.get().votingSec + "s). Click a name or use /lantern vote <player|skip>:", Formatting.GOLD);
        MutableText line = Text.literal("");
        for (PlayerData d : players.values()) {
            if (!d.alive) continue;
            line.append(Text.literal("[" + d.name + "] ").styled(s -> s.withColor(Formatting.AQUA)
                    .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/lantern vote " + d.name))
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("Vote to eject " + d.name)))));
        }
        line.append(Text.literal("[SKIP]").styled(s -> s.withColor(Formatting.GRAY)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/lantern vote skip"))));
        for (ServerPlayerEntity p : livingOnline()) { p.sendMessage(line, false); sound(p, SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f); }
    }

    public String vote(ServerPlayerEntity p, UUID target) {
        if (phase != Phase.VOTING) return "Voting is not open.";
        if (!isAlive(p.getUuid())) return "Dead players cannot vote.";
        if (votes.containsKey(p.getUuid())) return "You already voted.";
        if (!target.equals(GameLogic.SKIP) && !isAlive(target)) return "That player is not alive.";
        votes.put(p.getUuid(), target);
        broadcast(p.getName().getString() + " has voted.", Formatting.GRAY);
        return null;
    }

    private void resolveVoting() {
        Set<UUID> living = livingIds();
        GameLogic.TallyResult r = GameLogic.tally(votes, living);
        StringBuilder sb = new StringBuilder("Results: ");
        for (Map.Entry<UUID, Integer> e : r.counts().entrySet())
            if (e.getValue() > 0) sb.append(players.get(e.getKey()).name).append("=").append(e.getValue()).append("  ");
        sb.append("Skip=").append(r.skips());
        broadcast(sb.toString(), Formatting.YELLOW);
        if (Config.get().revealVotesPublicly) {
            for (Map.Entry<UUID, UUID> e : votes.entrySet()) {
                PlayerData voter = players.get(e.getKey());
                if (voter == null) continue;
                String tgt = e.getValue().equals(GameLogic.SKIP) ? "skip" : players.get(e.getValue()) == null ? "?" : players.get(e.getValue()).name;
                broadcast(voter.name + " -> " + tgt, Formatting.GRAY);
            }
        }
        if (r.ejected() == null) {
            broadcast("No one was ejected.", Formatting.YELLOW);
        } else {
            PlayerData ej = players.get(r.ejected());
            ej.alive = false;
            ServerPlayerEntity ep = online(ej.id);
            if (ep != null) { ep.changeGameMode(GameMode.SPECTATOR); title(ep, "EJECTED", "You are now a ghost.", Formatting.RED); }
            broadcast(ej.name + " was ejected." + (Config.get().revealRoleOnDeath ? " They were " + ej.role + "." : ""), Formatting.RED);
            if (ej.id.equals(lantern)) transferRing(ej.id); // secret; no Manhunter victory
        }
        if (checkWinAndEnd()) return;
        endMeeting();
    }

    private void endMeeting() {
        removeBossBar();
        Config c = Config.get();
        long dur = now - meetingStartedAt;
        killReady.replaceAll((k, v) -> Math.max(v + dur, now + (long) c.postMeetingKillCooldownSec * 20));
        blackoutReady.replaceAll((k, v) -> v + dur);
        for (Map.Entry<UUID, Long> e : blackoutReady.entrySet()) { // keep the core's cooldown overlay in sync
            ServerPlayerEntity mp = online(e.getKey());
            long rem = e.getValue() - now;
            if (mp != null && rem > 0) mp.getItemCooldownManager().set(ModItems.BLACKOUT_CORE, (int) rem);
        }
        ring.residualReadyAt += dur;
        ring.shieldUsedThisRound = false; // refreshed when the meeting ends
        ring.shieldTarget = null;
        clearBodies();
        if (c.returnPlayersAfterMeeting) {
            for (ServerPlayerEntity p : livingOnline()) {
                PlayerData d = players.get(p.getUuid());
                if (d.retPos != null) teleportTo(p, d.retWorld, d.retPos, d.retYaw, d.retPitch);
            }
        }
        lastMeetingEndedAt = now;
        meetingsHeld++;
        votes.clear();
        phase = Phase.PLAYING;
        if (!ringGranted) {
            ringGranted = true;
            UUID wanted = null;
            for (Map.Entry<UUID, String> e : forced.entrySet()) if (e.getValue().equals("lantern")) wanted = e.getKey();
            transferRing(null, wanted);
        }
        broadcast("The meeting has ended. Stay alert.", Formatting.YELLOW);
    }

    // ------------------------------------------------------------------ boss bar timer

    private void showBossBar() {
        removeBossBar();
        bossBar = new ServerBossBar(Text.literal("Discussion"), BossBar.Color.YELLOW, BossBar.Style.PROGRESS);
        for (PlayerData d : players.values()) {
            ServerPlayerEntity p = online(d.id);
            if (p != null) bossBar.addPlayer(p);
        }
        updateBossBar();
    }

    private void updateBossBar() {
        if (bossBar == null) return;
        Config c = Config.get();
        if (phase == Phase.DISCUSSION) {
            long total = Math.max(1, c.discussionSec * 20L), rem = Math.max(0, discussionEndsAt - now);
            bossBar.setName(Text.literal("Discussion - voting opens in " + (rem + 19) / 20 + "s"));
            bossBar.setColor(BossBar.Color.YELLOW);
            bossBar.setPercent(Math.min(1f, rem / (float) total));
        } else if (phase == Phase.VOTING) {
            long total = Math.max(1, c.votingSec * 20L), rem = Math.max(0, votingEndsAt - now);
            bossBar.setName(Text.literal("VOTE NOW - " + (rem + 19) / 20 + "s"));
            bossBar.setColor(BossBar.Color.RED);
            bossBar.setPercent(Math.min(1f, rem / (float) total));
        }
    }

    private void removeBossBar() {
        if (bossBar != null) { bossBar.clearPlayers(); bossBar = null; }
    }

    // ------------------------------------------------------------------ connection events

    public void onDisconnect(ServerPlayerEntity p) {
        if (phase == Phase.IDLE) return;
        PlayerData d = players.get(p.getUuid());
        if (d == null) return;
        if (d.alive) {
            d.alive = false;
            votes.remove(p.getUuid());
            broadcast(d.name + " left the game and was removed.", Formatting.GRAY);
            if (p.getUuid().equals(lantern)) transferRing(p.getUuid());
        }
        restorePlayer(p, d);
        checkWinAndEnd();
    }

    public void onJoin(ServerPlayerEntity p) {
        if (phase == Phase.IDLE) return;
        p.changeGameMode(GameMode.SPECTATOR);
        tell(p, "A game is in progress. You are spectating.", Formatting.GRAY);
    }

    // ------------------------------------------------------------------ misc queries

    /** Admin helper. role = manhunter | innocent | lantern | clear. Returns a status message. */
    public String forceRole(UUID id, String name, String role) {
        switch (role) {
            case "clear" -> { forced.remove(id); return "Cleared forced role for " + name + "."; }
            case "manhunter", "innocent", "lantern" -> {
                forced.put(id, role);
                String extra = role.equals("lantern") ? " (you get the ring when the first meeting ends, if you are alive)" : "";
                return name + " is guaranteed to be " + role.toUpperCase() + " from the next /lantern start" + extra + ".";
            }
            default -> { return "Unknown role. Use manhunter, innocent, lantern or clear."; }
        }
    }

    public boolean isLivingParticipant(ServerPlayerEntity p) { return phase != Phase.IDLE && isAlive(p.getUuid()); }
    public boolean isGhost(ServerPlayerEntity p) { PlayerData d = players.get(p.getUuid()); return phase != Phase.IDLE && d != null && !d.alive; }
    public boolean isBody(Entity e) { return e.getCommandTags().contains("lanterns_body"); }

    public Collection<String> livingNames() {
        List<String> n = new ArrayList<>();
        for (PlayerData d : players.values()) if (d.alive) n.add(d.name);
        return n;
    }

    public void sendGhostChat(ServerPlayerEntity sender, String text) {
        Text t = Text.literal("[Ghost] <" + sender.getName().getString() + "> " + text).formatted(Formatting.GRAY, Formatting.ITALIC);
        for (PlayerData d : players.values()) {
            if (d.alive) continue;
            ServerPlayerEntity p = online(d.id);
            if (p != null) p.sendMessage(t, false);
        }
    }

    public List<Text> status() {
        List<Text> out = new ArrayList<>();
        if (phase == Phase.IDLE) { out.add(tag("No game running.", Formatting.GRAY)); return out; }
        int alive = 0; for (PlayerData d : players.values()) if (d.alive) alive++;
        out.add(tag("Phase: " + phase + "  |  Alive: " + alive + "/" + players.size()
                + "  |  Manhunters at large: " + manhunterIds().size() + " at start (hidden)  |  Meetings held: " + meetingsHeld
                + "  |  Ring granted: " + (ringGranted ? "yes" : "not yet"), Formatting.YELLOW));
        out.add(tag("Living: " + String.join(", ", livingNames()), Formatting.GRAY));
        return out;
    }
}
