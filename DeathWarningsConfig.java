package net.runelite.client.plugins.deathwarnings;

import java.awt.Color;
import net.runelite.client.config.Alpha;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;

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
		description = "How death areas are drawn",
		position = 1
	)
	String appearanceSection = "appearanceSection";

	@ConfigItem(
		keyName = "npcList",
		name = "NPC list",
		description = "One entry per line: NPC name or ID, size, duration in ticks. "
			+ "Size works like AoE Warnings: 1 = one tile, 3 = 3x3, centred on the NPC (0 = the NPC's own footprint). "
			+ "Example: Nylocas Matomenos, 3, 4",
		position = 0,
		section = npcSection
	)
	default String npcList()
	{
		return "";
	}

	@Alpha
	@ConfigItem(
		keyName = "borderColor",
		name = "Border color",
		description = "Outline color of the death area",
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
		description = "Fill color of the death area",
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
		description = "Show how many ticks the death area will stay up",
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
		description = "Fade the area as its duration runs out",
		position = 4,
		section = appearanceSection
	)
	default boolean fade()
	{
		return false;
	}
}
