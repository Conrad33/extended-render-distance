package com.extendedrenderdistance.world;

import com.extendedrenderdistance.cache.definitions.ModelDefinition;
import com.extendedrenderdistance.cache.io.InputStream;

/**
 * An object's animation, as the client plays it on an animated object: frames shown for so many client cycles
 * (20 ms) each, looping, each frame a list of transforms (origin, move, turn, scale, transparency) on groups of the
 * model's vertices or faces. The formats are the client's (as the RuneLite cache library reads them, BSD-2). Any
 * thread once read.
 */
public final class ObjectAnimation
{
	/** The configs group of the animations, and the indexes of their frames and of the frames' skeletons. */
	static final int SEQUENCES = 12, FRAMES = 0, SKELETONS = 1;

	private static final int ORIGIN = 0, ALPHA = 5;

	/** One frame: per transform, its type, the groups it acts on, and its amounts. */
	static final class Frame
	{
		private final int[] types;
		private final int[][] groups;
		private final int[] dx, dy, dz;

		private Frame(int[] types, int[][] groups, int[] dx, int[] dy, int[] dz)
		{
			this.types = types;
			this.groups = groups;
			this.dx = dx;
			this.dy = dy;
			this.dz = dz;
		}

		/** Sets the faces' transparency as the frame does (before lighting, which leaves out the hidden faces). */
		void transparency(ModelDefinition m)
		{
			if (m.faceTransparencies == null || m.packedTransparencyVertexGroups == null)
				return;
			int[] faceGroup = m.packedTransparencyVertexGroups;
			for (int t = 0; t < types.length; t++)
			{
				if (types[t] != ALPHA)
					continue;
				for (int f = 0; f < m.faceCount && f < faceGroup.length; f++)
				{
					if (!in(groups[t], faceGroup[f]))
						continue;
					int a = (m.faceTransparencies[f] & 0xff) + dx[t] * 8;
					m.faceTransparencies[f] = (byte) Math.max(0, Math.min(255, a));
				}
			}
		}

		/** Moves the vertices as the frame does (after lighting: the client lights the model unposed). */
		void pose(ModelDefinition m)
		{
			for (int t = 0; t < types.length; t++)
			{
				if (types[t] != ALPHA)
					m.animate(types[t], groups[t], dx[t], dy[t], dz[t]);
			}
		}
	}

	private final Frame[] frames;
	/** Each frame's length, in client cycles. */
	private final int[] lengths;
	/** The frame the animation loops back to, and the cycles of one loop from there. */
	private final int loopFrom, loopLength;

	private ObjectAnimation(Frame[] frames, int[] lengths, int frameStep)
	{
		this.frames = frames;
		this.lengths = lengths;
		// as the client: past the last frame it steps back frameStep frames (when it can't, it's played from the start)
		this.loopFrom = frameStep > 0 && frameStep <= frames.length ? frames.length - frameStep : 0;
		int loop = 0;
		for (int i = loopFrom; i < frames.length; i++)
			loop += lengths[i];
		this.loopLength = Math.max(1, loop);
	}

	public int frameCount()
	{
		return frames.length;
	}

	Frame frame(int k)
	{
		return frames[Math.max(0, Math.min(frames.length - 1, k))];
	}

	/** The frame shown {@code cycle} client cycles into the animation (looping). */
	public int frameAt(int cycle)
	{
		int t = Math.floorMod(cycle, loopLength);
		for (int i = loopFrom; i < frames.length; i++)
		{
			t -= lengths[i];
			if (t < 0)
				return i;
		}
		return frames.length - 1;
	}

	/** The cycles of one loop. */
	public int loopLength()
	{
		return loopLength;
	}

	/** An animation, or null when it has no frames this can play (skeletal ones), or doesn't read. */
	static ObjectAnimation read(CacheSource.Files files, int sequence)
	{
		byte[] seq = files.file(CacheSource.CONFIGS, SEQUENCES, sequence);
		if (seq == null)
			return null;
		InputStream in = new InputStream(seq);
		// the settings before the frames (only the loop matters here)
		int frameStep = -1;
		int op;
		while ((op = in.readUnsignedByte()) != 1)
		{
			if (op == 0 || op > 12)
				return null;
			else if (op == 2)
				frameStep = in.readUnsignedShort();
			else if (op == 6 || op == 7)
				in.skip(2);
			else if (op == 3)
				in.skip(in.readUnsignedByte());
			else if (op == 5 || op == 8 || op == 9 || op == 10 || op == 11)
				in.skip(1);
			else if (op == 12)
				in.skip(in.readUnsignedByte() * 4);
		}
		int count = in.readUnsignedShort();
		if (count == 0)
			return null;
		int[] lengths = new int[count], ids = new int[count];
		for (int i = 0; i < count; i++)
			lengths[i] = Math.max(1, in.readUnsignedShort());
		for (int i = 0; i < count; i++)
			ids[i] = in.readUnsignedShort();
		for (int i = 0; i < count; i++)
			ids[i] |= in.readUnsignedShort() << 16;
		// the settings after the frames: only the loop matters here; past what this reads, it isn't known
		while (in.remaining() > 0 && (op = in.readUnsignedByte()) != 0)
		{
			if (op == 2)
				frameStep = in.readUnsignedShort();
			else if (op == 3)
				in.skip(in.readUnsignedByte());
			else if (op == 4)
				continue;
			else if (op == 5 || op == 8 || op == 9 || op == 10 || op == 11)
				in.skip(1);
			else if (op == 6 || op == 7)
				in.skip(2);
			else
				break;
		}

		Frame[] frames = new Frame[count];
		java.util.Map<Integer, int[][]> skeletons = new java.util.HashMap<>();
		for (int i = 0; i < count; i++)
		{
			frames[i] = frame(files, ids[i], skeletons);
			if (frames[i] == null)
				return null;
		}
		return new ObjectAnimation(frames, lengths, frameStep);
	}

	/** A frame, or null when it doesn't read. {@code skeletons} keeps those read: types, then each one's groups. */
	private static Frame frame(CacheSource.Files files, int id, java.util.Map<Integer, int[][]> skeletons)
	{
		byte[] f = files.file(FRAMES, id >>> 16, id & 0xffff);
		if (f == null || f.length < 3)
			return null;
		int skeleton = (f[0] & 0xff) << 8 | f[1] & 0xff;
		int[][] sk = skeletons.get(skeleton);
		if (sk == null)
		{
			sk = skeleton(files.file(SKELETONS, skeleton, 0));
			if (sk == null)
				return null;
			skeletons.put(skeleton, sk);
		}
		int[] kind = sk[0];
		int n = kind.length;

		// a flags byte per skeleton transform, then the amounts of those it uses
		InputStream flags = new InputStream(f), data = new InputStream(f);
		flags.skip(2);
		int length = flags.readUnsignedByte();
		if (length > n)
			return null;
		data.skip(3 + length);
		int[] types = new int[length * 2], dx = new int[length * 2], dy = new int[length * 2], dz = new int[length * 2];
		int[][] groups = new int[length * 2][];
		int at = 0, last = -1;
		for (int i = 0; i < length; i++)
		{
			int used = flags.readUnsignedByte();
			if (used <= 0)
				continue;
			// a transform about an origin starts from the origin before it, unmoved
			if (kind[i] != ORIGIN)
			{
				for (int o = i - 1; o > last; o--)
				{
					if (kind[o] == ORIGIN)
					{
						types[at] = ORIGIN;
						groups[at] = sk[1 + o];
						at++;
						break;
					}
				}
			}
			int unset = kind[i] == 3 ? 128 : 0;
			types[at] = kind[i];
			groups[at] = sk[1 + i];
			dx[at] = (used & 1) != 0 ? data.readShortSmart() : unset;
			dy[at] = (used & 2) != 0 ? data.readShortSmart() : unset;
			dz[at] = (used & 4) != 0 ? data.readShortSmart() : unset;
			last = i;
			at++;
		}
		// a frame that doesn't read to its end wasn't read right
		if (data.getOffset() != f.length)
			return null;
		return new Frame(java.util.Arrays.copyOf(types, at), java.util.Arrays.copyOf(groups, at),
			java.util.Arrays.copyOf(dx, at), java.util.Arrays.copyOf(dy, at), java.util.Arrays.copyOf(dz, at));
	}

	/** A skeleton: its transforms' types, then the groups each acts on; null when there's none. */
	private static int[][] skeleton(byte[] s)
	{
		if (s == null)
			return null;
		InputStream in = new InputStream(s);
		int n = in.readUnsignedByte();
		int[][] out = new int[n + 1][];
		out[0] = new int[n];
		for (int i = 0; i < n; i++)
			out[0][i] = in.readUnsignedByte();
		for (int i = 0; i < n; i++)
			out[1 + i] = new int[in.readUnsignedByte()];
		for (int i = 0; i < n; i++)
		{
			for (int j = 0; j < out[1 + i].length; j++)
				out[1 + i][j] = in.readUnsignedByte();
		}
		return out;
	}

	private static boolean in(int[] groups, int group)
	{
		for (int g : groups)
		{
			if (g == group)
				return true;
		}
		return false;
	}
}
