package com.extendedrenderdistance;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

@ConfigGroup(ExtendedRenderDistanceConfig.GROUP)
public interface ExtendedRenderDistanceConfig extends Config
{
	String GROUP = "extendedrenderdistance";

	/** The distances' limits, in tiles. */
	int MIN_TERRAIN = 200, MAX_DISTANCE = 5000;

	String TERRAIN_NAME = "Terrain distance";
	String TERRAIN_DESCRIPTION = "How far the terrain is rendered, in tiles.";
	String STRUCTURE_NAME = "Structure distance";
	String STRUCTURE_DESCRIPTION = "How far structures (buildings, trees, etc) are rendered, in tiles.";
	String SCENERY_NAME = "Scenery distance";
	String SCENERY_DESCRIPTION = "How far scenery (grass, rocks, etc) are rendered, in tiles. (Performance heavy)";

	@Range(min = MIN_TERRAIN, max = MAX_DISTANCE)
	@ConfigItem(
		keyName = "terrainDistance",
		name = TERRAIN_NAME,
		description = TERRAIN_DESCRIPTION,
		position = 1
	)
	default int terrainDistance()
	{
		return 5000;
	}

	@Range(min = 0, max = MAX_DISTANCE)
	@ConfigItem(
		keyName = "structureDistance",
		name = STRUCTURE_NAME,
		description = STRUCTURE_DESCRIPTION,
		position = 2
	)
	default int structureDistance()
	{
		return 5000;
	}

	@Range(min = 0, max = MAX_DISTANCE)
	@ConfigItem(
		keyName = "sceneryDistance",
		name = SCENERY_NAME,
		description = SCENERY_DESCRIPTION,
		position = 3
	)
	default int sceneryDistance()
	{
		// the GPU plugin's farthest draw distance
		return 184;
	}

	@ConfigItem(
		keyName = "simplify",
		name = "Simplify distant scenery",
		description = "Simplify the shapes of far-away structures and scenery so they render faster. Turn off to see "
			+ "them exactly (slower)",
		position = 4
	)
	default boolean simplify()
	{
		return true;
	}
}
