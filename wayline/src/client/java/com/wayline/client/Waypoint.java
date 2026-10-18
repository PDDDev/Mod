package com.wayline.client;

/** One saved coordinate. Plain fields so Gson can (de)serialize it directly. */
public class Waypoint {
	public String name;
	public int x, y, z;
	/** Dimension id, e.g. "minecraft:overworld". */
	public String dimension;
	public boolean visible = true;

	public Waypoint() {}

	public Waypoint(String name, int x, int y, int z, String dimension) {
		this.name = name;
		this.x = x;
		this.y = y;
		this.z = z;
		this.dimension = dimension;
	}
}
