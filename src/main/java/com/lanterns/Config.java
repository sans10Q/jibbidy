package com.lanterns;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;

import java.nio.file.Files;
import java.nio.file.Path;

/** JSON config at config/lanterns.json. Single-use limits (Verdict, self-shield) are NOT configurable. */
public class Config {
    public int configVersion = 0; // internal: used to migrate old config files
    public int manhunters = 1;
    public int minPlayers = 6;
    public int killCooldownSec = 60;
    public int initialKillCooldownSec = 30;
    public int postMeetingKillCooldownSec = 10;
    public double killRange = 3.5;
    public int blackoutDurationSec = 10;
    public int blackoutCooldownSec = 90;
    public int residualCooldownSec = 45;
    public int shieldDurationSec = 20;
    public int discussionSec = 45;
    public int votingSec = 30;
    public int emergencyMeetingsPerPlayer = 1;
    public int meetingGraceSec = 20;
    public int emergencyCooldownSec = 30;
    public double reportRange = 8.0;
    public double meetingFreeRadius = 3.0; // players may shuffle this far from the meeting point before being pulled back
    public boolean revealRoleOnDeath = false;
    public boolean revealVotesPublicly = false;
    public boolean returnPlayersAfterMeeting = true;
    public MeetingPoint meetingPoint = new MeetingPoint();

    public static class MeetingPoint {
        public boolean set = false;
        public String dimension = "minecraft:overworld";
        public double x, y, z;
        public float yaw, pitch;

        public RegistryKey<World> key() {
            return RegistryKey.of(RegistryKeys.WORLD, Identifier.of(dimension));
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Config instance = new Config();

    public static Config get() { return instance; }

    private static Path path() { return FabricLoader.getInstance().getConfigDir().resolve("lanterns.json"); }

    public static void load() {
        try {
            Path p = path();
            if (Files.exists(p)) {
                Config c = GSON.fromJson(Files.readString(p), Config.class);
                if (c != null) instance = c;
                if (instance.meetingPoint == null) instance.meetingPoint = new MeetingPoint();
            }
        } catch (Exception e) {
            LanternsMod.LOGGER.error("Failed to read config, using defaults", e);
        }
        if (instance.configVersion < 2) { // v1.5 defaults: 1 Manhunter, 45s discussion
            instance.manhunters = 1;
            instance.discussionSec = 45;
            instance.configVersion = 2;
        }
        save();
    }

    public static void save() {
        try {
            Files.writeString(path(), GSON.toJson(instance));
        } catch (Exception e) {
            LanternsMod.LOGGER.error("Failed to save config", e);
        }
    }
}
