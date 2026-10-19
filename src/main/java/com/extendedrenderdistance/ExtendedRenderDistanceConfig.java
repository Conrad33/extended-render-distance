package com.extendedrenderdistance;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

@ConfigGroup(ExtendedRenderDistanceConfig.GROUP)
public interface ExtendedRenderDistanceConfig extends Config
{
	String GROUP = "extendedrenderdistance";

	@Range(min = 200, max = 5000)
	@ConfigItem(
		keyName = "terrainDistance",
		name = "Terrain distance",
		description = "How far the terrain is rendered, in tiles.",
		position = 1
	)
	default int terrainDistance()
	{
		return 5000;
	}

	@Range(min = 0, max = 5000)
	@ConfigItem(
		keyName = "structureDistance",
		name = "Structure distance",
		description = "How far structures (buildings, trees, etc) are rendered, in tiles.",
		position = 2
	)
	default int structureDistance()
	{
		return 5000;
	}

	@Range(min = 0, max = 5000)
	@ConfigItem(
		keyName = "sceneryDistance",
		name = "Scenery distance",
		description = "How far scenery (grass, rocks, etc) are rendered, in tiles. (Performance heavy)",
		position = 3
	)
	default int sceneryDistance()
	{
		// the GPU plugin's farthest draw distance
		return 184;
	}
}
