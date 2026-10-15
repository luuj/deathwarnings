package net.runelite.client.plugins.deathwarnings;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Polygon;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;

class DeathWarningsOverlay extends Overlay
{
	private final Client client;
	private final DeathWarningsPlugin plugin;
	private final DeathWarningsConfig config;

	private BasicStroke stroke;
	private int strokeWidth = -1;

	@Inject
	private DeathWarningsOverlay(Client client, DeathWarningsPlugin plugin, DeathWarningsConfig config)
	{
		this.client = client;
		this.plugin = plugin;
		this.config = config;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
		setPriority(PRIORITY_MED);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (plugin.getZones().isEmpty())
		{
			return null;
		}

		final Player player = client.getLocalPlayer();
		if (player == null)
		{
			return null;
		}

		final WorldPoint playerLocation = player.getWorldLocation();
		final int renderDistance = config.renderDistance();
		final Color border = config.borderColor();
		final Color fill = config.fillColor();
		final boolean showTicks = config.showTicks();
		final boolean fade = config.fade();
		graphics.setStroke(getStroke(config.borderWidth()));
		final Font oldFont = graphics.getFont();
		graphics.setFont(config.font().getFont());

		for (DeathZone zone : plugin.getZones())
		{
			// Distance to the nearest edge of the marked area, so large areas aren't cut off early.
			// distanceTo returns Integer.MAX_VALUE for a different plane.
			if (zone.getSouthWestTile().distanceTo(playerLocation) - zone.getNpcSize() - zone.getRadius() > renderDistance)
			{
				continue;
			}

			// fromWorld(Client, ...) finds the right world view (top-level or e.g. a boat) for the tile,
			// and returns null if the tile isn't loaded or is on another plane.
			LocalPoint southWest = LocalPoint.fromWorld(client, zone.getSouthWestTile());
			if (southWest == null)
			{
				continue;
			}

			// Centre of the NPC's footprint. The radius grows the square evenly on every side,
			// so the centre doesn't move.
			int offset = (zone.getNpcSize() - 1) * Perspective.LOCAL_HALF_TILE_SIZE;
			LocalPoint center = southWest.plus(offset, offset);

			Polygon poly = Perspective.getCanvasTileAreaPoly(client, center, zone.getAreaSize());
			if (poly == null)
			{
				continue;
			}

			double alphaScale = fade ? (double) zone.getTicksRemaining() / zone.getTotalTicks() : 1.0;
			graphics.setColor(scaleAlpha(fill, alphaScale));
			graphics.fill(poly);
			graphics.setColor(scaleAlpha(border, alphaScale));
			graphics.draw(poly);

			if (showTicks)
			{
				String text = Integer.toString(zone.getTicksRemaining());
				Point textLoc = Perspective.getCanvasTextLocation(client, graphics, center, text, 0);
				if (textLoc != null)
				{
					OverlayUtil.renderTextLocation(graphics, textLoc, text, Color.WHITE);
				}
			}
		}

		graphics.setFont(oldFont);
		return null;
	}

	private BasicStroke getStroke(int width)
	{
		if (stroke == null || strokeWidth != width)
		{
			stroke = new BasicStroke(width);
			strokeWidth = width;
		}
		return stroke;
	}

	private static Color scaleAlpha(Color color, double scale)
	{
		if (scale >= 1.0)
		{
			return color;
		}
		int alpha = (int) Math.round(color.getAlpha() * Math.max(0.0, scale));
		return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
	}
}
