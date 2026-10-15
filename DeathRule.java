package net.runelite.client.plugins.deathwarnings;

import lombok.Value;

@Value
class DeathRule
{
	int radius;
	int ticks;
	/** Combat XP multiplier for NPCs that give bonus XP; 1.0 for most. Used to turn XP drops into damage. */
	double xpMultiplier;
	/** Max HP override for NPCs RuneLite has no HP data for; 0 = use built-in/RuneLite data. */
	int maxHp;
}
