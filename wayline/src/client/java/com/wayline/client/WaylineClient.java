package com.wayline.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

import java.util.Comparator;
import java.util.List;

/**
 * Wayline: per-world coordinate bookmarks.
 *   /wp add <name> [x y z]   /wp remove <name>   /wp list
 *   /wp show|hide|toggle <name>   /wp showall|hideall   /wp hud
 * Visible waypoints of the current dimension are listed on the HUD with distance + direction arrow.
 * Everything is local; nothing is sent to the server.
 */
public class WaylineClient implements ClientModInitializer {
	private static final String[] ARROWS = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};
	private static final int MAX_HUD_ROWS = 8;
	private static KeyMapping toggleHudKey;

	private static final SuggestionProvider<FabricClientCommandSource> NAMES =
			(ctx, builder) -> SharedSuggestionProvider.suggest(WaypointStore.names(worldKey()), builder);

	@Override
	public void onInitializeClient() {
		WaypointStore.load();

		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("wayline", "main"));
		toggleHudKey = KeyMappingHelper.registerKeyMapping(
				new KeyMapping("key.wayline.toggle_hud", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_J, category));

		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			trackDeath(mc);
			while (toggleHudKey.consumeClick()) {
				WaypointStore.setHudEnabled(!WaypointStore.hudEnabled());
				if (mc.player != null) {
					mc.gui.hud.setOverlayMessage(Component.literal("Waypoint HUD: " + (WaypointStore.hudEnabled() ? "ON" : "OFF")), false);
				}
			}
		});

		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("wayline", "hud"), WaylineClient::renderHud);
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> dispatcher.register(buildCommand()));
	}

	// ---------------------------------------------------------------- auto death waypoint

	private static boolean wasDead = false;

	/** Saves a "death-N" waypoint where you died (visible by default; hide/remove it with /wp). */
	private static void trackDeath(Minecraft mc) {
		LocalPlayer p = mc.player;
		if (p == null || mc.level == null) {
			wasDead = false;
			return;
		}
		boolean dead = p.isDeadOrDying();
		if (dead && !wasDead) {
			String key = worldKey();
			int n = 1;
			while (WaypointStore.find(key, "death-" + n) != null) n++;
			String name = "death-" + n;
			WaypointStore.forWorld(key).add(new Waypoint(name, p.getBlockX(), p.getBlockY(), p.getBlockZ(), dimensionId()));
			WaypointStore.save();
			mc.gui.hud.getChat().addClientSystemMessage(Component.literal(
					"[Wayline] Saved '" + name + "' at " + p.getBlockX() + ", " + p.getBlockY() + ", " + p.getBlockZ())
					.withStyle(ChatFormatting.RED));
		}
		wasDead = dead;
	}

	// ---------------------------------------------------------------- commands

	private static LiteralArgumentBuilder<FabricClientCommandSource> buildCommand() {
		return ClientCommands.literal("wp")
				.then(ClientCommands.literal("add")
						.then(ClientCommands.argument("name", StringArgumentType.word())
								.executes(ctx -> {
									LocalPlayer p = ctx.getSource().getPlayer();
									return add(ctx.getSource(), StringArgumentType.getString(ctx, "name"),
											p.getBlockX(), p.getBlockY(), p.getBlockZ());
								})
								.then(ClientCommands.argument("x", IntegerArgumentType.integer())
										.then(ClientCommands.argument("y", IntegerArgumentType.integer())
												.then(ClientCommands.argument("z", IntegerArgumentType.integer())
														.executes(ctx -> add(ctx.getSource(),
																StringArgumentType.getString(ctx, "name"),
																IntegerArgumentType.getInteger(ctx, "x"),
																IntegerArgumentType.getInteger(ctx, "y"),
																IntegerArgumentType.getInteger(ctx, "z"))))))))
				.then(ClientCommands.literal("remove")
						.then(ClientCommands.argument("name", StringArgumentType.word()).suggests(NAMES)
								.executes(ctx -> remove(ctx.getSource(), StringArgumentType.getString(ctx, "name")))))
				.then(ClientCommands.literal("show")
						.then(ClientCommands.argument("name", StringArgumentType.word()).suggests(NAMES)
								.executes(ctx -> setVisible(ctx.getSource(), StringArgumentType.getString(ctx, "name"), 1))))
				.then(ClientCommands.literal("hide")
						.then(ClientCommands.argument("name", StringArgumentType.word()).suggests(NAMES)
								.executes(ctx -> setVisible(ctx.getSource(), StringArgumentType.getString(ctx, "name"), 0))))
				.then(ClientCommands.literal("toggle")
						.then(ClientCommands.argument("name", StringArgumentType.word()).suggests(NAMES)
								.executes(ctx -> setVisible(ctx.getSource(), StringArgumentType.getString(ctx, "name"), -1))))
				.then(ClientCommands.literal("showall").executes(ctx -> setAll(ctx.getSource(), true)))
				.then(ClientCommands.literal("hideall").executes(ctx -> setAll(ctx.getSource(), false)))
				.then(ClientCommands.literal("list").executes(ctx -> list(ctx.getSource())))
				.then(ClientCommands.literal("hud").executes(ctx -> {
					WaypointStore.setHudEnabled(!WaypointStore.hudEnabled());
					ctx.getSource().sendFeedback(Component.literal("Waypoint HUD: " + (WaypointStore.hudEnabled() ? "ON" : "OFF")));
					return 1;
				}))
				.executes(ctx -> {
					ctx.getSource().sendFeedback(Component.literal(
							"/wp add <name> [x y z] | remove <name> | list | show/hide/toggle <name> | showall | hideall | hud"));
					return 1;
				});
	}

	private static int add(FabricClientCommandSource src, String name, int x, int y, int z) {
		String key = worldKey();
		if (WaypointStore.find(key, name) != null) {
			src.sendError(Component.literal("A waypoint named '" + name + "' already exists in this world."));
			return 0;
		}
		WaypointStore.forWorld(key).add(new Waypoint(name, x, y, z, dimensionId()));
		WaypointStore.save();
		src.sendFeedback(Component.literal("Saved '" + name + "' at " + x + ", " + y + ", " + z + " (" + dimensionId() + ")"));
		return 1;
	}

	private static int remove(FabricClientCommandSource src, String name) {
		Waypoint w = WaypointStore.find(worldKey(), name);
		if (w == null) {
			src.sendError(Component.literal("No waypoint named '" + name + "' in this world."));
			return 0;
		}
		WaypointStore.forWorld(worldKey()).remove(w);
		WaypointStore.save();
		src.sendFeedback(Component.literal("Removed '" + w.name + "'."));
		return 1;
	}

	/** mode: 1 = show, 0 = hide, -1 = toggle. */
	private static int setVisible(FabricClientCommandSource src, String name, int mode) {
		Waypoint w = WaypointStore.find(worldKey(), name);
		if (w == null) {
			src.sendError(Component.literal("No waypoint named '" + name + "' in this world."));
			return 0;
		}
		w.visible = mode < 0 ? !w.visible : mode == 1;
		WaypointStore.save();
		src.sendFeedback(Component.literal("'" + w.name + "' is now " + (w.visible ? "shown" : "hidden") + "."));
		return 1;
	}

	private static int setAll(FabricClientCommandSource src, boolean visible) {
		List<Waypoint> all = WaypointStore.forWorld(worldKey());
		all.forEach(w -> w.visible = visible);
		WaypointStore.save();
		src.sendFeedback(Component.literal((visible ? "Showing " : "Hiding ") + all.size() + " waypoint(s)."));
		return all.size();
	}

	private static int list(FabricClientCommandSource src) {
		List<Waypoint> all = WaypointStore.forWorld(worldKey());
		if (all.isEmpty()) {
			src.sendFeedback(Component.literal("No waypoints in this world. Use /wp add <name>."));
			return 0;
		}
		for (Waypoint w : all) {
			src.sendFeedback(Component.literal((w.visible ? "[shown]  " : "[hidden] ") + w.name + "  "
					+ w.x + ", " + w.y + ", " + w.z + "  (" + w.dimension + ")")
					.withStyle(w.visible ? ChatFormatting.GREEN : ChatFormatting.GRAY));
		}
		return all.size();
	}

	// ---------------------------------------------------------------- HUD

	private static void renderHud(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (player == null || mc.level == null || mc.gui.hud.isHidden() || !WaypointStore.hudEnabled()) return;

		String dim = dimensionId();
		double px = player.getX(), py = player.getY(), pz = player.getZ();
		float yaw = player.getYRot();

		List<Waypoint> shown = WaypointStore.forWorld(worldKey()).stream()
				.filter(w -> w.visible && w.dimension.equals(dim))
				.sorted(Comparator.comparingDouble(w -> sqDist(w, px, py, pz)))
				.limit(MAX_HUD_ROWS)
				.toList();

		int y = 4;
		for (Waypoint w : shown) {
			double dx = w.x + 0.5 - px, dz = w.z + 0.5 - pz;
			double dist = Math.sqrt(sqDist(w, px, py, pz));
			float target = (float) Math.toDegrees(Math.atan2(-dx, dz));
			float rel = Mth.wrapDegrees(target - yaw);
			String arrow = ARROWS[Math.floorMod(Math.round(rel / 45f), 8)];
			String line = arrow + " " + w.name + "  " + Math.round(dist) + "m";
			graphics.text(mc.font, line, 6, y, 0xFFFFFFFF, true);
			y += 11;
		}
	}

	private static double sqDist(Waypoint w, double px, double py, double pz) {
		double dx = w.x + 0.5 - px, dy = w.y - py, dz = w.z + 0.5 - pz;
		return dx * dx + dy * dy + dz * dz;
	}

	// ---------------------------------------------------------------- world identity

	private static String dimensionId() {
		Minecraft mc = Minecraft.getInstance();
		return mc.level == null ? "unknown" : mc.level.dimension().identifier().toString();
	}

	/** "sp:<save name>" for singleplayer, "mp:<server address>" for multiplayer. */
	static String worldKey() {
		Minecraft mc = Minecraft.getInstance();
		try {
			if (mc.getSingleplayerServer() != null) {
				return "sp:" + mc.getSingleplayerServer().getWorldData().getLevelName();
			}
			if (mc.getCurrentServer() != null) {
				return "mp:" + mc.getCurrentServer().ip.toLowerCase();
			}
		} catch (Exception ignored) {
			// fall through
		}
		return "unknown";
	}
}
