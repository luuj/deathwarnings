package net.runelite.client.plugins.deathwarnings;

import lombok.Getter;
import net.runelite.api.coords.WorldPoint;

/**
 * The area marked when a tracked NPC dies, anchored to the NPC's true (server-side) tile at death.
 */
@Getter
class DeathZone
{
	/** South-west tile of the NPC's footprint when it died. */
	private final WorldPoint southWestTile;
	private final int npcSize;
	/** Tiles added outward on every side of the NPC's footprint (0 = just the footprint). */
	private final int radius;
	private final int totalTicks;
	int ticksRemaining;

	DeathZone(WorldPoint southWestTile, int npcSize, int radius, int ticks)
	{
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
