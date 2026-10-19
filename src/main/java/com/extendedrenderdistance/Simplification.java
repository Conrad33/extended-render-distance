package com.extendedrenderdistance;

/** How far-away scenery is simplified (its corners snapped to a grid that gets coarser with distance). */
public enum Simplification
{
	/** The grid shrinks for big objects (pyramids, castles, towers), so they keep their shape. */
	SIZE_AWARE("Size-aware"),
	/**
	 * Like size-aware, but one grid for each map square (fine enough for its biggest object), so neighbouring pieces
	 * of a structure still meet exactly and leave no gaps.
	 */
	SIZE_AWARE_SEALED("Size-aware, sealed"),
	/** The same grid for everything. */
	UNIFORM("Uniform"),
	/** No snapping: every model exactly as it is. */
	NONE("None");

	private final String label;

	Simplification(String label)
	{
		this.label = label;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
