package com.extendedrenderdistance.gl;

import static org.lwjgl.opengl.GL33C.*;

/** The far world's GLSL program. GL thread. */
final class Shaders
{
	private Shaders()
	{
	}

	static int program(String vertex, String fragment)
	{
		int vs = shader(GL_VERTEX_SHADER, vertex);
		int fs = shader(GL_FRAGMENT_SHADER, fragment);
		int p = glCreateProgram();
		glAttachShader(p, vs);
		glAttachShader(p, fs);
		glLinkProgram(p);
		glDeleteShader(vs);
		glDeleteShader(fs);
		if (glGetProgrami(p, GL_LINK_STATUS) == GL_FALSE)
		{
			String err = glGetProgramInfoLog(p);
			glDeleteProgram(p);
			throw new IllegalStateException("shader link failed: " + err);
		}
		return p;
	}

	private static int shader(int type, String src)
	{
		int s = glCreateShader(type);
		glShaderSource(s, src);
		glCompileShader(s);
		if (glGetShaderi(s, GL_COMPILE_STATUS) == GL_FALSE)
		{
			String err = glGetShaderInfoLog(s);
			glDeleteShader(s);
			throw new IllegalStateException("shader compile failed: " + err);
		}
		return s;
	}

	/** The client's packed HSL to RGB, with its brightness curve (after RuneLite's GPU plugin, BSD-2). */
	private static final String HSL_TO_RGB = String.join("\n",
		"vec3 hslToRgb(int hsl, float brightness) {",
		"  float hue = float(hsl >> 10 & 63) / 64.0 + 0.0078125;",
		"  float sat = float(hsl >> 7 & 7) / 8.0 + 0.0625;",
		"  float lum = float(hsl & 127) / 128.0;",
		"  float q = lum < 0.5 ? lum * (1.0 + sat) : lum + sat - lum * sat;",
		"  float p = 2.0 * lum - q;",
		"  vec3 t = fract(vec3(hue + 1.0 / 3.0, hue, hue - 1.0 / 3.0) + 1.0);",
		"  vec3 c;",
		"  for (int i = 0; i < 3; i++) {",
		"    float x = t[i];",
		"    if (6.0 * x < 1.0) c[i] = p + (q - p) * 6.0 * x;",
		"    else if (2.0 * x < 1.0) c[i] = q;",
		"    else if (3.0 * x < 2.0) c[i] = p + (q - p) * (2.0 / 3.0 - x) * 6.0;",
		"    else c[i] = p;",
		"  }",
		"  return pow(c, vec3(brightness));",
		"}");

	/**
	 * Camera-relative positions (the client's axes, y down) with a packed colour (bits 0 to 15; draw priority in 16 to
	 * 18; for scenery, the tile within its square of the object or floor it belongs to, plus one, in 19 to 31);
	 * depth pushed back by depthScale so the real scene wins ties, and pulled forward a hair per priority level so a
	 * detail (a window) wins over the face it lies on even where far simplification has flattened the two together;
	 * nothing the game draws itself (scenery whose tile is in a zone the GPU plugin drew; ground well inside them,
	 * a zone's overlap left so their edges meet); hazed into the sky.
	 */
	static final String FAR_VERT = String.join("\n",
		"#version 330",
		"layout(location = 0) in vec3 pos;",
		"layout(location = 1) in int colour;",
		"uniform mat4 proj;",
		"uniform vec3 offset;",
		"uniform float brightness;",
		"uniform float depthScale;",
		"out vec3 fColour;",
		"out vec3 fPos;",
		"flat out int fAnchored;",
		"flat out vec2 fAnchor;",
		HSL_TO_RGB,
		"void main() {",
		"  vec3 p = pos + offset;",
		"  vec4 c = proj * vec4(p, 1.0);",
		"  c.z *= depthScale * (1.0 + float(colour >> 16 & 7) * 0.0002);",
		"  int a = colour >> 19 & 8191;",
		"  fAnchored = a > 0 ? 1 : 0;",
		"  a = max(a - 1, 0);",
		"  fAnchor = offset.xz + vec2(float(a >> 6) * 128.0 + 64.0, float(a & 63) * 128.0 + 64.0);",
		"  gl_Position = c;",
		"  fColour = hslToRgb(colour & 65535, brightness);",
		"  fPos = p;",
		"}");

	static final String FAR_FRAG = String.join("\n",
		"#version 330",
		"in vec3 fColour;",
		"in vec3 fPos;",
		"flat in int fAnchored;",
		"flat in vec2 fAnchor;",
		"uniform sampler2D drawnZones;",
		"uniform vec2 zonesOrigin;",
		"uniform vec3 fogColour;",
		"uniform float fogEnd;",
		"uniform float fogDepth;",
		"out vec4 frag;",
		"bool drawn(vec2 p) {",
		"  ivec2 c = ivec2(floor((p - zonesOrigin) / 1024.0));",
		"  if (c.x < 0 || c.y < 0 || c.x >= 32 || c.y >= 32) return false;",
		"  return texelFetch(drawnZones, c, 0).r > 0.5;",
		"}",
		"void main() {",
		"  if (fAnchored == 1) {",
		"    if (drawn(fAnchor)) discard;",
		"  } else if (drawn(fPos.xz) && drawn(fPos.xz + vec2(1024.0, 0.0)) && drawn(fPos.xz - vec2(1024.0, 0.0))",
		"      && drawn(fPos.xz + vec2(0.0, 1024.0)) && drawn(fPos.xz - vec2(0.0, 1024.0))) discard;",
		"  float d = length(fPos.xz);",
		"  if (d > fogEnd) discard;",
		"  float fog = fogDepth > 0.0 ? clamp((d - (fogEnd - fogDepth)) / fogDepth, 0.0, 1.0) : 0.0;",
		"  frag = vec4(mix(fColour, fogColour, fog), 1.0);",
		"}");
}
