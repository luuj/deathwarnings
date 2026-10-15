package net.runelite.client.plugins.deathwarnings;

import com.google.common.base.Splitter;
import com.google.inject.Provides;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import javax.inject.Inject;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import com.google.common.collect.ImmutableMap;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Hitsplat;
import net.runelite.api.HitsplatID;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Player;
import net.runelite.api.Projectile;
import net.runelite.api.Skill;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.FakeXpDrop;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.NpcSpawned;
import net.runelite.api.events.StatChanged;
import net.runelite.api.gameval.NpcID;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.NPCManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.Text;

@PluginDescriptor(
	name = "<html><font color=#b82584>[J] Death Warnings",
	enabledByDefault = false,
	description = "Marks a configurable area around an NPC's true tile when it dies, optionally predicting your own kills",
	tags = {"death", "npc", "aoe", "explode", "combat", "overlay", "predict"}
)
@Slf4j
public class DeathWarningsPlugin extends Plugin
{
	private static final Splitter LINE_SPLITTER = Splitter.on('\n').omitEmptyStrings().trimResults();
	private static final int MAX_RADIUS = 7;
	private static final int MAX_TICKS = 100;
	private static final double MIN_XP_MULTIPLIER = 0.1;
	private static final double MAX_XP_MULTIPLIER = 5.0;

	/**
	 * If a predicted death hasn't happened (and the NPC still has HP) this many ticks after the
	 * prediction, the prediction was wrong and its area is removed.
	 */
	private static final int PREDICTION_GRACE_TICKS = 4;
	private static final int CYCLES_PER_TICK = 30;

	/**
	 * Max HP for NPCs RuneLite's NPC data doesn't cover. Demonic larvae (Doom of Mokhaiotl) use the same
	 * values as the Demonic Larva Tracker plugin.
	 */
	private static final Map<Integer, Integer> KNOWN_MAX_HP = ImmutableMap.<Integer, Integer>builder()
		.put(NpcID.DOM_DEMONIC_ENERGY, 2)
		.put(NpcID.DOM_DEMONIC_ENERGY_RANGE, 2)
		.put(NpcID.DOM_DEMONIC_ENERGY_MAGE, 2)
		.put(NpcID.DOM_DEMONIC_ENERGY_MELEE, 2)
		.put(NpcID.DOM_DEMONIC_ENERGY_GIANT_RANGE, 4)
		.put(NpcID.DOM_DEMONIC_ENERGY_GIANT_MAGE, 4)
		.build();

	/** Combat XP per point of damage, and Hitpoints XP per point of damage, before any NPC multiplier. */
	private static final double COMBAT_XP_PER_DAMAGE = 4.0;
	private static final double HITPOINTS_XP_PER_DAMAGE = 4.0 / 3.0;

	@Inject
	private Client client;

	@Inject
	private DeathWarningsConfig config;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private DeathWarningsOverlay overlay;

	@Inject
	private NPCManager npcManager;

	@Getter(AccessLevel.PACKAGE)
	private final List<DeathZone> zones = new ArrayList<>();

	private final Map<Integer, DeathRule> rulesById = new HashMap<>();
	private final Map<String, DeathRule> rulesByName = new HashMap<>();
	// Name patterns containing '*', checked in list order after exact names.
	private final List<WildcardRule> wildcardRules = new ArrayList<>();
	// Name -> matching rule (or none), so wildcard patterns run once per distinct NPC name, not per event.
	private final Map<String, Optional<DeathRule>> nameRuleCache = new HashMap<>();

	// NPCs already marked (by prediction or a real death), so later events don't mark them twice.
	private final Set<NPC> marked = new HashSet<>();

	// HP tracking for prediction, only for NPCs in the list that you've hit.
	private final Map<NPC, TrackedNpc> tracked = new HashMap<>();

	// XP bookkeeping: last known XP per skill and XP gained during the current tick.
	private final Map<Skill, Integer> lastXp = new EnumMap<>(Skill.class);
	private final Map<Skill, Integer> xpThisTick = new EnumMap<>(Skill.class);

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
		lastXp.clear();
		rulesById.clear();
		rulesByName.clear();
		wildcardRules.clear();
		nameRuleCache.clear();
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
			// The next login sends every skill's XP again; treat that as a fresh baseline, not as XP gained.
			lastXp.clear();
		}
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		Skill skill = event.getSkill();
		int xp = event.getXp();
		Integer previous = lastXp.put(skill, xp);
		if (previous != null && xp > previous)
		{
			xpThisTick.merge(skill, xp - previous, Integer::sum);
		}
	}

	/**
	 * Skills at 200M XP (and some other cases) don't actually gain XP; the game shows a "fake" drop instead.
	 * Count those too so prediction still works.
	 */
	@Subscribe
	public void onFakeXpDrop(FakeXpDrop event)
	{
		if (event.getXp() > 0)
		{
			xpThisTick.merge(event.getSkill(), event.getXp(), Integer::sum);
		}
	}

	@Subscribe
	public void onNpcSpawned(NpcSpawned event)
	{
		NPC npc = event.getNpc();
		DeathRule rule = config.predictDeaths() ? findRule(npc) : null;
		if (rule != null)
		{
			TrackedNpc t = startTracking(npc, rule);
			if (t != null)
			{
				tracked.put(npc, t);
			}
		}
	}

	@Subscribe
	public void onHitsplatApplied(HitsplatApplied event)
	{
		if (!(event.getActor() instanceof NPC))
		{
			return;
		}

		NPC npc = (NPC) event.getActor();
		TrackedNpc t = tracked.get(npc);
		if (t == null)
		{
			return;
		}

		// Every hitsplat is visible, including other players', so the HP estimate stays exact
		// as long as we've followed the NPC since it was at full health.
		Hitsplat hitsplat = event.getHitsplat();
		int amount = hitsplat.getAmount();
		if (hitsplat.getHitsplatType() == HitsplatID.HEAL)
		{
			t.hp += amount;
		}
		else
		{
			t.hp -= amount;
		}

		if (t.hp <= 0)
		{
			// The lethal hit has landed: a predicted area's countdown starts now.
			startCountdown(npc);
		}

		if (hitsplat.isMine())
		{
			// Usually this damage was already counted from an earlier XP drop and has now landed.
			// For melee the hitsplat arrives in the same tick as its XP, before GameTick, so keep any
			// damage that wasn't pending yet as credit to cancel out this tick's XP.
			int matched = Math.min(t.pendingDamage, amount);
			t.pendingDamage -= matched;
			t.landedBeforeXp += amount - matched;
		}
	}

	@Subscribe
	public void onActorDeath(ActorDeath event)
	{
		Actor actor = event.getActor();
		if (actor instanceof NPC)
		{
			markDeath((NPC) actor, false);
		}
	}

	@Subscribe
	public void onNpcDespawned(NpcDespawned event)
	{
		NPC npc = event.getNpc();
		// Fallback for NPCs that die without a 0-health update (and so never fire ActorDeath).
		if (npc.isDead() && !marked.contains(npc))
		{
			markDeath(npc, false);
		}
		else if (marked.contains(npc))
		{
			TrackedNpc t = tracked.get(npc);
			if (npc.isDead() || (t != null && t.hp <= 0))
			{
				startCountdown(npc);
			}
			else
			{
				// Left the scene alive while a prediction was waiting to land: drop that prediction.
				zones.removeIf(zone -> zone.getNpc() == npc && zone.awaitingHit);
			}
		}
		marked.remove(npc);
		tracked.remove(npc);
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		// StatChanged events for this tick arrive before GameTick, so all of this tick's XP is in.
		if (config.predictDeaths())
		{
			attributeOwnDamage();
			checkPredictions();
		}
		xpThisTick.clear();

		if (!zones.isEmpty())
		{
			zones.removeIf(zone -> zone.counting && --zone.ticksRemaining <= 0);
		}
	}

	/**
	 * Turns this tick's combat XP into a damage amount and adds it to your current target's pending damage.
	 * The XP drop arrives when the hit is decided, before the hitsplat shows (a tick or more later for
	 * ranged and magic), which is what makes the early prediction possible.
	 */
	private void attributeOwnDamage()
	{
		int hitpointsXp = xpThisTick.getOrDefault(Skill.HITPOINTS, 0);
		if (hitpointsXp <= 0)
		{
			// No Hitpoints XP means no damage from you this tick (a cannon gives Ranged XP only, for example).
			return;
		}

		Player local = client.getLocalPlayer();
		Actor target = local != null ? local.getInteracting() : null;
		if (!(target instanceof NPC))
		{
			return;
		}

		NPC npc = (NPC) target;
		DeathRule rule = findRule(npc);
		if (rule == null || marked.contains(npc))
		{
			return;
		}

		TrackedNpc t = tracked.get(npc);
		if (t == null)
		{
			// Not followed since it spawned (e.g. the plugin or list changed mid-fight). Start from the
			// health bar, which may already include this tick's hit, so don't add this tick's damage.
			t = startTracking(npc, rule);
			if (t != null)
			{
				tracked.put(npc, t);
			}
			return;
		}

		int damage = damageFromXp(hitpointsXp, rule.getXpMultiplier()) - t.landedBeforeXp;
		if (damage > 0)
		{
			t.pendingDamage += damage;
		}
	}

	/**
	 * Melee and ranged give 4 XP per damage across Attack/Strength/Defence/Ranged, which pins the damage down
	 * exactly. Magic adds a spell's base XP, so for magic fall back to Hitpoints XP (4/3 per damage). The client
	 * only sees whole XP, so a Hitpoints XP amount can fit two damage values; take the nearest, as the Demonic
	 * Larva Tracker does. A wrong guess is withdrawn after a few ticks.
	 */
	private int damageFromXp(int hitpointsXp, double multiplier)
	{
		int combatXp = xpThisTick.getOrDefault(Skill.ATTACK, 0)
			+ xpThisTick.getOrDefault(Skill.STRENGTH, 0)
			+ xpThisTick.getOrDefault(Skill.DEFENCE, 0)
			+ xpThisTick.getOrDefault(Skill.RANGED, 0);
		int magicXp = xpThisTick.getOrDefault(Skill.MAGIC, 0);

		if (combatXp > 0 && magicXp == 0)
		{
			return minDamageForXp(combatXp, COMBAT_XP_PER_DAMAGE * multiplier);
		}
		return (int) Math.round(hitpointsXp / (HITPOINTS_XP_PER_DAMAGE * multiplier));
	}

	/** Smallest damage d whose XP (d * xpPerDamage, rounded either way) could produce this whole-XP gain. */
	private static int minDamageForXp(int xp, double xpPerDamage)
	{
		return (int) Math.floor((xp - 1) / xpPerDamage) + 1;
	}

	private TrackedNpc startTracking(NPC npc, DeathRule rule)
	{
		Integer maxHp = rule.getMaxHp() > 0 ? Integer.valueOf(rule.getMaxHp()) : KNOWN_MAX_HP.get(npc.getId());
		if (maxHp == null)
		{
			maxHp = npcManager.getHealth(npc.getId());
		}
		if (maxHp == null || maxHp <= 0)
		{
			// Unknown max HP: can't predict this NPC, but its real death is still marked.
			return null;
		}

		int ratio = npc.getHealthRatio();
		int scale = npc.getHealthScale();
		int hp = maxHp;
		if (ratio >= 0 && scale > 0 && ratio < scale)
		{
			// Already damaged before we started following it: start from the health bar's upper bound.
			hp = (int) Math.ceil((double) maxHp * ratio / scale);
		}
		return new TrackedNpc(hp, maxHp);
	}

	private void checkPredictions()
	{
		if (tracked.isEmpty())
		{
			return;
		}

		int tick = client.getTickCount();
		for (Map.Entry<NPC, TrackedNpc> entry : tracked.entrySet())
		{
			NPC npc = entry.getKey();
			TrackedNpc t = entry.getValue();
			t.landedBeforeXp = 0;

			syncWithHealthBar(npc, t);

			if (t.predictedTick < 0)
			{
				if (t.hp - t.pendingDamage <= 0)
				{
					t.predictedTick = tick;
					// If the hit has already landed (melee), count down straight away; otherwise wait for it.
					markDeath(npc, t.hp > 0);
				}
			}
			else if (tick - t.predictedTick > PREDICTION_GRACE_TICKS && !npc.isDead() && t.hp > 0)
			{
				// It survived: withdraw the area and keep tracking with the pending damage cleared.
				zones.removeIf(zone -> zone.getNpc() == npc);
				marked.remove(npc);
				t.pendingDamage = 0;
				t.predictedTick = -1;
			}
		}
	}

	/**
	 * Keeps the estimate honest if something was missed (e.g. healing without a hitsplat): the real HP
	 * can't be above what the health bar allows.
	 */
	private static void syncWithHealthBar(NPC npc, TrackedNpc t)
	{
		int ratio = npc.getHealthRatio();
		int scale = npc.getHealthScale();
		if (ratio < 0 || scale <= 0)
		{
			return;
		}
		int upperBound = ratio == 0 ? 0 : (int) Math.ceil((double) t.maxHp * ratio / scale);
		t.hp = Math.min(t.hp, upperBound);
	}

	private DeathRule findRule(NPC npc)
	{
		DeathRule rule = rulesById.get(npc.getId());
		if (rule != null || npc.getName() == null)
		{
			return rule;
		}

		String name = Text.standardize(npc.getName());
		return nameRuleCache.computeIfAbsent(name, n ->
		{
			DeathRule exact = rulesByName.get(n);
			if (exact != null)
			{
				return Optional.of(exact);
			}
			for (WildcardRule wildcard : wildcardRules)
			{
				if (wildcard.pattern.matcher(n).matches())
				{
					return Optional.of(wildcard.rule);
				}
			}
			return Optional.empty();
		}).orElse(null);
	}

	/**
	 * Called when the NPC's lethal hit lands or it dies. A predicted area is re-synced to its full duration
	 * here, so any error in the travel-time estimate is corrected the moment the hit actually lands.
	 */
	private void startCountdown(NPC npc)
	{
		for (DeathZone zone : zones)
		{
			if (zone.getNpc() != npc)
			{
				continue;
			}
			if (zone.awaitingHit)
			{
				zone.awaitingHit = false;
				zone.ticksRemaining = zone.getTotalTicks();
			}
			zone.counting = true;
		}
	}

	/**
	 * Ticks until your projectile(s) heading for this NPC land, or 0 if none are in the air (melee, or
	 * an attack without a projectile).
	 */
	private int projectileTravelTicks(NPC npc)
	{
		Player local = client.getLocalPlayer();
		int maxCycles = 0;
		for (Projectile projectile : client.getProjectiles())
		{
			Actor source = projectile.getSourceActor();
			if (projectile.getTargetActor() == npc && (source == local || source == null))
			{
				maxCycles = Math.max(maxCycles, projectile.getRemainingCycles());
			}
		}
		return (maxCycles + CYCLES_PER_TICK - 1) / CYCLES_PER_TICK;
	}

	/**
	 * @param predicted true when marking from an XP-drop prediction whose hit hasn't landed yet; the
	 *                  area's countdown then waits for the hit.
	 */
	private void markDeath(NPC npc, boolean predicted)
	{
		if (rulesById.isEmpty() && rulesByName.isEmpty() && wildcardRules.isEmpty())
		{
			return;
		}

		DeathRule rule = findRule(npc);
		if (rule == null)
		{
			return;
		}

		if (!marked.add(npc))
		{
			// Already marked by a prediction: move the area to where it actually died, in case it
			// stepped after the prediction, and make sure its countdown is running.
			for (DeathZone zone : zones)
			{
				if (zone.getNpc() == npc)
				{
					zone.southWestTile = npc.getWorldLocation();
				}
			}
			startCountdown(npc);
			return;
		}

		// getWorldLocation() is the NPC's true (server-side) tile, not where its model is drawn mid-walk.
		NPCComposition composition = npc.getTransformedComposition();
		int size = composition != null ? composition.getSize() : 1;
		DeathZone zone = new DeathZone(npc, npc.getWorldLocation(), size, rule.getRadius(), rule.getTicks());
		if (predicted)
		{
			// Count down through the projectile's flight plus the full duration, so the number shown is
			// how long the area really has left. Without a projectile to time, pause until the hit lands.
			zone.awaitingHit = true;
			int travelTicks = projectileTravelTicks(npc);
			zone.ticksRemaining += travelTicks;
			zone.counting = travelTicks > 0;
		}
		zones.add(zone);
	}

	private void rebuildRules()
	{
		rulesById.clear();
		rulesByName.clear();
		wildcardRules.clear();
		nameRuleCache.clear();

		for (String line : LINE_SPLITTER.split(config.npcList()))
		{
			String[] parts = line.split(",");
			if (parts.length < 3 || parts.length > 5)
			{
				log.warn("Ignoring malformed death warning entry: {}", line);
				continue;
			}

			String key = parts[0].trim();
			int radius;
			int ticks;
			double xpMultiplier = 1.0;
			int maxHp = 0;
			try
			{
				radius = Integer.parseInt(parts[1].trim());
				ticks = Integer.parseInt(parts[2].trim());
				if (parts.length >= 4 && !parts[3].trim().isEmpty())
				{
					xpMultiplier = Double.parseDouble(parts[3].trim());
				}
				if (parts.length == 5)
				{
					maxHp = Integer.parseInt(parts[4].trim());
				}
			}
			catch (NumberFormatException ex)
			{
				log.warn("Ignoring death warning entry with invalid numbers: {}", line);
				continue;
			}

			if (key.isEmpty() || radius < 0 || radius > MAX_RADIUS || ticks < 1 || ticks > MAX_TICKS
				|| xpMultiplier < MIN_XP_MULTIPLIER || xpMultiplier > MAX_XP_MULTIPLIER || maxHp < 0)
			{
				log.warn("Ignoring out-of-range death warning entry: {}", line);
				continue;
			}

			DeathRule rule = new DeathRule(radius, ticks, xpMultiplier, maxHp);
			if (key.chars().allMatch(Character::isDigit))
			{
				rulesById.put(Integer.parseInt(key), rule);
			}
			else if (key.indexOf('*') >= 0)
			{
				wildcardRules.add(new WildcardRule(wildcardToPattern(Text.standardize(key)), rule));
			}
			else
			{
				rulesByName.put(Text.standardize(key), rule);
			}
		}
	}

	/** "*demonic*larva*" -> a pattern where each '*' matches any text (including none) and the rest is literal. */
	private static Pattern wildcardToPattern(String key)
	{
		StringBuilder regex = new StringBuilder();
		String[] parts = key.split("\\*", -1);
		for (int i = 0; i < parts.length; i++)
		{
			if (i > 0)
			{
				regex.append(".*");
			}
			if (!parts[i].isEmpty())
			{
				regex.append(Pattern.quote(parts[i]));
			}
		}
		return Pattern.compile(regex.toString());
	}

	private static final class WildcardRule
	{
		final Pattern pattern;
		final DeathRule rule;

		WildcardRule(Pattern pattern, DeathRule rule)
		{
			this.pattern = pattern;
			this.rule = rule;
		}
	}

	private void reset()
	{
		zones.clear();
		marked.clear();
		tracked.clear();
		xpThisTick.clear();
	}

	private static final class TrackedNpc
	{
		final int maxHp;
		/** Current HP from hitsplats (exact if followed from full health). */
		int hp;
		/** Your damage known from XP drops whose hitsplats haven't landed yet. */
		int pendingDamage;
		/** Your hitsplat damage that landed this tick before its XP was processed (melee). */
		int landedBeforeXp;
		/** Tick the death was predicted on, or -1. */
		int predictedTick = -1;

		TrackedNpc(int hp, int maxHp)
		{
			this.hp = hp;
			this.maxHp = maxHp;
		}
	}
}
