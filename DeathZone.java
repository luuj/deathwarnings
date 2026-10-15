package net.runelite.client.plugins.deathwarnings;

import lombok.Getter;
import net.runelite.api.NPC;
import net.runelite.api.coords.WorldPoint;

/**
 * The area marked when a tracked NPC dies, anchored to the NPC's true (server-side) tile at death.
 */
@Getter
class DeathZone
{
	/** The NPC this area belongs to, so a wrong prediction can be withdrawn. */
	private final NPC npc;
	/** South-west tile of the NPC's footprint when it died (moved to the real death tile if it was predicted). */
	WorldPoint southWestTile;
	private final int npcSize;
	/** Tiles added outward on every side of the NPC's footprint (0 = just the footprint). */
	private final int radius;
	private final int totalTicks;
	int ticksRemaining;
	/** True for a predicted kill whose lethal hit hasn't landed yet. */
	boolean awaitingHit;
	/**
	 * Whether the countdown is running. A predicted area normally counts down through the projectile's
	 * travel time; it's only paused if no projectile could be found to time it, until the hit lands.
	 */
	boolean counting = true;

	DeathZone(NPC npc, WorldPoint southWestTile, int npcSize, int radius, int ticks)
	{
		this.npc = npc;
		this.southWestTile = southWestTile;
		this.npcSize = npcSize;
		this.radius = radius;
		this.totalTicks = ticks;
		this.ticksRemaining = ticks;
	}

	/** Side length in tiles of the square drawn: the footprint plus the radius on every side. */
	int getAreaSize()
	{
		return npcSize + 2 * radius;
	}
}
