package com.extendedrenderdistance.gl;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Which of the loaded scene's 8 x 8 tile zones the game's renderer draws itself, as told by its
 * {@code drawZoneOpaque} calls: a zone counts as drawn if it was drawn in any of the last {@link #KEEP} frames (so
 * one turned briefly out of view doesn't flicker over to the far world). Forgotten when the scene moves. Client
 * thread.
 */
final class DrawnZones
{
	/** Zones across the extended scene, and how many of them lie before the normal scene's south-west corner. */
	static final int ZONES = 23, OFFSET = 5;
	/** The mask's side, in zones (a power of two at least {@link #ZONES}). */
	static final int MASK = 32;
	private static final int KEEP = 30;

	private final long[] lastDrawn = new long[ZONES * ZONES];
	private long frame = 1;
	private int baseX = Integer.MIN_VALUE, baseY;
	private final ByteBuffer mask = ByteBuffer.allocateDirect(MASK * MASK).order(ByteOrder.nativeOrder());

	/** A zone drawn this frame (scene zone coordinates, as the renderer is called with). */
	void drawn(int zx, int zz)
	{
		if (zx >= 0 && zz >= 0 && zx < ZONES && zz < ZONES)
			lastDrawn[zx * ZONES + zz] = frame;
	}

	/** The end of a frame in a scene whose south-west tile is world tile (baseX, baseY). */
	void endFrame(int baseX, int baseY)
	{
		if (baseX != this.baseX || baseY != this.baseY)
		{
			java.util.Arrays.fill(lastDrawn, 0);
			this.baseX = baseX;
			this.baseY = baseY;
		}
		frame++;
	}

	boolean isDrawn(int zx, int zz)
	{
		if (zx < 0 || zz < 0 || zx >= ZONES || zz >= ZONES)
			return false;
		long t = lastDrawn[zx * ZONES + zz];
		return t != 0 && frame - t <= KEEP;
	}

	/** Whether the zone holding world tile (wx, wy) is drawn, for a scene with south-west tile (baseX, baseY). */
	boolean isDrawnAt(int wx, int wy, int baseX, int baseY)
	{
		return isDrawn((wx >> 3) - (baseX >> 3) + OFFSET, (wy >> 3) - (baseY >> 3) + OFFSET);
	}

	/** The mask as bytes (255 drawn, 0 not), x across, z down the rows: zone (zx, zz) at [zz * MASK + zx]. */
	ByteBuffer mask()
	{
		mask.clear();
		for (int zz = 0; zz < MASK; zz++)
		{
			for (int zx = 0; zx < MASK; zx++)
				mask.put(isDrawn(zx, zz) ? (byte) 255 : 0);
		}
		mask.flip();
		return mask;
	}
}
