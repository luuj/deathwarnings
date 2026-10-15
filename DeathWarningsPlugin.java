package net.runelite.client.plugins.deathwarnings;

import com.google.common.base.Splitter;
import com.google.inject.Provides;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.GameState;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.NpcDespawned;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.Text;

@PluginDescriptor(
	name = "<html><font color=#b82584>[J] Death Warnings",
	enabledByDefault = false,
	description = "Marks a configurable area around an NPC's true tile when it dies",
	tags = {"death", "npc", "aoe", "explode", "combat", "overlay"}
)
@Slf4j
public class DeathWarningsPlugin extends Plugin
{
	private static final Splitter LINE_SPLITTER = Splitter.on('\n').omitEmptyStrings().trimResults();
	private static final int MAX_RADIUS = 7;
	private static final int MAX_TICKS = 100;

	@Inject
	private DeathWarningsConfig config;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private DeathWarningsOverlay overlay;

	@Getter(AccessLevel.PACKAGE)
	private final List<DeathZone> zones = new ArrayList<>();

	private final Map<Integer, DeathRule> rulesById = new HashMap<>();
	private final Map<String, DeathRule> rulesByName = new HashMap<>();

	// NPCs already marked via ActorDeath, so the despawn fallback doesn't mark them twice.
	private final Set<NPC> marked = new HashSet<>();

	@Provides
	DeathWarningsConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(DeathWarningsConfig.class);
	}

	@Override
	protected void startUp()
	{
		rebuildRules();
		overlayManager.add(overlay);
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(overlay);
		reset();
		rulesById.clear();
		rulesByName.clear();
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (DeathWarningsConfig.GROUP.equals(event.getGroup()) && "npcList".equals(event.getKey()))
		{
			rebuildRules();
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		GameState state = event.getGameState();
		if (state == GameState.LOGIN_SCREEN || state == GameState.HOPPING)
		{
			reset();
		}
	}

	@Subscribe
	public void onActorDeath(ActorDeath event)
	{
		Actor actor = event.getActor();
		if (actor instanceof NPC)
		{
			markDeath((NPC) actor);
		}
	}

	@Subscribe
	public void onNpcDespawned(NpcDespawned event)
	{
		NPC npc = event.getNpc();
		// Fallback for NPCs that die without a 0-health update (and so never fire ActorDeath).
		if (npc.isDead() && !marked.contains(npc))
		{
			markDeath(npc);
		}
		marked.remove(npc);
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (!zones.isEmpty())
		{
			zones.removeIf(zone -> --zone.ticksRemaining <= 0);
		}
	}

	private void markDeath(NPC npc)
	{
		if (rulesById.isEmpty() && rulesByName.isEmpty())
		{
			return;
		}

		DeathRule rule = rulesById.get(npc.getId());
		if (rule == null && npc.getName() != null)
		{
			rule = rulesByName.get(Text.standardize(npc.getName()));
		}
		if (rule == null || !marked.add(npc))
		{
			return;
		}

		// getWorldLocation() is the NPC's true (server-side) tile, not where its model is drawn mid-walk.
		WorldPoint trueTile = npc.getWorldLocation();
		NPCComposition composition = npc.getTransformedComposition();
		int size = composition != null ? composition.getSize() : 1;
		zones.add(new DeathZone(trueTile, size, rule.getRadius(), rule.getTicks()));
	}

	private void rebuildRules()
	{
		rulesById.clear();
		rulesByName.clear();

		for (String line : LINE_SPLITTER.split(config.npcList()))
		{
			String[] parts = line.split(",");
			if (parts.length != 3)
			{
				log.warn("Ignoring malformed death warning entry: {}", line);
				continue;
			}

			String key = parts[0].trim();
			int radius;
			int ticks;
			try
			{
				radius = Integer.parseInt(parts[1].trim());
				ticks = Integer.parseInt(parts[2].trim());
			}
			catch (NumberFormatException ex)
			{
				log.warn("Ignoring death warning entry with invalid numbers: {}", line);
				continue;
			}

			if (key.isEmpty() || radius < 0 || radius > MAX_RADIUS || ticks < 1 || ticks > MAX_TICKS)
			{
				log.warn("Ignoring out-of-range death warning entry: {}", line);
				continue;
			}

			DeathRule rule = new DeathRule(radius, ticks);
			if (key.chars().allMatch(Character::isDigit))
			{
				rulesById.put(Integer.parseInt(key), rule);
			}
			else
			{
				rulesByName.put(Text.standardize(key), rule);
			}
		}
	}

	private void reset()
	{
		zones.clear();
		marked.clear();
	}
}
