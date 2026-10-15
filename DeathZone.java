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
	/** Side length in tiles of the square drawn, like AoE Warnings' size (3 = 3x3). 0 = the NPC's own footprint. */
	private final int size;
	private final int totalTicks;
	int ticksRemaining;

	DeathZone(WorldPoint southWestTile, int npcSize, int size, int ticks)
	{
		this.southWestTile = southWestTile;
		this.npcSize = npcSize;
		this.size = size;
		this.totalTicks = ticks;
		this.ticksRemaining = ticks;
	}

	int getAreaSize()
	{
		return size == 0 ? npcSize : size;
	}

	/**
	 * Tiles to shift west/south from the NPC's south-west tile so the square is centred on the NPC's
	 * footprint. When the sizes differ by an odd number the square leans one tile to the north-east,
	 * so it always lines up with real tiles.
	 */
	int getSouthWestOffsetTiles()
	{
		return Math.floorDiv(getAreaSize() - npcSize, 2);
	}
}
