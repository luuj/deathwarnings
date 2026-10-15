package net.runelite.client.plugins.deathwarnings;

import java.awt.Color;
import net.runelite.client.config.Alpha;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.FontType;
import net.runelite.client.config.Range;
import net.runelite.client.config.Units;

@ConfigGroup(DeathWarningsConfig.GROUP)
public interface DeathWarningsConfig extends Config
{
	String GROUP = "deathwarnings";

	@ConfigSection(
		name = "NPCs",
		description = "Which NPC deaths to mark",
		position = 0
	)
	String npcSection = "npcSection";

	@ConfigSection(
		name = "Appearance",
		description = "How the overlay is drawn",
		position = 1
	)
	String appearanceSection = "appearanceSection";

	@ConfigItem(
		keyName = "npcList",
		name = "NPC list",
		description = "One entry per line: NPC name or ID, radius, duration in ticks, then optionally an XP multiplier "
			+ "and a max HP. Radius is tiles outward from the NPC's footprint, up to 7: 0 = just the tiles it occupied, "
			+ "1 on a one-tile NPC = 3x3. XP multiplier is only for NPCs that give bonus combat XP (default 1). "
			+ "Max HP is only needed for NPCs RuneLite has no HP data for. "
			+ "Names can use * as a wildcard. Example: *demonic*larva*, 1, 2",
		position = 0,
		section = npcSection
	)
	default String npcList()
	{
		return "";
	}

	@ConfigItem(
		keyName = "predictDeaths",
		name = "Predict deaths",
		description = "Mark the area as soon as your own hit is known to be lethal (from your XP drop), "
			+ "before the hitsplat and death animation. Only your own hits are predicted.",
		position = 1,
		section = npcSection
	)
	default boolean predictDeaths()
	{
		return true;
	}

	@Alpha
	@ConfigItem(
		keyName = "borderColor",
		name = "Border color",
		description = "Outline color of the marked area",
		position = 0,
		section = appearanceSection
	)
	default Color borderColor()
	{
		return new Color(255, 140, 0, 220);
	}

	@Alpha
	@ConfigItem(
		keyName = "fillColor",
		name = "Fill color",
		description = "Fill color of the marked area",
		position = 1,
		section = appearanceSection
	)
	default Color fillColor()
	{
		return new Color(255, 140, 0, 40);
	}

	@Range(min = 1, max = 5)
	@ConfigItem(
		keyName = "borderWidth",
		name = "Border width",
		description = "Thickness of the outline",
		position = 2,
		section = appearanceSection
	)
	default int borderWidth()
	{
		return 2;
	}

	@ConfigItem(
		keyName = "showTicks",
		name = "Show ticks remaining",
		description = "Show how many ticks each marked area will stay up",
		position = 3,
		section = appearanceSection
	)
	default boolean showTicks()
	{
		return true;
	}

	@ConfigItem(
		keyName = "fade",
		name = "Fade out",
		description = "Fade the areas as their duration runs out",
		position = 4,
		section = appearanceSection
	)
	default boolean fade()
	{
		return false;
	}

	@ConfigItem(
		keyName = "font",
		name = "Font",
		description = "Font used for the overlay text",
		position = 5,
		section = appearanceSection
	)
	default FontType font()
	{
		return FontType.BOLD.withSize(16);
	}

	@Range(min = 1, max = 20)
	@Units(" tiles")
	@ConfigItem(
		keyName = "renderDistance",
		name = "Render distance",
		description = "Only draw within this many tiles of your player",
		position = 6,
		section = appearanceSection
	)
	default int renderDistance()
	{
		return 20;
	}
}
