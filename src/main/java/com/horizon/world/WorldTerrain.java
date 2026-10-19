package com.horizon.world;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The ground of the whole surface world, read from the game cache: per tile its height and colour. Immutable once
 * loaded, so any thread can read it.
 * <p>
 * Heights are the client's (negative is up). Each tile has an underlay colour (blended with its neighbours, as the
 * client does) and maybe an overlay over part or all of it (paths, water, floors) with a shape and rotation.
 * Colours are the client's packed 16-bit HSL, {@link #VOID} for none, {@link #HOLE} for an overlay that hides the
 * ground, or a texture marker ({@link #isTextured}) resolved against the client's texture colours when a mesh is
 * built.
 */
public final class WorldTerrain
{
	public static final int REGION = 64;
	public static final int VOID = -1;
	/** An overlay that is drawn as nothing (the client's magenta overlays). */
	public static final int HOLE = -2;
	private static final char NO_UNDERLAY = 0xffff;
	private static final int TEXTURED = 1 << 30;

	/** One 64 x 64 map square. Arrays are indexed x * 64 + y. */
	public static final class Region
	{
		public final int rx, ry;
		final short[] height;
		/** Blended underlay colour, {@link #NO_UNDERLAY} for none. */
		final char[] underlay;
		/** Overlay id (0 for none) into {@link #overlayColours}. */
		final short[] overlay;
		/** Overlay path << 2 | rotation. */
		final byte[] shape;
		/** Planes 1 to 3: how far each sits above the one below, in eighths of a height unit ([plane - 1][tile]). */
		final byte[][] rise;
		/** Object placements, see {@link #placement}. */
		final long[] locs;

		Region(int rx, int ry, short[] height, char[] underlay, short[] overlay, byte[] shape, byte[][] rise,
			long[] locs)
		{
			this.rx = rx;
			this.ry = ry;
			this.height = height;
			this.underlay = underlay;
			this.overlay = overlay;
			this.shape = shape;
			this.rise = rise;
			this.locs = locs;
		}

		public int locCount()
		{
			return locs.length;
		}

		public int locId(int i)
		{
			return (int) (locs[i] >>> 21);
		}

		public int locType(int i)
		{
			return (int) (locs[i] >> 16) & 31;
		}

		public int locOrientation(int i)
		{
			return (int) (locs[i] >> 14) & 3;
		}

		public int locPlane(int i)
		{
			return (int) (locs[i] >> 12) & 3;
		}

		/** Tile within the square, 0 to 63. */
		public int locX(int i)
		{
			return (int) (locs[i] >> 6) & 63;
		}

		public int locY(int i)
		{
			return (int) locs[i] & 63;
		}

		public int baseX()
		{
			return rx * REGION;
		}

		public int baseY()
		{
			return ry * REGION;
		}
	}

	private final Region[] grid = new Region[256 * 256];
	private final List<Region> regions;
	/** Colour code per overlay id (index 0 unused). */
	private final int[] overlayColours;

	WorldTerrain(List<Region> regions, int[] overlayColours)
	{
		this.overlayColours = overlayColours;
		for (Region r : regions)
			grid[r.rx << 8 | r.ry] = r;
		this.regions = Collections.unmodifiableList(new ArrayList<>(regions));
	}

	public List<Region> regions()
	{
		return regions;
	}

	public Region region(int rx, int ry)
	{
		if (rx < 0 || ry < 0 || rx > 255 || ry > 255)
			return null;
		return grid[rx << 8 | ry];
	}

	public boolean has(int wx, int wy)
	{
		return region(wx >> 6, wy >> 6) != null;
	}

	/** The client height at a tile's south-west corner (negative up); 0 off the map. */
	public int height(int wx, int wy)
	{
		Region r = region(wx >> 6, wy >> 6);
		return r == null ? 0 : r.height[(wx & 63) << 6 | (wy & 63)];
	}

	/** Like {@link #height} but off the map takes the nearest tile of {@code fallback}'s region. */
	public int height(int wx, int wy, Region fallback)
	{
		Region r = region(wx >> 6, wy >> 6);
		if (r != null)
			return r.height[(wx & 63) << 6 | (wy & 63)];
		int x = Math.max(0, Math.min(63, wx - fallback.baseX()));
		int y = Math.max(0, Math.min(63, wy - fallback.baseY()));
		return fallback.height[x << 6 | y];
	}

	/** The tile's blended underlay colour, or VOID. */
	public int underlay(int wx, int wy)
	{
		Region r = region(wx >> 6, wy >> 6);
		if (r == null)
			return VOID;
		char c = r.underlay[(wx & 63) << 6 | (wy & 63)];
		return c == NO_UNDERLAY ? VOID : c;
	}

	/** The tile's overlay colour code: VOID for no overlay, HOLE, a colour or a texture marker. */
	public int overlay(int wx, int wy)
	{
		Region r = region(wx >> 6, wy >> 6);
		if (r == null)
			return VOID;
		int id = r.overlay[(wx & 63) << 6 | (wy & 63)] & 0xffff;
		return id == 0 || id >= overlayColours.length ? VOID : overlayColours[id];
	}

	/** The overlay's path (0 a full tile, 1 to 12 part of one). */
	public int overlayPath(int wx, int wy)
	{
		Region r = region(wx >> 6, wy >> 6);
		return r == null ? 0 : (r.shape[(wx & 63) << 6 | (wy & 63)] & 0xff) >> 2;
	}

	public int overlayRotation(int wx, int wy)
	{
		Region r = region(wx >> 6, wy >> 6);
		return r == null ? 0 : r.shape[(wx & 63) << 6 | (wy & 63)] & 3;
	}

	/**
	 * The one colour that best stands for a tile from afar: a full overlay, else the underlay, else a part
	 * overlay; VOID where nothing is drawn.
	 */
	public int colour(int wx, int wy)
	{
		int over = overlay(wx, wy), under = underlay(wx, wy);
		if (over != VOID && (overlayPath(wx, wy) == 0 || under == VOID))
			return over == HOLE ? VOID : over;
		return under;
	}

	/** The client height of a tile corner on a plane (0 to 3); negative is up. */
	public int height(int wx, int wy, int plane, Region fallback)
	{
		int h = height(wx, wy, fallback);
		if (plane <= 0)
			return h;
		Region r = region(wx >> 6, wy >> 6);
		if (r == null)
			r = fallback;
		int x = Math.max(0, Math.min(63, wx - r.baseX())), y = Math.max(0, Math.min(63, wy - r.baseY()));
		for (int p = 1; p <= plane; p++)
			h -= (r.rise[p - 1][x << 6 | y] & 0xff) * 8;
		return h;
	}

	/** Packs a placement: object id, type (0 to 22), orientation, plane and tile within the square. */
	static long placement(int id, int type, int orientation, int plane, int x, int y)
	{
		return (long) id << 21 | (long) (type & 31) << 16 | (orientation & 3) << 14 | (plane & 3) << 12
			| (x & 63) << 6 | (y & 63);
	}

	static char packUnderlay(int hsl)
	{
		return hsl == VOID ? NO_UNDERLAY : (char) hsl;
	}

	/**
	 * Height in world units (up positive) anywhere, bilinear between the four corners as the client does; 0 off
	 * the map. {@code x} and {@code y} are world tiles times 128.
	 */
	public double heightAt(double x, double y)
	{
		double tx = x / 128, ty = y / 128;
		int ix = (int) Math.floor(tx), iy = (int) Math.floor(ty);
		double fx = tx - ix, fy = ty - iy;
		double south = height(ix, iy) * (1 - fx) + height(ix + 1, iy) * fx;
		double north = height(ix, iy + 1) * (1 - fx) + height(ix + 1, iy + 1) * fx;
		return -(south * (1 - fy) + north * fy);
	}

	static int textured(int texture)
	{
		return TEXTURED | texture;
	}

	public static boolean isTextured(int colour)
	{
		return colour >= 0 && (colour & TEXTURED) != 0;
	}

	public static int texture(int colour)
	{
		return colour & 0xffff;
	}
}
