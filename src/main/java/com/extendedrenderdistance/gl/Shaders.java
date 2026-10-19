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
	 * 18; for scenery, the tile within its square of the object or floor it belongs to, plus one, in 19 to 31), and
	 * texture coordinates (256ths) and a texture (its id plus one in bits 0 to 7, the light on it in 8 to 14,
	 * repeated tile by tile when bit 15 is set) scrolled as the GPU plugin animates it;
	 * depth pushed back by depthScale so the real scene wins ties, and pulled forward a hair per priority level so a
	 * detail (a window) wins over the face it lies on even where far simplification has flattened the two together;
	 * nothing the game draws itself (scenery whose tile is in a zone the GPU plugin drew; ground well inside them,
	 * a zone's overlap left so their edges meet); nothing past the terrain distance.
	 */
	static final String FAR_VERT = String.join("\n",
		"#version 330",
		"layout(location = 0) in vec3 pos;",
		"layout(location = 1) in int colour;",
		"layout(location = 2) in vec2 uv;",
		"layout(location = 3) in int texInfo;",
		"uniform mat4 proj;",
		"uniform vec3 offset;",
		"uniform float brightness;",
		"uniform float depthScale;",
		"uniform int tick;",
		"uniform vec2 textureAnimations[256];",
		"uniform float textureLightMode;",
		"out vec3 fColour;",
		"out vec3 fPos;",
		"flat out int fAnchored;",
		"flat out vec2 fAnchor;",
		"out vec2 fUv;",
		"out float fTexLight;",
		"out vec3 fTexTint;",
		"noperspective centroid out vec3 fHsl;",
		"flat out int fTexture;",
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
		"  int id = texInfo & 255;",
		"  fTexture = id | ((texInfo & 0x8000) != 0 ? 0x100 : 0);",
		"  fTexLight = float(texInfo >> 8 & 127) / 127.0;",
		"  fTexTint = textureLightMode > 0.5 ? hslToRgb(texInfo >> 8 & 127, brightness) : vec3(0.0);",
		"  fHsl = vec3(colour >> 10 & 63, colour >> 7 & 7, colour & 127);",
		"  fUv = uv / 256.0;",
		"  if (id > 0) fUv += float(tick) * textureAnimations[min(id - 1, 255)] / 128.0;",
		"}");

	static final String FAR_FRAG = String.join("\n",
		"#version 330",
		"in vec3 fColour;",
		"in vec3 fPos;",
		"flat in int fAnchored;",
		"flat in vec2 fAnchor;",
		"in vec2 fUv;",
		"in float fTexLight;",
		"in vec3 fTexTint;",
		"noperspective centroid in vec3 fHsl;",
		"flat in int fTexture;",
		"uniform sampler2D drawnZones;",
		"uniform vec2 zonesOrigin;",
		"uniform float viewEnd;",
		"uniform sampler2DArray textures;",
		"uniform int texturesOn;",
		"uniform float textureEnd;",
		"uniform float brightness;",
		"uniform float textureLightMode;",
		"uniform float smoothBanding;",
		"out vec4 frag;",
		HSL_TO_RGB,
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
		"  float dist = length(fPos.xz);",
		"  if (dist > viewEnd) discard;",
		// as the GPU plugin: the smoothly blended colour, or with smooth banding off the colour blended in steps (hue,
		// saturation and lightness blended apart: a far quad's corners can be different ground, whose packed colours
		// blended together would mix their bits)
		"  vec3 colour = fColour;",
		"  if (smoothBanding > 0.5) {",
		"    ivec3 h = ivec3(fHsl);",
		"    colour = hslToRgb(h.x << 10 | h.y << 7 | h.z, brightness);",
		"  }",
		"  int id = fTexture & 255;",
		"  if (texturesOn == 1 && id > 0 && dist < textureEnd) {",
		// coarse ground repeats its texture every tile: the gradients of the unwrapped coordinates keep the mip level
		"    vec2 at = (fTexture & 0x100) != 0 ? fract(fUv) : fUv;",
		"    vec3 layer = vec3(at, float(id - 1));",
		"    if (textureLod(textures, layer, 0.0).a < 1.0) discard;",
		"    vec3 t = textureGrad(textures, layer, dFdx(fUv), dFdy(fUv)).rgb;",
		// as the GPU plugin: lit by the light, or with bright textures by the light's colour
		"    vec3 lit = pow(t, vec3(brightness)) * mix(vec3(fTexLight), fTexTint, textureLightMode);",
		"    colour = mix(lit, colour, smoothstep(textureEnd * 0.85, textureEnd, dist));",
		"  }",
		"  frag = vec4(colour, 1.0);",
		"}");
}
