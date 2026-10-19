package com.extendedrenderdistance;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

@ConfigGroup(ExtendedRenderDistanceConfig.GROUP)
public interface ExtendedRenderDistanceConfig extends Config
{
	String GROUP = "extendedrenderdistance";

	@Range(min = 200, max = 10000)
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

	@Range(min = 0, max = 10000)
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

	@Range(min = 0, max = 10000)
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
}
