import com.dd.plist.*;
import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.*;
import java.text.DecimalFormat;
import java.util.*;

/*
 *  Experimental code to read Cheetah 3D's .jas files which are written in the Apple Binary Plist format and
 *  then dump them out as an indented text file designed to show the hierarchy of the data.
 *
 *  Author: Wayne Holder, 2020
 *  License: MIT (https://opensource.org/licenses/MIT)
 *
 * See README.md file for more info
 *
 * Notes 1: Vertices are defined as 4 float values, where the last float is always 0
 *
 *  Objects[1].vertex: Data (128 bytes)
 *    BF 00 00 00 = -0.500000  <- index 0 X
 *    BF 00 00 00 = -0.500000  <- index 0 Y
 *    3F 00 00 00 =  0.500000  <- index 0 Z
 *    00 00 00 00 =  0.000000  <- always zero
 *
 *    BF 00 00 00 = -0.500000  <- index 1 X
 *    3F 00 00 00 =  0.500000  <- index 1 Y
 *    3F 00 00 00 =  0.500000  <- index 1 Z
 *    00 00 00 00 =  0.000000  <- always zero
 *
 *    Notice the bytes for each float are in big endian format!
 *
 * Note 2: Vertices are also stored as 3 float values prefixed by a 16 byte header in a section with a
 *  "parameter" value of "pointArray" and the byte data array with a key value of "value", like this:
 *
 *  Objects[1].tracks2[3].value: Data (112 bytes)
 *    // pointArray
 *    08 00 00 00 =  8      <- number of points
 *    03 00 00 00 =  3      <- number of floats/point
 *    01 00 00 00 =  1
 *    00 00 00 00 =  0
 *
 *    00 00 00 BF = -0.500000  <- index 0 X
 *    00 00 00 BF = -0.500000  <- index 0 Y
 *    00 00 00 3F =  0.500000  <- index 0 Z
 *
 *    00 00 00 BF = -0.500000  <- index 1 X
 *    00 00 00 3F =  0.500000  <- index 1 Y
 *    00 00 00 3F =  0.500000  <- index 1 Z
 *    ... repeats, as needed
 *
 *  Notice the bytes for each int and float are in little endian format!
 *
 * Note 3: The first value in the polygons (face) data is negative and indicates the number of vertices, such as:
 *
 *    vertexcount: Integer: 7397 (0x1CE5)
 *    polygons: NSData (146088 bytes)
 *      FF FF FF FC = -4
 *      00 00 00 01 =  1
 *      00 00 00 02 =  2
 *      00 00 00 03 =  3
 *      00 00 00 00 =  0
 *
 *      FF FF FF FC = -4
 *      00 00 00 04 =  4
 *      00 00 00 05 =  5
 *      00 00 00 02 =  2
 *      00 00 00 01 =  1
 *      ... repeats, as needed
 *
 *  Notice the bytes for each int are in big endian format!
 *
 * Note 4: shaderTagMaterial contains integer Id that links to Materials3 "ID" integer value
 *
 * Note 5: UV Coords seem to follow the order polygon faces are eunmerated in "polygons" NSData item, such as:
 *
 *  Objects[1].uvcoords: Data (465504 bytes) - name: Dreyar
 *    // first pair of float values in each set is UV Coord set 0, 2nd is set 1
 *    3E 92 56 DE =  0.285819  <- coord set 0, index 0
 *    3E BA 5B B8 =  0.363981
 *    3E 92 56 DE =  0.285819  <- coord set 1, index 0
 *    3E BA 5B B8 =  0.363981
 *
 *    3E 9A E4 2A =  0.302522  <- coord set 0, index 1
 *    3E BA 5D 28 =  0.363992
 *    3E 9A E4 2A =  0.302522  <- coord set 1, index 1
 *    3E BA 5D 28 =  0.363992
 *      ... repeats, as needed
 *
 *  Notice the bytes for each float are in big endian format!  Also, the 2nd value, V, is reversed from how it's
 *  used in OBJ files, so you must subtract this value from 1.0 to get the unreversed value.
 *
 * Note 6: Joint info is contained in the Array, which is linked to the "baseData" key in a Dictionary
 * object, like this:
 *    baseData: Dictionary (1 items)
 *    linkData: Array (52 items)
 *      linkBound                           typically false
 *      associateID                         typically 0
 *      linkID                              id matching to joint tree "ID" value
 *      bindPoseS                           typically all values 1 (scaling)
 *      linkMode                            typically all values 0
 *      transformMatrix                     typically 4x4 identity matrix
 *      transformAssociateModelMatrix       typically 4x4 identity matrix
 *      cdata                               <vertex index>/<weight> values (See note 7)
 *      transformLinkMatrix                 typically contains joint translation matrix
 *      bindPoseT                           typically all values 0 (translation)
 *      bindPoseR                           typically all values 0 (rotation)
 *
 * Note 7: "cdata" Data section for each joint ("linkData" item) contains <vertex index>/<weight> values, such as:
 *
 *    cdata: Data (3096 bytes)
 *      // even/odd values contain <vertex index>/<joint weight> values
 *      00 00 00 00 =  0          <- index
 *      3C D9 5A 0D =  0.0265322  <- weight
 *
 *      00 00 00 01 =  1          <- index
 *      3C D8 83 9D =  0.0264299  <- weight
 *      ... repeats, as needed
 *
 *  Notice the bytes for each int and float are in big endian format!
 *
 * Note 8: keyframe data is stored as Data in a Dictionary item with the key "keys".  The first 4 bytes form an
 *  int value that inducates the number of keyframes in the block.  The next 4 bytes are an int that always seems to
 *  be set to a value of 27.  After this, each keyframe is encoded as a block of 27 bytes.  The first 24 bytes form
 *  6, 32 bit float values with the 6th (last) value being the keyframe parameter.  The purpose of the other values
 *  in this block is currently unknown.  For example:
 *
 *    index[0]: Dictionary (3 items)
 *      keys: Data (872 bytes)
 *      // mixamorig:Hips - position X
 *      00 00 00 20 = 32          <- number of frames
 *      00 00 00 1B = 27          <- always 27
 *
 *      00 00 00 00 =  0
 *      BB B6 0B 61 = -0.0055556
 *      3B B6 0B 61 =  0.0055556
 *      BB 1F 85 C5 = -0.0024341
 *      3B 1F 85 C5 =  0.0024341
 *      3C 2D CE 54 =  0.0106083  <- keyframe value
 *      00 00 00
 *      ... repeats, as needed
 *
 *  Notice the bytes for each int and float are in big endian format!
 *
 * Note 9: each Keyframe value is labelled by "name" and "parameter" values in a preceeding Dictionary that indicates
 *  the Joint name such as "mixamorig:Hips".  The "parameter" value can be "position", "rotation", or "scale".
 *
 * Note 10: Vertex normals do not appear to be saved in the .jas files I used as test examples.  However, it's possible
 *  there may be circumstances where vertex normals are stored.
 */

public class Cheetah3DParser {
  private static DecimalFormat    df = new DecimalFormat("0.000000");
  private boolean                 consoleOut;
  private boolean                 suppressId;
  private boolean                 showHexData;
  private boolean                 showRaw;
  private boolean                 showMaterials = false;
  private boolean                 showPolys     = false;
  private boolean                 showVertices  = false;
  private boolean                 showUVs       = false;
  private boolean                 showWeights   = false;
  private boolean showJoints         = false;
  private boolean showJointHierarchy = false;
  private boolean showKeyframes      = false;
  private PrintStream             out = System.out;
  private Map<String, Integer>    parmOrder = new HashMap<>();
  private Map<Integer, Material>  idToMaterial = new LinkedHashMap<>();
  private List<Polygon>           polygons = new ArrayList<>();
  private List<Material>          materials = new ArrayList<>();

  {
    parmOrder.put("position", 0);
    parmOrder.put("rotation", 1);
    parmOrder.put("scale", 2);
  }

  private class Material {
    private String        materialName;
    private float[]       diffColor, specColor, reflColor, transColor, emisColor;
    private float         specSize, reflBlur, transBlur;
    private int           index, reflSamples, transSamples, bumpType;
    private boolean       reflFresnel, transUseAlpha;
    private String[]      bumpTypes = new String[] {"bumpHeight", "bumpNormalYPlus", "bumpNormalYMinus"};
    private String[]      filterTypes = new String[] {"Off", "Bilinear", "Trilinear", "Anisotropic"};
    private  String[]      sampleTypes = new String[] {"UV1", "UV2"};
    private List<Texture>       textures = new ArrayList<>();
   private Map<String,Texture>  idToTexture = new HashMap<>();

    Material (int index, String name, int id) {
      this.index = index;
      this.materialName = name;
      idToMaterial.put(id, this);
    }

    class Texture {
      private String    type, file;
      private float[]   background, mixcolor;
      private float[]   position, scale;
      private float     intensity, mix;
      private int       filtertype, sample;
      private boolean   tileU, tileV;

      public Texture (String type, String conId) {
        this.type = type;
        idToTexture.put(conId, this);
      }

      private String getType () {
        if ("bump".equals(type)) {
          return bumpTypes[bumpType];
        }
        return type;
      }
    }

    private void addTexture (String name, String conId) {
      textures.add(new Texture(name, conId));
    }

    private Texture getTexture (String id) {
      return idToTexture.get(id);
    }

    private String getName () {
      return materialName;
    }

    public void print (PrintStream out, String indent) {
      out.println(indent + pad("Diffuse:", 16) + fmtARGB(diffColor));
      out.println(indent + pad("Specular:", 16) + fmtARGB(specColor));
      out.println(indent + pad("Specular Size:", 16) + specSize);
      out.println(indent + pad("Reflection:", 16) + fmtARGB(reflColor));
      out.println(indent + pad("Ref. Blur:", 16) + fmtFloat(reflBlur));
      out.println(indent + pad("Ref. Samples:", 16) + reflSamples);
      out.println(indent + pad("Fresnel:", 16) + reflFresnel);
      out.println(indent + pad("Transparency:", 16) + fmtARGB(transColor));
      out.println(indent + pad("Trans. Blur:", 16) + fmtFloat(transBlur));
      out.println(indent + pad("Trans. Samples:", 16) + transSamples);
      out.println(indent + pad("Use Alpha:", 16) + transUseAlpha);
      out.println(indent + pad("Emissive:", 16) + fmtARGB(emisColor));
      out.println(indent + pad("Bump Type:", 16) + bumpTypes[bumpType]);
      for (Texture texture : textures) {
        out.println(indent + pad("Texture Type:", 16) + texture.getType());
        out.println(indent + pad("Texture File:", 16) + "'" + texture.file + "'");
        out.println(indent + "  " + pad("mixcolor:", 14) + fmtARGB(texture.mixcolor));
        out.println(indent + "  " + pad("mix:", 14) + fmtFloat(texture.mix));
        out.println(indent + "  " + pad("background:", 14) + fmtARGB(texture.background));
        out.println(indent + "  " + pad("intensity:", 14) + fmtFloat(texture.intensity));
        out.println(indent + "  " + pad("sample:", 14) + sampleTypes[texture.sample]);
        out.println(indent + "  " + pad("position:", 14) + fmtFloat(texture.position[0]) + " " + fmtFloat(texture.position[1]));
        out.println(indent + "  " + pad("scale:", 14) + fmtFloat(texture.scale[0]) + " " + fmtFloat(texture.scale[1]));
        out.println(indent + "  " + pad("tileU:", 14) + texture.tileU);
        out.println(indent + "  " + pad("tileV:", 14) + texture.tileV);
        out.println(indent + "  " + pad("filtertype:", 14) + filterTypes[texture.filtertype]);
      }
    }
  }

  private static class Take {
    private String                  takeName;
    private Map<String,Keyframe[]>  keyframes = new LinkedHashMap<>();

    Take (String takeName) {
      this.takeName = takeName;
    }

    private void addKeyframes (String jointName, Keyframe[] keyframes) {
      this.keyframes.put(jointName, keyframes);
    }
  }

  private static class Keyframe {
    private float[] translate, rotation, scale;

    Keyframe (float[] translate, float[] rotation, float[] scale) {
      this.translate = translate;
      this.rotation = rotation;
      this.scale = scale;
    }
  }

  private static class Joint {
    private int         jointId;
    private float[]     transformMatrix, transformLinkMatrix, transformAssociateModelMatrix;
    private float[]     bindPoseT, bindPoseR, bindPoseS;
    private String      jointName;
    private float[]     translate, rotation, scale;
    private Joint[]     children = new Joint[0];

    Joint (int jointId) {
      this.jointId = jointId;
    }

    void print (PrintStream out, String indent) {
      out.println(indent + jointName);
      out.println(indent + "  translate: " + fmtCoord(translate));
      out.println(indent + "  rotation:  " + fmtCoord(rotation));
      out.println(indent + "  scale:     " + fmtCoord(scale));
      out.println(indent + "  bindPoseT: " + fmtCoord(bindPoseT));
      out.println(indent + "  bindPoseR: " + fmtCoord(bindPoseR));
      out.println(indent + "  bindPoseS: " + fmtCoord(bindPoseS));
      out.println(indent + "  transformMatrix:");
      printMatrix(out, indent + "   ", transformMatrix);
      out.println(indent + "  transformLinkMatrix:");
      printMatrix(out, indent + "   ", transformLinkMatrix);
      out.println(indent + "  transformAssociateModelMatrix:");
      printMatrix(out, indent + "   ", transformAssociateModelMatrix);
    }

    private void printMatrix (PrintStream out, String indent, float[] mat) {
      for (int ii = 0; ii < mat.length; ii += 4) {
        out.println(indent + fmtFloat(mat[ii]) + " " + fmtFloat(mat[ii +1]) + " " + fmtFloat(mat[ii +2]));

      }
    }

    void sethildren (Joint[] children) {
      this.children = children;
    }

    void setBindPose (float[] bindPoseT, float[] bindPoseR, float[] bindPoseS) {
      this.bindPoseT = bindPoseT;
      this.bindPoseR = bindPoseR;
      this.bindPoseS = bindPoseS;
    }

    void setMatrices (float[] transformMatrix, float[] transformAssociateModelMatrix, float[] transformLinkMatrix) {
      this.transformMatrix = transformMatrix;
      this.transformAssociateModelMatrix = transformAssociateModelMatrix;
      this.transformLinkMatrix = transformLinkMatrix;
    }

    void setInfo (String jointName, float[] translate, float[] rotation, float[] scale) {
      this.jointName = jointName;
      this.translate = translate;
      this.rotation = rotation;
      this.scale = scale;
    }
  }

  private class Polygon {
    private String      polygonName;
    private Material    material;
    private float[][]   vertices, uvcoords;
    private int[][]     polyFaces;
    private Joint[]     joints;
    private Joint       rootJoint;
    private int         uvSet, polyPoints, weightVals;
    private Weight[][]  weights;
    private Map<Integer,Integer>  indexToId = new HashMap<>();
    private Map<Integer,Joint>    idToJoint = new LinkedHashMap<>();
    private Map<String,Take>      takes = new LinkedHashMap<>();

    private class Weight {
      private int   index;
      private float weight;

      Weight (int index, float weight) {
        this.index = index;
        this.weight = weight;
      }
    }

    private Take getTake (String takeName) {
      Take take = takes.get(takeName);
      if (take == null) {
        takes.put(takeName, take = new Take(takeName));
      }
      return take;
    }

    Polygon (String name, NSDictionary objDict) {
      this.polygonName = name;
      // Process NGON Tags for Material definition, if any
      NSObject[] tags = ((NSArray) objDict.get("tags")).getArray();
      for (NSObject tag : tags) {
        NSDictionary tagDict = (NSDictionary) tag;
        String tagType = getString(tagDict, "type");
        switch (tagType) {
        case "SHADERTAG":
          int materialId = getInt(tagDict, "shaderTagMaterial");
          material = idToMaterial.get(materialId);
          // Grab these other parameters for possible future use...
          //int shaderId = getInt(tagDict, "shaderId");
          //int shaderTagShadingSpace = getInt(tagDict, "shaderTagShadingSpace");
          //int shaderTagSelection = getInt(tagDict, "shaderTagSelection");
          //int shaderTagTangentSpace = getInt(tagDict, "shaderTagTangentSpace");
          //float shaderTagUVRotation = getFloat(tagDict, "shaderTagUVRotation");
          //float[] shaderTagOffset = getFloatArray(tagDict, "shaderTagOffset");
          break;
        case "SKELETONTAG":
          // Added to last joint
          // Grab these other parameters for possible future use...
          //int skeletonId = getInt(tagDict, "ID");     // Doesn't match anything...
          //int skeletonSkinningMethod = getInt(tagDict, "skeletonSkinningMethod");
          //float skeletonDropoffRate = getFloat(tagDict, "skeletonDropoffRate");
          break;
        case "MODETAG":
          // Not sure what this is for...
          //int modeId = getInt(tagDict, "ID");       // Doesn't match anything...
          break;
        case "ANCHORTAG":
        case "BAKETAG":
        case "CAUSTICTAG":
        case "HDRITAG":
        case "IKHANDLETAG":
        case "METABALLTAG":
        case "MORPHTAG":
        case "ORIENTCONSTRAINTTAG":
        case "PARENTCONSTRAINTTAG":
        case "PARTICLETAG":
        case "POINTCONSTRAINTTAG":
        case "POSETAG":
        case "PROPWINTAG":
        case "RADIOSITYTAG":
        case "RIGIDBODYTAG":
        case "ROPETAG":
        case "SOFTBODYTAG":
        case "SPLINEIKTAG":
        case "UVTAG":
        case "SCRIPTTAG":
          // Not sure what tese tags are for...
          break;
        }
      }
      // Get vertices
      int vertexCount = getInt(objDict, "vertexcount");
      vertices = new float[vertexCount][3];
      float[] vertex = getDataFloats(objDict, "vertex");
      for (int jj = 0; jj < vertex.length; jj += 4) {
        vertices[jj >> 2][0] = vertex[jj];
        vertices[jj >> 2][1] = vertex[jj + 1];
        vertices[jj >> 2][2] = vertex[jj + 2];
      }
      // Get polygons
      int polyCount = getInt(objDict, "polygoncount");
      polyFaces = new int[polyCount][];
      int[] faceVals = getDataInts(objDict, "polygons");
      int[] face = new int[0];
      int idx1 = 0, idx2 = 0;
      for (int fVal : faceVals) {
        if (fVal < 0) {
          face = polyFaces[idx1++] = new int[-fVal];
          idx2 = 0;
        } else {
          face[idx2++] = fVal;
          polyPoints++;
        }
      }
      // Get UV Coords
      float[] uvData = getDataFloats(objDict, "uvcoords");
      uvcoords = new float[uvData.length >> 2][2];
      if (uvData.length > 0) {
        uvSet = getInt(objDict, "activeuvset");
        for (int ii = 0; ii < uvData.length; ii += 4) {
          int idx = ii + uvSet * 2;
          uvcoords[ii >> 2][0] = uvData[idx];       // U
          uvcoords[ii >> 2][1] = uvData[idx + 1];   // V

        }
      }
      // Get Joint to Mesh Weight values
      for (NSObject tag : tags) {
        NSDictionary tagDict = (NSDictionary) tag;
        NSDictionary baseDict = (NSDictionary) tagDict.get("baseData");
        if (baseDict != null && baseDict.containsKey("linkData")) {
          NSObject[] linkData = ((NSArray) baseDict.get("linkData")).getArray();
          int len = linkData.length;
          joints = new Joint[len];
          weights = new Weight[len][0];
          for (int ii = 0; ii < len; ii++) {
            NSObject item = linkData[ii];
            if (item instanceof NSDictionary) {
              NSDictionary ldDict = (NSDictionary) item;
              int linkID = getInt(ldDict, "linkID");
              Joint joint = new Joint(linkID);
              joint.setBindPose(getFloatArray(ldDict, "bindPoseT"), getFloatArray(ldDict, "bindPoseR"),
                                getFloatArray(ldDict, "bindPoseS"));
              joint.setMatrices( getDataFloats(ldDict, "transformMatrix"),
                                 getDataFloats(ldDict, "transformAssociateModelMatrix"),
                                 getDataFloats(ldDict, "transformLinkMatrix"));
              joints[ii] = joint;
              idToJoint.put(linkID, joints[ii]);
              indexToId.put(ii, linkID);
              if (ldDict.containsKey("cdata")) {
                int[] cdata = getDataInts(ldDict, "cdata");
                Weight[] weightList = new Weight[cdata.length / 2];
                weights[ii] = weightList;
                for (int jj = 0; jj < cdata.length; jj += 2) {
                  int index = cdata[jj];
                  float weight = Float.intBitsToFloat(cdata[jj + 1]);
                  weightList[jj / 2] = new Weight(index, weight);
                  weightVals++;
                }
              }
            }
          }
        }
      }
    }

    private void print (PrintStream out) {
      out.println("  " +  pad("Material:", 16) + (material != null ? "'" + material.getName() + "'" : "default"));
      out.println("  " + pad("vertexcount:", 16) + vertices.length);
      if (showVertices) {
        out.println("  vertices:");
        for (float[] vertex : vertices) {
          out.println("    " + fmtCoord(vertex));
        }
      }
      out.println("  " + pad("polygon faces:", 16) + polyFaces.length);
      out.println("  " + pad("polygon points:", 16) + polyPoints);
      if (showPolys) {
        out.println("  polygons:");
        for (int[] face : polyFaces) {
          out.print("    ");
          boolean addSpace = false;
          for (int fVal : face) {
            out.print((addSpace ? " " : "") + fVal );
            addSpace = true;
          }
          out.println();
        }
      }
      out.println("  " + pad("uvcoords:", 16) + uvcoords.length);
      if (showUVs) {
        out.println("  uvcoords: (set: " + uvSet + ")");
        for (float[] uvcoord : uvcoords) {
          out.println("    " + fmtFloat(uvcoord[0]) + " " + fmtFloat(uvcoord[1]));
        }
      }
      out.println("  " + pad("joints:", 16) + joints.length);
      if (showJoints) {
        for (Joint joint : joints) {
          joint.print(out, "    ");
        }
      }
      out.println("  " + pad("weight sets:", 16) + weights.length);
      out.println("  " + pad("weight vals:", 16) + weightVals);
      if (showWeights) {
        for (int jointIndex = 0; jointIndex < weights.length; jointIndex++) {
          Weight[] weightList = weights[jointIndex];
          // Note: not all joints have weights
          int jointId = indexToId.get(jointIndex);
          String jointName = idToJoint.get(jointId).jointName;
          out.println("    joint ID = " + jointId + ", name = '" + jointName + "'");
          for (Weight weight : weightList) {
            out.println("      " + weight.index + " " + fmtFloat(weight.weight));
          }
        }
      }
      if (showJointHierarchy) {
        out.println("  hierarchy:");
        printHierarchy(rootJoint, out, "    ");
      }
      if (showKeyframes) {
        for (String takeName : takes.keySet()) {
          Take take = takes.get(takeName);
          out.println("Take: " + takeName);
          for (String target : take.keyframes.keySet()) {
            out.println("  target: " + target);
            Keyframe[] keyframes = take.keyframes.get(target);
            for (int ii = 0; ii < keyframes.length; ii++) {
              Keyframe keyframe = keyframes[ii];
              out.println("    keyframe: " + ii);
              out.println("      translate: " + fmtCoord(keyframe.translate));
              out.println("      rotation:  " + fmtCoord(keyframe.rotation));
              out.println("      scale:     " + fmtCoord(keyframe.scale));
            }
          }
        }
      }
    }
  }

  private void printHierarchy (Joint joint, PrintStream out, String indent) {
    out.println(indent + joint.jointName);
    for (Joint child : joint.children) {
      if (child != null) {
        printHierarchy(child, out, indent + "  ");
      }
    }
  }

  private List<Material> getMaterials (NSDictionary rootDict) throws Exception {
    List<Material> materialList = new ArrayList<>();
    NSObject[] materialsArray = ((NSArray) rootDict.get("Materials3")).getArray();
    int idx = 0;
    for (NSObject nsObject : materialsArray) {
      NSDictionary matDict = (NSDictionary) nsObject;
      Material material = new Material(idx, getString(matDict, "name"), getInt(matDict, "ID"));
      materialList.add(material);
      NSObject[] nodes = ((NSArray) matDict.get("nodes")).getArray();
      for (int jj = 0; jj < nodes.length; jj++) {
        NSObject node = nodes[jj];
        NSDictionary nodeDict = (NSDictionary) node;
        // Parse "xmlDef" section to figure out which textures are in use by associating conID values
        NSDictionary baseDict = (NSDictionary) nodeDict.get("baseData");
        String matXml = getString(baseDict, "xmlDef");
        Document doc = parseXml(matXml);
        if (jj == 0) {
          material.diffColor = getFloatArray(nodeDict, "diffColor");
          material.specColor = getFloatArray(nodeDict, "specColor");
          material.specSize = getFloat(nodeDict, "specSize");
          material.reflColor = getFloatArray(nodeDict, "reflColor");
          material.reflBlur = getFloat(nodeDict, "reflBlur");
          material.reflSamples = getInt(nodeDict, "reflSamples");
          material.reflFresnel = getBoolean(nodeDict, "reflFresnel");
          material.transColor = getFloatArray(nodeDict, "transColor");
          material.transBlur = getFloat(nodeDict, "transBlur");
          material.transSamples = getInt(nodeDict, "transSamples");
          material.transUseAlpha = getBoolean(nodeDict, "transUseAlpha");
          material.emisColor = getFloatArray(nodeDict, "emisColor");
          material.bumpType = getInt(nodeDict, "bumpType");
          Node cNode = getChildNode(doc, "param");
          NodeList xmlNodes = cNode != null ? cNode.getChildNodes() : null;
          if (xmlNodes != null) {
            for (int kk = 0; kk < xmlNodes.getLength(); kk++) {
              Node mNode = xmlNodes.item(kk);
              String nName = mNode.getNodeName();
              NamedNodeMap attrs = mNode.getAttributes();
              Node idNode = attrs.getNamedItem("conID");
              Node cnNode = attrs.getNamedItem("name");
              if (idNode != null && cnNode != null && ("color".equals(nName) || "float".equals(nName))) {
                String cId = idNode.getNodeValue();
                String cName = cnNode.getNodeValue();
                material.addTexture(cName, cId);
              }
            }
          }
        } else {
          Node iNode = getChildNode(doc, "image");
          if (iNode != null) {
            NamedNodeMap iAttrs = iNode.getAttributes();
            Node idNode = iAttrs.getNamedItem("id");
            String nodeId = idNode.getNodeValue();
            Material.Texture texture = material.getTexture(nodeId);
            if (nodeDict.containsKey("tracks2")) {
              NSObject[] tracks2 = ((NSArray) nodeDict.get("tracks2")).getArray();
              for (NSObject item : tracks2) {
                NSDictionary itemDict = (NSDictionary) item;
                String parmName = getString(itemDict, "parameter");
                switch (parmName) {
                case "background":
                  texture.background = getFloatArray(nodeDict, "background");
                  break;
                case "mixcolor":
                  texture.mixcolor = getFloatArray(nodeDict, "mixcolor");
                  break;
                case "intensity":
                  texture.intensity = getFloat(nodeDict, "intensity");
                  break;
                case "mix":
                  texture.mix = getFloat(nodeDict, "mix");
                  break;
                case "filtertype":
                  texture.filtertype = getInt(nodeDict, "filtertype");
                  break;
                case "sample":
                  texture.sample = getInt(nodeDict, "sample");
                  break;
                case "tileU":
                  texture.tileU = getBoolean(nodeDict, "tileU");
                  break;
                case "tileV":
                  texture.tileV = getBoolean(nodeDict, "tileV");
                  break;
                case "position":
                  texture.position = getFloatArray(nodeDict, "position");
                  break;
                case "scale":
                  texture.scale = getFloatArray(nodeDict, "scale");
                  break;
                case "texture":
                  texture.file = getString(nodeDict, "texture");
                  break;
                }
              }
            }
          }
        }
      }
    }
    return materialList;
  }

  public static void main (String[] args) throws Exception {
    new Cheetah3DParser(args);
  }

  private Cheetah3DParser (String[] args) throws Exception {
    String outFile = null;
    if (args.length > 0) {
      String inFile = null;
      for (int ii = 0; ii < args.length; ii++) {
        String arg = args[ii];
        if (arg.startsWith("-")) {
          switch (arg.substring(1)) {
          case "materials":
            showMaterials = true;
            break;
          case "verts":
            showVertices = true;
            break;
          case "polys":
            showPolys = true;
            break;
          case "uvs":
            showUVs = true;
            break;
          case "weights":
            showWeights = true;
            break;
          case "joints":
            showJoints = true;
            break;
          case "hierarchy":
            showJointHierarchy = true;
            break;
          case "keyframes":
            showKeyframes = true;
            break;
          case "con":
            consoleOut = true;
            break;
          case "raw":
            showRaw = true;
            break;
          case "hex":
            showHexData = true;   // Raw mode only
            break;
          case "sid":
            suppressId = true;   // Raw mode only
            break;
          case "all":
            showPolys = showMaterials = showVertices = showPolys = showUVs = showWeights = showJoints = showJointHierarchy =
                        showKeyframes = true;
            break;
          default:
            System.out.println("Invalid switch: " + arg);
            System.exit(1);
          }
        } else {
          inFile = arg;
          if (ii < args.length - 1) {
            outFile = args[ii + 1];
          }
          break;
        }
      }
      int off;
      if (inFile != null && (off = inFile.toLowerCase().indexOf(".jas")) > 0) {
        File file = new File(inFile);
        if (file.exists()) {
          if (consoleOut || (!showRaw && outFile == null)) {
            out = System.out;
          } else {
            if (outFile == null) {
              outFile = inFile.substring(0, off) + ".txt";
            }
            BufferedOutputStream bOut = new BufferedOutputStream(new FileOutputStream(new File(outFile)));
            out = new PrintStream(bOut);
          }
          NSDictionary rootDict = (NSDictionary) PropertyListParser.parse(file);
          if (showRaw) {
            // Dump indented text representation of file
            for (String key : rootDict.allKeys()) {
              NSObject obj = rootDict.get(key);
              List<String> path = new ArrayList<>();
              path.add(key);
              switch (key) {
              case "Render":
              case "Objects":
              case "Takes":
              case "Materials3":
              case "Dynamics":
              case "Animation":
              case "Layer":
                enumerate(path, new ArrayList<>(), 0, null, obj, null, " ");
                break;
              case "Version":
                NSString nStr = (NSString) obj;
                out.println("Version = '" + nStr.toString().trim() + "'");
                break;
              }
            }
          } else {
            // List available animation takes
            NSDictionary takesDict = (NSDictionary) rootDict.get("Takes");
            NSObject[] takes = ((NSArray) takesDict.get("takes")).getArray();
            if (takes.length > 0) {
              out.println("Takes:");
              for (NSObject nsObject : takes) {
                NSDictionary take = (NSDictionary) nsObject;
                out.println("  '" + take.get("name") + "'");
              }
              if (takesDict.containsKey("currentTake")) {
                out.println("currentTake: '" + getString(takesDict, "currentTake") + "'");
              }
            }
            // Process Materials
            materials = getMaterials(rootDict);
            // Process Objects
            NSObject[] objects = ((NSArray) rootDict.get("Objects")).getArray();
            processObjects(objects, null, "  ");
            // Print Materials
            for (Material material : materials) {
              out.println("Material: '" + material.getName() + "', index = " + material.index);
              if (showMaterials) {
                material.print(out, "  ");
              }
            }
            // Print Polygons
            for (Polygon polygon : polygons) {
              out.println("Polygon: '" + polygon.polygonName + "'");
              polygon.print(out);
            }
          }
        } else {
          System.out.println("Unable to read file: " + inFile);
        }
        out.flush();
        out.close();
      } else {
        System.out.println("Expecting Cheetah 3D .jas file");
      }
    } else {
      System.out.println("Usage: java -jar Cheetah3DParser.jar [optional switches] <file.jas>");
    }
  }

  private static Node getChildNode (Node node, String name) {
    NodeList children = node.getChildNodes();
    int len = children.getLength();
    for (int ii = 0; ii < len; ii++) {
      Node child = children.item(ii);
      String cName = child.getNodeName();
      if (cName.equals(name)) {
        return child;
      }
      return getChildNode(child, name);
    }
    return null;
  }

  private void processObjects (NSObject[] objects, Polygon polygon, String indent) {
    for (NSObject object : objects) {
      NSDictionary objDict = (NSDictionary) object;
      String objName = getString(objDict, "name");
      String objType = getString(objDict, "type");
      if ("NGON".equals(objType)) {
        polygon = new Polygon(objName, objDict);
        polygons.add(polygon);
        // Extract and reorder animation keyframes, if any
        //processKeyframes(objDict, polygon);
      } else if ("FOLDER".equals(objType)) {
        out.println(indent + objType + ": '" + objName + "'");
        processObjects(((NSArray) objDict.get("childs")).getArray(), polygon, indent + "  ");
      } else if ("JOINT".equals(objType)) {
        int id = getInt(objDict, "ID");
        Joint joint = polygon.idToJoint.get(id);
        if (joint != null) {
          if (polygon.rootJoint == null) {
            polygon.rootJoint = joint;
          }
          // Note: not all Joints have names
          float[] translate = getFloatArray(objDict, "position");
          float[] rotation = getFloatArray(objDict, "rotation");
          float[] scale = getFloatArray(objDict, "scale");
          joint.setInfo(objName, translate, rotation, scale);
          NSObject[] children = ((NSArray) objDict.get("childs")).getArray();
          Joint[] childJoints = new Joint[children.length];
          for (int ii = 0; ii < children.length; ii++) {
            NSDictionary childDict = (NSDictionary) children[ii];
            int childId = getInt(childDict, "ID");
            childJoints[ii] = polygon.idToJoint.get(childId);
          }
          joint.sethildren(childJoints);
        }
        // Extract and reorder animation keyframes, if any
        processKeyframes(objDict, polygon, joint);
        processObjects(((NSArray) objDict.get("childs")).getArray(), polygon, indent + "  ");
      } else if ("CAMERA".equals(objType)) {
        // Not used
      }
    }
  }

  private void processKeyframes (NSDictionary objDict, Polygon polygon, Joint joint) {
    if (objDict.containsKey("tracks2")) {
      Map<String, List<Float[][]>> takeMap = new LinkedHashMap<>();
      NSObject[] tracks2 = ((NSArray) objDict.get("tracks2")).getArray();
      for (NSObject nsObject : tracks2) {
        NSDictionary tracks2Dict = (NSDictionary) nsObject;
        String parameter = getString(tracks2Dict, "parameter");
        if ("position".equals(parameter) || "rotation".equals(parameter) || "scale".equals(parameter)) {
          int parmIdx = parmOrder.get(parameter);
          NSObject[] pTakes = ((NSArray) tracks2Dict.get("takes")).getArray();
          for (NSObject pTake : pTakes) {
            NSDictionary take = (NSDictionary) pTake;
            String takeName = getString(take, "name");
            List<Float[][]> takeList;
            if (!takeMap.containsKey(takeName)) {
              takeMap.put(takeName, takeList = new ArrayList<>());
            } else {
              takeList = takeMap.get(takeName);
            }
            NSObject[] fcurves = ((NSArray) take.get("fcurves")).getArray();
            for (int kk = 0; kk < fcurves.length; kk++) {
              NSDictionary fcVals = (NSDictionary) fcurves[kk];
              byte[] data = getDataBytes(fcVals, "keys");
              int numKeyframes = getInt(data, 0);
              for (int ll = 0; ll < numKeyframes; ll++) {
                Float[][] parmValues;
                if (ll >= takeList.size()) {
                  parmValues = new Float[3][3];      // position, rotation, scale
                  takeList.add(parmValues);
                } else {
                  parmValues = takeList.get(ll);
                }
                int kIdx = ll * 27 + 8 + 20;
                float kVal = getFloat(data, kIdx);
                parmValues[parmIdx][kk] = kVal;
              }
            }
          }
        }
      }
      if (showKeyframes) {
        // Get Keyframes
        for (String key : takeMap.keySet()) {
          List<Float[][]> tList = takeMap.get(key);
          if (tList.size() > 0) {
            Take take = polygon.getTake(key);
            String target = joint != null ? joint.jointName : polygon.polygonName;
            Keyframe[] kfArray = new Keyframe[tList.size()];
            for (int idx = 0; idx < tList.size(); idx++) {
              Float[][] keyframe = tList.get(idx);
              float[] position = new float[] {0, 0, 0};
              float[] rotation = new float[] {0, 0, 0};
              float[] scale = new float[] {1, 1, 1};
              for (int ii = 0; ii < keyframe.length; ii++) {
                float[] fVal = new float[3];
                for (int jj = 0; jj < fVal.length; jj++) {
                  Float val = keyframe[ii][jj];
                  if (val != null) {
                    fVal[jj] = val;
                  }
                }
                if (ii == 0) {
                  position = fVal;
                } else if (ii == 1) {
                  rotation = fVal;
                } else {
                  scale = fVal;
                }
              }
              kfArray[idx] = new Keyframe(position, rotation, scale);
            }
            take.addKeyframes(target, kfArray);
          }
        }
      }
    }
  }

  private static String fmtCoord (float[] val) {
    return fmtFloat(val[0]) + " " + fmtFloat(val[1]) + " " + fmtFloat(val[2]);
  }

  private static String pad (String str, int minLength) {
    StringBuilder strBuilder = new StringBuilder(str);
    while (strBuilder.length() < minLength) {
      strBuilder.append(" ");
    }
    return strBuilder.toString();
  }

  private static String fmtARGB (float[] ary) {
    return fmtFloat(ary[0]) + " " + fmtFloat(ary[1]) + " " + fmtFloat(ary[2]) + " " + fmtFloat(ary[3]);
  }

  private static int getInt (NSDictionary dict, String key) {
    return ((NSNumber) dict.get(key)).intValue();
  }

  private static float getFloat (NSDictionary dict, String key) {
    return ((NSNumber) dict.get(key)).floatValue();
  }

  private static boolean getBoolean (NSDictionary dict, String key) {
    return ((NSNumber) dict.get(key)).boolValue();
  }

  private static String getString (NSDictionary dict, String key) {
    return dict.get(key).toString();
  }

  private static float[] getFloatArray (NSDictionary dict, String key) {
    NSObject[] ary = ((NSArray) dict.get(key)).getArray();
    float[] fVals = new float[ary.length];
    for (int ii = 0; ii < ary.length; ii++) {
      NSNumber nbr = (NSNumber) ary[ii];
      fVals[ii] = nbr.floatValue();
    }
    return fVals;
  }

  private static int[] getDataInts (NSDictionary dict, String key) {
    byte[] data = getDataBytes(dict, key);
    int[] ints = new int[data.length / 4];
    for (int ii = 0; ii < data.length; ii += 4) {
      ints[ii >> 2] = getInt(data, ii);
    }
    return ints;
  }

  private static float[] getDataFloats (NSDictionary dict, String key) {
    int[] ints = getDataInts(dict, key);
    float[] floats = new float[ints.length];
    for (int ii = 0; ii < ints.length; ii++) {
      floats[ii] = Float.intBitsToFloat(ints[ii]);
    }
    return floats;
  }

  private static byte[] getDataBytes (NSDictionary dict, String key) {
    NSData nData = (NSData) dict.get(key);
    return nData.bytes();
  }

  private void enumerate (List<String> path, List<NSObject> pList, int lIdx, String dKey, NSObject cObj, NSObject pObj,
                                 String indent) throws Exception {
    if (cObj instanceof NSArray) {
      NSArray nAry = (NSArray) cObj;
      if (!(pObj instanceof NSArray) && !(pObj instanceof NSDictionary)) {
        out.print(getPath(path) + (pObj == null ? " " : ""));
      }
      out.println("Array (" + nAry.count() + " items)");
      NSObject[] oArr = nAry.getArray();
      int idx = 0;
      for (NSObject aObj : oArr) {
        out.print(indent + getPath(path) + "[" + idx + "] ");
        path.add("[" + idx + "]");
        enumerate(path, pList, idx, null, aObj, cObj, indent + " ");
        if (path.size() > 0) {
          path.remove(path.size() - 1);
        }
        idx++;
      }
    } else if (cObj instanceof NSDictionary) {
      NSDictionary nDict = (NSDictionary) cObj;
      NSObject name = nDict.get("name");
      if (!(pObj instanceof NSArray) && !(pObj instanceof NSDictionary)) {
        out.print(getPath(path) + (pObj == null ? " " : ""));
      }
      out.println("Dictionary (" + nDict.count() + " items)" + (name != null ? " - name: " + name : ""));
      if (nDict.count() > 0) {
        pList.add(nDict);
      }
      for (String key : nDict.allKeys()) {
        NSObject dObj = nDict.objectForKey(key);
        out.print(indent + getPath(path) + "." + key + ": ");
        path.add(key);
        enumerate(path, pList, lIdx, key, dObj, cObj, indent + " ");
        if (path.size() > 0) {
          path.remove(path.size() - 1);
        }
      }
      if (nDict.count() > 0) {
        pList.remove(pList.size() - 1);
      }
      //} else if (obj instanceof NSSet) {
      //  NSSet nSet = (NSSet) obj;
      //  // Not used by Cheetah 3D
      //  out.println("NSSet (" + nSet.count() + " items)");
    } else if (cObj instanceof NSString) {
      NSString nStr = (NSString) cObj;
      if ("xmlDef".equals(dKey)) {
        out.println("XML String: ");
        printXml(indent, nStr.toString());
      } else {
        out.println("= '" + nStr.toString() + "'");
      }
    } else if (cObj instanceof NSNumber) {
      NSNumber nNum = (NSNumber) cObj;
      switch (nNum.type()) {
      case 0:   // Integer
        int iVal = nNum.intValue();
        if (suppressId && ("ID".equals(dKey) || "linkID".equals(dKey))) {
          out.println("= <suppressed>");
        } else {
          out.println("= " + iVal + " (0x" + Integer.toHexString(iVal).toUpperCase() + ")");
        }
        break;
      case 1:   // Real
        float fVal = nNum.floatValue();
        out.println("= " + fmtFloat(fVal));
        break;
      case 2:   // Boolean
        out.println("= " + nNum.boolValue());
        break;
      }
    } else if (cObj instanceof NSData) {
      boolean isMatrix = "transformMatrix".equals(dKey) || "transformAssociateModelMatrix".equals(dKey) ||
                         "transformLinkMatrix".equals(dKey);
      boolean isKeys = "keys".equals(dKey);
      boolean isVertex = "vertex".equals(dKey);
      boolean uvcoords = "uvcoords".equals(dKey);
      boolean isCData = "cdata".equals(dKey);
      boolean isPolygons = "polygons".equals(dKey);
      boolean isPointArray = "pointArray".equals(getValue(pList, -1, "parameter"));
      boolean isSpecial = !(isMatrix | isKeys | isVertex | uvcoords | isCData | isPolygons | isPointArray);
      NSData nData = (NSData) cObj;
      byte[] data = nData.bytes();
      out.print("Data (" + data.length + " bytes)");
      String name = getValue(pList, -1, "name");
      if (name != null) {
        out.print(" - name: " + name);
      }
      out.println();
      if (isKeys) {
        int idx = 0;
        String jointName = getValue(pList, -4, "name");
        String jointType = getValue(pList, -3, "parameter");
        if (jointType != null) {
          String[] jType = {"X", "Y", "Z"};
          jointType += " " + jType[lIdx];
        }
        if (jointName != null && jointType != null) {
          out.println(indent + "// Joint:  " + jointName + " - " + jointType);
        }
        for (int ii = 0; ii < 8 && idx < data.length; ii++) {
          printHex(data, indent, ii);
          if ((ii & 3) == 3) {
            int intVal = getInt(data, idx - 3);
            out.print(String.format("%2d", intVal));
            if (idx == 3) {
              out.println("          <- number of keyframes");
            } else if (idx == 7) {
              out.println("          <- always 27");
            }
          }
          idx++;
        }
        while (idx < data.length) {
          for (int ii = 0; ii < 27 && idx < data.length; ii++) {
            printHex(data, indent, ii);
            if ((ii & 3) == 3) {
              float fVal = getFloat(data, idx - 3);
              out.print(fmtFloat(fVal));
              if ((ii >> 2) == 5) {
                out.print("  <- keyframe value, index " + ((idx - 31) / 27));
              }
              out.println();
            }
            if (ii >= 24 && !showHexData) {
              out.print((((ii - 24) & 3) == 0 ? "" : " ") + String.format("%02X", data[idx]));
            }
            idx++;
          }
          out.println();
        }
      } else {
        if (isPointArray) {
          out.println(indent + " // pointArray");
        }
        if (isCData) {
          out.println(indent + " // even/odd values contain <vertex index>/<joint weight> values");
        }
        if (isPolygons) {
          out.println(indent + " // negative values indicate number of sides");
        }
        if (isSpecial) {
          out.println(indent + " // bytes      ltl Endian    big Endian");
        }
        if (uvcoords) {
          out.println(indent + " // first pair of float values in each set is UV Coord set 0, 2nd is set 1");
          out.println(indent + " // V coord is 1.0 - V in OBJ files");
        }
        indent += " ";
        for (int ii = 0; ii < data.length; ii++) {
          if (isSpecial) {
            out.print(((ii & 3) == 0 ? indent : " ") + String.format("%02X", data[ii]) + ((ii & 3) == 3 ? " = " : ""));
            if ((ii & 3) == 3 || ii == data.length - 1) {
              int lEnd = data[ii] << 24 | (data[ii - 1] & 0xFF) << 16 | (data[ii - 2] & 0xFF) << 8 | (data[ii - 3] & 0xFF);
              int bEnd = data[ii - 3] << 24 | (data[ii - 2] & 0xFF) << 16 | (data[ii - 1] & 0xFF) << 8 | (data[ii] & 0xFF);
              out.println(String.format("0x%09X", bEnd) + " : " + String.format("0x%09X", lEnd));
            }
          } else {
            printHex(data, indent, ii);
            if ((ii & 3) == 3 || ii == data.length - 1) {
              int fIndx = ii >> 2;
              if (isVertex || uvcoords) {
                out.print(fmtFloat(getFloat(data, ii - 3)));
                if (uvcoords) {
                  if ((fIndx & 3) == 0) {
                    out.print("  <- UV set 0, index " + (fIndx / 4));
                  }
                  if ((fIndx & 3) == 2) {
                    out.print("  <- UV set 1, index " + (fIndx / 4));
                  }
                } else {
                  if ((fIndx & 3) == 0) {
                    out.print("  <- index " + (fIndx / 4));
                  }
                }
              } else if (isMatrix) {
                out.print(fmtFloat(getFloat(data, ii - 3)));
                int idx = ii >> 2;
                out.print("  <- row " + (idx / 4) + ", col " + (idx % 4));
              } else if (isPolygons) {
                // Print polygon to vertex index info
                out.print(String.format("%2d", getInt(data, ii - 3)));
              } else if (isCData) {
                // Print vextex/weight info
                if ((fIndx & 1) == 0) {
                  out.print(String.format("%9d", getInt(data, ii - 3)) + "  <- vertex index");
                } else {
                  out.print(fmtFloat(getFloat(data, ii - 3)) + "  <- weight");
                }
              } else if (isPointArray) {
                // Note: litle endian format
                int intVal = data[ii] << 24 | (data[ii - 1] & 0xFF) << 16 | (data[ii - 2] & 0xFF) << 8 | (data[ii - 3] & 0xFF);
                int idx = ii >> 2;
                if (idx < 4) {
                  out.print(String.format("%9d", intVal));
                  if (idx == 0) {
                    out.print("  <- number of points");
                  } else if (idx == 1) {
                    out.print("  <- number of floats/point");
                  }
                } else {
                  out.print(fmtFloat(Float.intBitsToFloat(intVal)));
                  if ((idx - 4) % 3 == 0) {
                    out.print("  <- index " + ((idx - 4) / 3));
                  }
                }
              }
              out.println();
            }
          }
        }
      }
    } else {
      throw new IllegalStateException("Unknown type: " + cObj.toString());
    }
  }

  private void printHex (byte[] data, String indent, int ii) {
    if ((ii & 3) == 0) {
      out.print(indent);
    } else if (showHexData) {
      out.print(" ");
    }
    if (showHexData) {
      out.print(String.format("%02X", data[ii]) + ((ii & 3) == 3 ? " = " : ""));
    }
  }

  private static String getValue (List<NSObject> pList, int idx, String key) {
    int pIdx = pList.size() + idx;
    if (pIdx >= 0) {
      NSObject obj = pList.get(pIdx);
      if (obj instanceof NSDictionary) {
        NSDictionary dObj = (NSDictionary) obj;
        if (dObj.containsKey(key)) {
          return dObj.objectForKey(key).toString();
        }
      }
    }
    return null;
  }

  private static String getPath (List<String> path) {
    StringBuilder buf = new StringBuilder();
    for (String item : path) {
      if (buf.length() > 0 && !item.startsWith("[")) {
        buf.append('.');
      }
      buf.append(item);
    }
    return buf.toString();
  }

  private static String fmtFloat (float fVal) {
    fVal = fVal == -0 ? 0 : fVal;
    if (Math.abs(fVal) > 100000) {
      return String.format((fVal >= 0 ? " %e" : "%e"), fVal);
    } else {
      return (fVal >= 0 ? " " : "") + df.format(fVal);
    }
  }

  private static int getInt (byte[] data, int idx) {
    // Note: big endian format
    return data[idx] << 24 | (data[idx + 1] & 0xFF) << 16 | (data[idx + 2] & 0xFF) << 8 | (data[idx + 3] & 0xFF);
  }

  private static float getFloat (byte[] data, int idx) {
    return Float.intBitsToFloat(getInt(data, idx));
  }

  private void printXml (String indent, String xml) throws Exception {
    Document document = parseXml(xml);
    Transformer tform = TransformerFactory.newInstance().newTransformer();
    tform.setOutputProperty(OutputKeys.INDENT, "yes");
    tform.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
    ByteArrayOutputStream bOut = new ByteArrayOutputStream();
    tform.transform(new DOMSource(document), new StreamResult(bOut));
    String[] lines = bOut.toString().split("\n");
    for (String line : lines) {
      if (!line.startsWith("<?xml")) {
        out.println(indent + line);
      }
    }
  }

  private Document parseXml (String xml) throws Exception {
    InputStream xIn = new ByteArrayInputStream(xml.getBytes());
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    DocumentBuilder builder = factory.newDocumentBuilder();
    return builder.parse(xIn);
  }
}
