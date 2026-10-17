import de.oai.smoothblocks.SmoothBlocksShaderPatch;

/** Synthetic Sodium and post-transform Iris fixtures; checks rewrite coverage while preserving helper probes. */
public class PatchHarness {
  public static void main(String[] args) {
    String src = """
#version 460 core
uniform sampler2D u_BlockTex;
uniform bool u_UseRGSS;
vec4 sampleNearest(sampler2D source, vec2 uv, vec2 pixelSize, vec2 du, vec2 dv, vec2 texelScreenSize) {
  return textureGrad(source, uv, du, dv);
}
vec4 sampleNearest(sampler2D source, vec2 uv, vec2 pixelSize) {
  vec2 du = dFdx(uv); vec2 dv = dFdy(uv);
  vec2 texelScreenSize = sqrt(du * du + dv * dv);
  return sampleNearest(source, uv, pixelSize, du, dv, texelScreenSize);
}
vec4 sampleRGSS(sampler2D source, vec2 uv, vec2 pixelSize) {
  vec4 rgssColor=textureLod(source, uv, 0.0);
  vec4 nearestColor=sampleNearest(source,uv,pixelSize);
  return mix(nearestColor,rgssColor,0.5);
}
void main(){ vec4 c=u_UseRGSS?sampleRGSS(u_BlockTex,vec2(.5),vec2(1.0/1024.0)):sampleNearest(u_BlockTex,vec2(.5),vec2(1.0/1024.0)); }
""";
    String p=SmoothBlocksShaderPatch.patchSodiumFragment(src);
    require(p.contains("SMOOTHBLOCKS_XBRZ_DIRECT_V13"), "v13 marker");
    require(p.contains("smoothblocks_OriginalNearest"), "nearest rename");
    require(p.contains("smoothblocks_OriginalRGSS"), "rgss rename");
    require(p.contains("uniform int smoothblocks_Debug"), "compact visual debug uniform");

    // Different UV branches in main must be patched while the POM helper probe stays untouched.
    String iris = """
#version 330 core
uniform sampler2D gtexture;
vec4 pomProbe(vec2 uv) { return textureLod(gtexture, uv + vec2(0.001), 0.0); }
void main(){
 vec2 uv=vec2(.5), dx=vec2(.01,0), dy=vec2(0,.01);
 vec4 h=pomProbe(uv);
 vec4 a=texture(gtexture,uv);
 vec4 b=textureGrad(gtexture,uv,dx,dy);
 vec4 c=textureLod(gtexture,uv,0.0);
 vec4 aux=texture(gtexture,uv + vec2(0.002));
 gl_FragColor=a+b+c+h+aux*0.0;
}
""";
    String finished=SmoothBlocksShaderPatch.patchIrisTerrainFragmentPostTransform(iris);
    require(finished.contains("SMOOTHBLOCKS_XBRZ_DIRECT_V13"), "Iris post-transform marker");
    require(finished.contains("smoothblocks_TerrainSample(gtexture"), "Iris main texture patched");
    require(finished.contains("smoothblocks_TerrainSampleGrad(gtexture"), "Iris main grad patched");
    require(finished.contains("smoothblocks_TerrainSampleLod(gtexture"), "Iris main lod patched");
    require(finished.contains("textureLod(gtexture, uv + vec2(0.001), 0.0)"), "auxiliary helper read untouched");
    require(finished.contains("smoothblocks_TerrainSample(gtexture,uv + vec2(0.002))"), "different-UV main albedo branch patched");
    require(SmoothBlocksShaderPatch.terrainStatsString().contains("aux=1/0"), "helper-only auxiliary read remains");
    System.out.println("PASS shader-patch v1.6.3 chars="+p.length()+" stats="+SmoothBlocksShaderPatch.terrainStatsString());
  }
  static void require(boolean b,String s){if(!b)throw new AssertionError(s);}
}
