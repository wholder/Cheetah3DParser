import com.dd.plist.*;
import org.w3c.dom.Document;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.*;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;

/*
 *  Experimental code to read Cheetah 3D's .jas files which are written in the Apple Binary Plist format and
 *  then dump them out as an indented text file designed to show the hierarchy of the data.
 *
 *  Author: Wayne Holder, 2020
 *  License: MIT (https://opensource.org/licenses/MIT)
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
 * Note 6: Joint info is contained in the Array, which is linked to the "baseData" key in an Dictionary
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
  private static final boolean suppressId  = false;
  private static final boolean showHexData = false;
  private static final boolean useSystemOut = false;
  private static DecimalFormat df  = new DecimalFormat("0.000000");
  private static PrintStream   out = System.out;

  public static void main (String[] args) throws Exception {
    if (args.length > 0) {
      int off = args[0].toLowerCase().indexOf(".jas");
      if (off > 0) {
        String inFile = args[0];
        File file = new File(inFile);
        if (file.exists()) {
          if (useSystemOut || args.length > 1 && "console".equals(args[1])) {
            out = System.out;
          } else {
            String outFile = inFile.substring(0, off) + ".txt";
            BufferedOutputStream bOut = new BufferedOutputStream(new FileOutputStream(new File(outFile)));
            out = new PrintStream(bOut);
          }
          NSDictionary rootDict = (NSDictionary) PropertyListParser.parse(file);
          for (String key : rootDict.allKeys()) {
            NSObject obj = rootDict.objectForKey(key);
            List<String> path = new ArrayList<>();
            path.add(key);
            switch (key) {
            case "Render":
            case "Takes":       // NSArray[2] prevTo, length, name, prevFrom
            case "Materials3":  // NSArray[1] name = "dreyar_M", nodes
            case "Dynamics":
            case "Animation":   // NSDictionary(10) animFPS, animTimerPlay, etc.
            case "Layer":       // NSArray[8] layerVisibleInRenderer, layerName, layerColor, etc.
            case "Objects":     // NSArray[3] Camera, Dreyar, mixamorig:Hips
              enumerate(path, new ArrayList<>(), 0, null, obj, null, " ");
              break;
            case "Version":
              NSString nStr = (NSString) obj;
              out.println("Version = '" + nStr.toString().trim() + "'");
              break;
            }
          }
        } else {
          System.out.println("Unable to read file: " + args[0]);
        }
      } else {
        System.out.println("Expecting Cheetah 3D .jas file");
      }
    } else {
      System.out.println("Usage: java -jar Cheetah3DParser.jar <file.jas>");
    }
  }

  private static void enumerate (List<String> path, List<NSObject> pList, int lIdx, String dKey, NSObject cObj, NSObject pObj,
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
        out.println("= " + toString(fVal));
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
              float fVal = toString(data, idx - 3);
              out.print(toString(fVal));
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
                out.print(toString(toString(data, ii - 3)));
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
                out.print(toString(toString(data, ii - 3)));
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
                  out.print(toString(toString(data, ii - 3)) + "  <- weight");
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
                  out.print(toString(Float.intBitsToFloat(intVal)));
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

  private static void printHex (byte[] data, String indent, int ii) {
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

  private static String toString (float fVal) {
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

  private static float toString (byte[] data, int idx) {
    return Float.intBitsToFloat(getInt(data, idx));
  }

  private static void printXml (String indent, String xml) throws Exception {
    InputStream xIn = new ByteArrayInputStream(xml.getBytes());
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    DocumentBuilder builder = factory.newDocumentBuilder();
    Document document = builder.parse(xIn);
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
}
