package com.wayline.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Loads/saves waypoints grouped by world key (one list per singleplayer save or server). */
public final class WaypointStore {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("wayline.json");

	private static class Data {
		boolean hudEnabled = true;
		Map<String, List<Waypoint>> worlds = new HashMap<>();
	}

	private static Data data = new Data();

	private WaypointStore() {}

	public static void load() {
		try {
			if (Files.exists(FILE)) {
				Data loaded = GSON.fromJson(Files.readString(FILE), Data.class);
				if (loaded != null && loaded.worlds != null) data = loaded;
			}
		} catch (Exception e) {
			System.err.println("[Wayline] Could not read " + FILE + ": " + e);
		}
	}

	public static void save() {
		try {
			Files.writeString(FILE, GSON.toJson(data));
		} catch (IOException e) {
			System.err.println("[Wayline] Could not write " + FILE + ": " + e);
		}
	}

	public static List<Waypoint> forWorld(String worldKey) {
		return data.worlds.computeIfAbsent(worldKey, k -> new ArrayList<>());
	}

	public static Waypoint find(String worldKey, String name) {
		for (Waypoint w : forWorld(worldKey)) {
			if (w.name.equalsIgnoreCase(name)) return w;
		}
		return null;
	}

	public static List<String> names(String worldKey) {
		return forWorld(worldKey).stream().map(w -> w.name).toList();
	}

	public static boolean hudEnabled() {
		return data.hudEnabled;
	}

	public static void setHudEnabled(boolean on) {
		data.hudEnabled = on;
		save();
	}
}
