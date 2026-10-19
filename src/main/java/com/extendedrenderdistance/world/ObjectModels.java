package com.extendedrenderdistance.world;

import java.util.LinkedHashMap;
import java.util.Map;
import com.extendedrenderdistance.cache.definitions.ModelDefinition;
import com.extendedrenderdistance.cache.definitions.ObjectDefinition;
import com.extendedrenderdistance.cache.models.FaceNormal;
import com.extendedrenderdistance.cache.models.VertexNormal;

/**
 * Objects' models as the client builds them for the scene, from the cache: the definition's model for the
 * placement type, mirrored and turned by the orientation, recoloured, retextured, scaled and offset, then lit the
 * way the client lights scenery (ambient + 64, contrast + 768, light from (-50, -10, -50)). Kept in a bounded cache.
 * Any thread.
 */
final class ObjectModels
{
	/**
	 * A lit model, ready to place: per face its three corners and their colours (packed HSL; the face's draw
	 * priority in bits 16 to 23, its transparency in the top byte), and for textured faces their corners' texture
	 * coordinates and texture ({@link VertexSink#texture}; null when no face is textured).
	 */
	static final class Lit
	{
		final float[] corners;
		final int[] colours;
		final float[] uvs;
		final int[] textures;
		final int faces;
		/** How far the model reaches above its origin (local units, up positive). */
		final int top;
		/** The model's largest size across (local units), on any axis. */
		final float extent;

		Lit(float[] corners, int[] colours, float[] uvs, int[] textures, int faces, int top)
		{
			this.uvs = uvs;
			this.textures = textures;
			float[] lo = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
			float[] hi = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
			for (int i = 0; i < faces * 9; i++)
			{
				lo[i % 3] = Math.min(lo[i % 3], corners[i]);
				hi[i % 3] = Math.max(hi[i % 3], corners[i]);
			}
			this.extent = faces == 0 ? 0 : Math.max(hi[0] - lo[0], Math.max(hi[1] - lo[1], hi[2] - lo[2]));
			this.corners = corners;
			this.colours = colours;
			this.faces = faces;
			this.top = top;
		}
	}

	private static final Lit NONE = new Lit(new float[0], new int[0], null, null, 0, 0);
	private static final int MAX_CACHED = 8000;

	private final WorldCache cache;
	private final int[] textureColours;
	private final Map<Long, Lit> lit = new LinkedHashMap<Long, Lit>(1024, 0.75f, true)
	{
		@Override
		protected boolean removeEldestEntry(Map.Entry<Long, Lit> e)
		{
			return size() > MAX_CACHED;
		}
	};

	ObjectModels(WorldCache cache, int[] textureColours)
	{
		this.cache = cache;
		this.textureColours = textureColours;
	}

	/** Adds the model files {@link #get} would read for this, if it isn't built yet. */
	void needed(ObjectDefinition d, int type, int orientation, java.util.Set<Integer> out)
	{
		synchronized (lit)
		{
			if (lit.containsKey((long) d.getId() << 16 | type << 4 | orientation))
				return;
		}
		int[] ids = d.getObjectModels(), types = d.getObjectTypes();
		if (ids == null)
			return;
		if (types == null)
		{
			if (type == 10)
			{
				for (int id : ids)
					out.add(id);
			}
			return;
		}
		for (int i = 0; i < types.length && i < ids.length; i++)
		{
			if (types[i] == type)
			{
				out.add(ids[i]);
				return;
			}
		}
	}

	/**
	 * The model an object shows for a placement type (the client's model type: 11 shows as 10) and orientation (0
	 * to 7: 4 and up are mirrored), or null when it has none for that type.
	 */
	Lit get(ObjectDefinition d, int type, int orientation)
	{
		long key = (long) d.getId() << 16 | type << 4 | orientation;
		Lit l;
		synchronized (lit)
		{
			l = lit.get(key);
		}
		if (l == null)
		{
			l = build(d, type, orientation);
			if (l == null)
				l = NONE;
			synchronized (lit)
			{
				lit.put(key, l);
			}
		}
		return l == NONE ? null : l;
	}

	private Lit build(ObjectDefinition d, int type, int orientation)
	{
		int[] ids = d.getObjectModels();
		int[] types = d.getObjectTypes();
		if (ids == null || ids.length == 0)
			return null;
		if (types == null)
		{
			if (type != 10)
				return null;
		}
		else
		{
			int found = -1;
			for (int i = 0; i < types.length; i++)
			{
				if (types[i] == type)
				{
					found = i;
					break;
				}
			}
			if (found < 0)
				return null;
			ids = new int[]{ids[found]};
		}
		boolean mirror = d.isRotated() ^ orientation > 3;

		// each model built and lit on its own, then joined
		float[][] parts = new float[ids.length][];
		int[][] partColours = new int[ids.length][];
		float[][] partUvs = new float[ids.length][];
		int[][] partTextures = new int[ids.length][];
		boolean textured = false;
		int faces = 0, top = 0;
		for (int p = 0; p < ids.length; p++)
		{
			ModelDefinition m = cache.model(ids[p]);
			if (m == null || m.faceCount == 0)
				continue;
			transform(m, d, mirror, orientation & 3);
			int[] textures = m.faceTextures == null ? null : new int[m.faceCount * 3];
			if (textures != null)
				m.computeTextureUVCoordinates();
			int[] colours = light(m, 64 + d.getAmbient(), 768 + d.getContrast(), textures);
			float[] corners = new float[m.faceCount * 9];
			float[] uvs = textures == null ? null : new float[m.faceCount * 6];
			int kept = 0;
			for (int f = 0; f < m.faceCount; f++)
			{
				if (colours[f * 3] == Integer.MIN_VALUE)
					continue;
				int[] v = {m.faceIndices1[f], m.faceIndices2[f], m.faceIndices3[f]};
				for (int k = 0; k < 3; k++)
				{
					corners[kept * 9 + k * 3] = m.vertexX[v[k]];
					corners[kept * 9 + k * 3 + 1] = m.vertexY[v[k]];
					corners[kept * 9 + k * 3 + 2] = m.vertexZ[v[k]];
					top = Math.max(top, -m.vertexY[v[k]]);
					colours[kept * 3 + k] = colours[f * 3 + k];
					if (textures != null)
					{
						textures[kept * 3 + k] = textures[f * 3 + k];
						float[] fu = m.faceTextureUCoordinates[f], fv = m.faceTextureVCoordinates[f];
						uvs[kept * 6 + k * 2] = fu == null ? 0 : fu[k];
						uvs[kept * 6 + k * 2 + 1] = fv == null ? 0 : fv[k];
					}
				}
				kept++;
			}
			parts[p] = java.util.Arrays.copyOf(corners, kept * 9);
			partColours[p] = java.util.Arrays.copyOf(colours, kept * 3);
			if (textures != null)
			{
				partUvs[p] = java.util.Arrays.copyOf(uvs, kept * 6);
				partTextures[p] = java.util.Arrays.copyOf(textures, kept * 3);
				textured = true;
			}
			faces += kept;
		}
		if (faces == 0)
			return null;
		float[] corners = new float[faces * 9];
		int[] colours = new int[faces * 3];
		float[] uvs = textured ? new float[faces * 6] : null;
		int[] textures = textured ? new int[faces * 3] : null;
		int at = 0;
		for (int p = 0; p < parts.length; p++)
		{
			if (parts[p] == null)
				continue;
			System.arraycopy(parts[p], 0, corners, at * 9, parts[p].length);
			System.arraycopy(partColours[p], 0, colours, at * 3, partColours[p].length);
			if (partTextures[p] != null)
			{
				System.arraycopy(partUvs[p], 0, uvs, at * 6, partUvs[p].length);
				System.arraycopy(partTextures[p], 0, textures, at * 3, partTextures[p].length);
			}
			at += parts[p].length / 9;
		}
		return new Lit(corners, colours, uvs, textures, faces, top);
	}

	/** Mirror, turn, recolour, retexture, scale and offset, as the client does before lighting. */
	private static void transform(ModelDefinition m, ObjectDefinition d, boolean mirror, int turns)
	{
		if (mirror)
		{
			for (int v = 0; v < m.vertexCount; v++)
				m.vertexZ[v] = -m.vertexZ[v];
			int[] t = m.faceIndices1;
			m.faceIndices1 = m.faceIndices3;
			m.faceIndices3 = t;
		}
		if (turns == 1)
			m.rotate1();
		else if (turns == 2)
			m.rotate2();
		else if (turns == 3)
			m.rotate3();
		short[] find = d.getRecolorToFind(), replace = d.getRecolorToReplace();
		if (find != null && replace != null && m.faceColors != null)
		{
			for (int i = 0; i < Math.min(find.length, replace.length); i++)
				m.recolor(find[i], replace[i]);
		}
		short[] tFind = d.getRetextureToFind(), tReplace = d.getTextureToReplace();
		if (tFind != null && tReplace != null && m.faceTextures != null)
		{
			for (int i = 0; i < Math.min(tFind.length, tReplace.length); i++)
				m.retexture(tFind[i], tReplace[i]);
		}
		int sx = d.getModelSizeX(), sh = d.getModelSizeHeight(), sy = d.getModelSizeY();
		if (sx != 128 || sh != 128 || sy != 128)
			m.resize(sx, sh, sy);
		// the cache reader reads these unsigned; the client reads them signed
		int ox = (short) d.getOffsetX(), oh = (short) d.getOffsetHeight(), oy = (short) d.getOffsetY();
		if (ox != 0 || oh != 0 || oy != 0)
			m.move(ox, oh, oy);
	}

	/**
	 * The client's model lighting: three packed colours per face, the face's draw priority in bits 16 to 23 (details
	 * like windows sit over the faces they're on by priority), transparency in the top byte, MIN_VALUE for a face
	 * that isn't drawn. Textured faces take the texture's colour, darkened by the light, and when {@code textures}
	 * isn't null, their corners' textures go in it ({@link VertexSink#texture}: lit by the light alone, as the client
	 * lights a textured face).
	 */
	private int[] light(ModelDefinition m, int ambient, int contrast, int[] textures)
	{
		m.computeNormals();
		int lx = -50, ly = -10, lz = -50;
		int mag = (int) Math.sqrt(lx * lx + ly * ly + lz * lz);
		int k = mag * contrast >> 8;
		int[] out = new int[m.faceCount * 3];
		for (int f = 0; f < m.faceCount; f++)
		{
			int type = m.faceRenderTypes == null ? 0 : m.faceRenderTypes[f] & 3;
			int tex = m.faceTextures == null ? -1 : m.faceTextures[f];
			int alpha = m.faceTransparencies == null ? 0 : m.faceTransparencies[f] & 0xff;
			int priority = (m.faceRenderPriorities == null ? m.priority : m.faceRenderPriorities[f]) & 0x0f;
			if (type == 2 || alpha >= 254)
			{
				out[f * 3] = Integer.MIN_VALUE;
				continue;
			}
			int base;
			if (tex >= 0)
				base = tex < textureColours.length ? textureColours[tex] : 0x2b4a;
			else
				base = m.faceColors == null ? 0 : m.faceColors[f] & 0xffff;
			if (type == 3)
			{
				out[f * 3] = out[f * 3 + 1] = out[f * 3 + 2] = alpha << 24 | priority << 16 | 128;
				if (tex >= 0 && textures != null)
					textures[f * 3] = textures[f * 3 + 1] = textures[f * 3 + 2] = VertexSink.texture(tex, 126);
				continue;
			}
			int[] v = {m.faceIndices1[f], m.faceIndices2[f], m.faceIndices3[f]};
			if (type == 1)
			{
				FaceNormal n = m.faceNormals == null ? null : m.faceNormals[f];
				int l = n == null ? ambient : (ly * n.y + lz * n.z + lx * n.x) / (k / 2 + k) + ambient;
				int c = alpha << 24 | priority << 16 | shade(base, l);
				out[f * 3] = out[f * 3 + 1] = out[f * 3 + 2] = c;
				if (tex >= 0 && textures != null)
					textures[f * 3] = textures[f * 3 + 1] = textures[f * 3 + 2] = VertexSink.texture(tex, l);
				continue;
			}
			for (int i = 0; i < 3; i++)
			{
				VertexNormal n = m.vertexNormals[v[i]];
				int l = n == null || n.magnitude == 0 ? ambient
					: (ly * n.y + lz * n.z + lx * n.x) / (k * n.magnitude) + ambient;
				out[f * 3 + i] = alpha << 24 | priority << 16 | shade(base, l);
				if (tex >= 0 && textures != null)
					textures[f * 3 + i] = VertexSink.texture(tex, l);
			}
		}
		return out;
	}

	/** The client's colour shading: lightness scaled by the light (128 is unchanged). */
	private static int shade(int hsl, int light)
	{
		int l = light * (hsl & 127) >> 7;
		l = Math.max(2, Math.min(126, l));
		return (hsl & 0xff80) + l;
	}
}
