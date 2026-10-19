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
		name = "Terrain distance",
		description = "How far the ground is drawn, in tiles. Nothing is drawn beyond it, and the haze thickens "
			+ "towards it",
		position = 1
	)
	default int viewDistance()
	{
		return 1500;
	}

	@Range(min = 0, max = 10000)
	@ConfigItem(
		keyName = "landmarkRange",
		name = "Structure distance",
		description = "How far buildings, trees and other big or tall things are drawn, in tiles",
		position = 2
	)
	default int landmarkDistance()
	{
		return 1000;
	}

	@Range(min = 0, max = 10000)
	@ConfigItem(
		keyName = "sceneryRange",
		name = "Scenery distance",
		description = "How far all scenery (fences, rocks, small objects) is drawn, in tiles. Performance heavy",
		position = 3
	)
	default int sceneryDistance()
	{
		return 320;
	}
}
