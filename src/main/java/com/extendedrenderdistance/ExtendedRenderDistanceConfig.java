package com.extendedrenderdistance;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

@ConfigGroup(ExtendedRenderDistanceConfig.GROUP)
public interface ExtendedRenderDistanceConfig extends Config
{
	String GROUP = "extendedrenderdistance";

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
		keyName = "simplification",
		name = "Simplification",
		description = "How far-away scenery is simplified to keep it fast. Size-aware keeps big things (pyramids, "
			+ "castles) smooth; Size-aware, sealed also closes the small gaps between their pieces; Uniform treats "
			+ "everything alike; None doesn't simplify at all (slowest)",
		position = 4
	)
	default Simplification simplification()
	{
		return Simplification.SIZE_AWARE;
	}
}
