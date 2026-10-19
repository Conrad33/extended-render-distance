package com.horizon;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

@ConfigGroup(HorizonConfig.GROUP)
public interface HorizonConfig extends Config
{
	String GROUP = "horizon";

	@Range(min = 200, max = 4000)
	@ConfigItem(
		keyName = "viewRange",
		name = "View distance",
		description = "How far the world is drawn, in tiles. The haze thickens towards it",
		position = 1
	)
	default int viewDistance()
	{
		return 1500;
	}

	@Range(min = 0, max = 800)
	@ConfigItem(
		keyName = "sceneryRange",
		name = "Scenery distance",
		description = "How far all scenery (fences, rocks, small objects) is drawn, in tiles",
		position = 2
	)
	default int sceneryDistance()
	{
		return 320;
	}

	@Range(min = 0, max = 3000)
	@ConfigItem(
		keyName = "landmarkRange",
		name = "Building distance",
		description = "How far buildings (walls and roofs) and tall things (trees, towers) are drawn, in tiles",
		position = 3
	)
	default int landmarkDistance()
	{
		return 1000;
	}

	@ConfigItem(
		keyName = "hideEdgeFog",
		name = "Hide the GPU plugin's edge fog",
		description = "The GPU plugin fades the normal view into the sky at its edge; with the far world behind it, that "
			+ "shows as a ring of sky",
		position = 4
	)
	default boolean hideEdgeFog()
	{
		return true;
	}
}
