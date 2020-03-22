## **Cheetah3DParser**
[Cheetah3D](https://www.cheetah3d.com) is a wonderful 3D modeling, rendering and animation program for the Mac written by Martin Wengenmayer.  Cheetah3DParser is experimental code I created that's designed to read Cheetah3D's `.jas` files and then dump them out in a human-readable form.  Since `.jas` files are encoded in [Apple Binary pList format](https://en.wikipedia.org/wiki/Property_list), Cheetah3DParser uses the [dd-plist](https://github.com/3breadt/dd-plist) library to read the raw data.

### Caveats
This code is a work in progress and is intended mainly as a tool to enable me to view and study how Cheetah 3D stores its data.  As a consequence, the code is actually pretty ugly in places.  My eventual goal is to use what I've learned from writing Cheetah3DParser to write an importer for JavaFx that can directly read `.jas` files.  However, at the moment, this is still in the planning stages.

Also, while this document will attempt to explain how Cheetah3D stores data in `.jas` files, the information I present here is based only on my own research and may be flawed, or incomplete.  So, I encourage you to make your own study of one, or more .jas files using Cheetah3DParser and reach your own conclusions.

### Running Cheetah3DParser
First, download the program's executable `.jar` file named `Cheetah3DParser.jar` [using this link](https://github.com/wholder/Cheetah3DParser/blob/master/out/artifacts/Cheetah3DParser_jar) and copy it into a convenient folder that also contains some `.jas` files.  Then (assuming you have Java 8, or later installed on your computer), you can run the code from the command line, or terminal, like this:
 ```
  java -jar Cheetah3DParser.jar <filename>
  ``` 
where `<filename>` is the name of a `.jas` file in the same directory.  In response, Cheetah3DParser will print a list of basic info about the input file to the console, like this:
 ```
Takes:
  'mixamo.com'
CurrentTake:    'mixamo.com'
Material0:      'dreyar_M'
Polygon:        'Dreyar'
  Material:       'dreyar_M'
  vertexcount:    7397
  polygon faces:  7428
  polygon points: 29094
  uvcoords:       29094
  joints:         52
  weight sets:    52
  weight vals:    17125
```
To get more detailed information, you can add one, or more of the following "switch" options to the command invocation, like this:
 ```
  java -jar Cheetah3DParser.jar -switch1 -switch2 <filename>
  ``` 
where each switch starts with a minus sign (-) and all switches are added before the input file name.  Currently available switches are:

Switch | Function
------ | --------
`-materials` | list all the properties for each material
`-verts` | list the vertices for each Polygon object (x,y,v values for each point)
`-polys` | list the polygon face windings for each polygon (see note 1)
`-uvs` | list UV Coords for each polygon (u,v values for each point in polygon list, note 2)
`-weights` | list joint to vertex weighting values for each polygon 
`-joints` | list the Joints for each polygon
`-hierarchy` | list the Joint hierarchy for each polygon
`-keyframes` | list the keyframes for each polygon
`-all` | list all information for each polygon (see note 3)
`-con` | redirect output to the console
`-raw` | See section: "Cheetah3DParser.s "raw" mode"
`-sid` | See section: "Cheetah3DParser.s "raw" mode"
`-hex` | See section: "Cheetah3DParser.s "raw" mode"

Note 1: Lists 3, or more integer values are indexes into the list of vertices to define the points for a polygon.

Note 2: Each index value in the polygon faces list corresponds to a value in the UV list.

Note 3: the switch "`-all`" is the same as adding `-materials`, `-verts`, `-polys`, `-uvs`, `-weights`, `-joints`, `-hierarchy`, `-keyframes` to the command.  Caution, this can produce a lot of output text.

### Cheetah3DParser.s "raw" mode
In "raw" mode, Cheetah3DParser will parse the raw, pList information in the input file, convert it to indented text and write to an output (in the same directory) named `xx.txt`, where "`xx`" is the name of the file (minus the `.jas` suffix) you entered for `<filename>`.  Run Cheetah3DParser in "raw" mode, like this:
 ```
  java -jar Cheetah3DParser.jar -raw <filename>
  ``` 
By default, Cheetah3DParser does not display the raw data bytes for data it is able to decode into things like a list of vertices.  However, adding the "`-hex`" switch will force Cheetah3DParser to display data this as a series of hex bytes printed before the decoded values (note: the examples of decoded data blocks shown later in this file were printed with the "`-hex`" switch enabled in order to show how the data is decoded from the bytes.)  For example:
 ```
  java -jar Cheetah3DParser.jar -raw -hex <filename>
  ``` 
One technique I used in analyzing Cheetah3D files, was to save a version of a `.jas` file with a minor change, decode it and the unchanged `.jas` file using Cheetah3DParser and then use a text compile compare program, such as BBEdit, to show the differences between the two decoded files.  However, some `.jas` files, such as those that contain Material definitions, include numeric "id" values that can vary between the two files you're trying to compare.  However, adding the "`-sid`" (suppress id) switch will cause Cheetah3DParser to supress printing these values, which makes it much easier to compare the files.  For example:
 ```
  java -jar Cheetah3DParser.jar -raw -sid <filename>
  ``` 
Note: you can use multiple switches, but all switches should be delimited by a space and should all be specified before the `.jas` filename.

### Understanding the pList Format
The root of data is a Dictionary (a set of key/value pairs) object that contains a set of predefined keys that indicate various subsections in the the file.  The root keys are:
 ```
    Render:     Typical keys are: renderSettingResolution, renderSettingGamma, etc.
    Takes:      Typical keys are: prevTo, length, name, prevFrom
    Materials3: Typical keys are: name = <mesh name>, nodes
    Dynamics:   Unknown, as the files I've tested do not contain this subsection
    Animation:  Typical keys are: animFPS, animTimerPlay, etc.
    Layer:      Typical keys are: layerVisibleInRenderer, layerName, layerColor, etc.
    Objects:    Typical keys are: Camera, <joint names>, <mesh names>
    Version:    String value
```
The value of each key/value pair in a Dictionary can contain another Dictionary, an Array of values, or one of the following value types:
```
    String      A sequence of characters
    Integer     32 bit `int` value
    Real        32 bit `float` value
    Boolean     true/false
```
Array objects, in turn, can contain zero or more values where each value can be a Dictionary, an Array, or one of the value types.

Cheetah3DParser enumerates this hierarchical structure as an indented series of lines.  The following shows how the beginning of a typical `.jas` files is displayed Cheetah3DParser:
```
Render Array (2 items)
 Render[0] Dictionary (47 items)
  Render[0].renderSettingGBufferDepth: = 0 (0x0)
  Render[0].renderSettingGIType: = 0 (0x0)
  Render[0].renderSettingFilmic: = false
  Render[0].renderSettingResolution: Array (2 items)
   Render[0].renderSettingResolution[0] = 640 (0x280)
   Render[0].renderSettingResolution[1] = 480 (0x1E0)
  Render[0].renderSettingGIMaxRayLength: =  10000.000000
  Render[0].renderSettingGamma: =  1.800000
  .. lines deleted
  Render[0].type: = 'CHEETAH'
  Render[0].renderSettingGISpecDepth: = 0 (0x0)
  Render[0].renderSettingPhotonMapSamples: = 250 (0xFA)
  Render[0].tracks2: Array (14 items)
   Render[0].tracks2[0] Dictionary (5 items)
    Render[0].tracks2[0].value: = 0 (0x0)
    Render[0].tracks2[0].takes: Array (0 items)
    Render[0].tracks2[0].parameter: = 'giType'
    Render[0].tracks2[0].register: Dictionary (0 items)
    Render[0].tracks2[0].auxparameter: Dictionary (0 items)
   Render[0].tracks2[1] Dictionary (5 items)
    Render[0].tracks2[1].value: =  1.000000
    .. lines deleted
```
A line like this, for example:
 ```
    Render[0].tracks2[0].value: = 0 (0x0)
```
can be read as indicating that the root key entry "`Render`" contains an Array where the 0th entry contains a Dictionary in which the key "`tracks2`" links to another Array where the 0th entry contains a key named "`value`" that links to an Integer with a value of 0.  The idea of presenting the information in this way is to enable you to trace any deeply embedded value back up to its root.
 
### Special Data Blocks
In addition to the String, Integer, Real and Boolean value types, Cheetah 3D stores many of its more important data as blocks data in the form of byte arrays.  Each of these byte arrays can broken down into other kinds of data structures which can then contain lists of Vertex values, and so on.  The following sections go into more detail into how to decode these blocks.

#### Vertices
Vertices are defined as 4 `float` values contained in a Data block named "`vertex`".  The last `float` of each set of 4 is always set to 0.  Note: according to the author of Cheetah3d, Martin Wengenmayer, this 4th `float` value is used to support "soft point selections".
```
Objects[1].vertex: Data (128 bytes)
  BF 00 00 00 = -0.500000  <- index 0 X
  BF 00 00 00 = -0.500000  <- index 0 Y
  3F 00 00 00 =  0.500000  <- index 0 Z
  00 00 00 00 =  0.000000  <- always zero

  BF 00 00 00 = -0.500000  <- index 1 X
  3F 00 00 00 =  0.500000  <- index 1 Y
  3F 00 00 00 =  0.500000  <- index 1 Z
  00 00 00 00 =  0.000000  <- always zero
```
Notice the bytes making up each `float` are in big endian format!

#### Vertices (alternate)
Vertices are also stored as 3 `float` values prefixed by a 16 byte header in a section with a "`parameter`" value of "`pointArray`" and the byte data array with a key value of "`value`".  For the files I have tested, this data seems to exactly match the vertices saved under the "vertex" key (see above).  So, given that the data in "`pointArray`" uses little endian, rather than the big endian format used by other data structures in .jas files, I suggest using the "`vertex`" values as the source of vertex information.
```
Objects[1].tracks2[3].value: Data (112 bytes)
  // pointArray
  08 00 00 00 =  8      <- number of points
  03 00 00 00 =  3      <- number of floats/point
  01 00 00 00 =  1
  00 00 00 00 =  0

  00 00 00 BF = -0.500000  <- index 0 X
  00 00 00 BF = -0.500000  <- index 0 Y
  00 00 00 3F =  0.500000  <- index 0 Z

  00 00 00 BF = -0.500000  <- index 1 X
  00 00 00 3F =  0.500000  <- index 1 Y
  00 00 00 3F =  0.500000  <- index 1 Z
  ... repeats, as needed
```
Notice the bytes making up each `int` and `float` are in little endian format!

#### Polygons
Polygons are defined by an array of `int` values where the start of a polygon is indicated by a negative which, when made positive,  indicates the number of vertex indexes that will follow, such as:
```
  polygons: Data (146088 bytes)
    FF FF FF FC = -4
    00 00 00 01 =  1
    00 00 00 02 =  2
    00 00 00 03 =  3
    00 00 00 00 =  0

    FF FF FF FC = -4
    00 00 00 04 =  4
    00 00 00 05 =  5
    00 00 00 02 =  2
    00 00 00 01 =  1
    ... repeats, as needed
```
Notice the bytes making up each `int` are in big endian format!

#### UV Coords
UV Coords seem to follow the order in which the polygon faces are enumerated in the previously-mentioned Data item named "`polygons`", such as:
```
Objects[1].uvcoords: Data (465504 bytes) - name: Dreyar
  // first pair of float values in each set is UV Coord set 0, 2nd is set 1
  3E 92 56 DE =  0.285819  <- coord set 0, index 0
  3E BA 5B B8 =  0.363981
  3E 92 56 DE =  0.285819  <- coord set 1, index 0
  3E BA 5B B8 =  0.363981

  3E 9A E4 2A =  0.302522  <- coord set 0, index 1
  3E BA 5D 28 =  0.363992
  3E 9A E4 2A =  0.302522  <- coord set 1, index 1
  3E BA 5D 28 =  0.363992
    ... repeats, as needed
```
Notice the bytes making up each float are in big endian format!  

Also, the 2nd value, V, is reversed from how it's used in OBJ files, so you must subtract this value from 1.0 to get the unreversed value.

#### Joints
Joint info is contained in the Array, which is linked to the "baseData" key in a Dictionary object, like this:
```
  baseData: Dictionary (1 items)
  linkData: Array (52 items)
    linkBound                           typically false
    associateID                         typically 0
    linkID                              id matching to joint tree "ID" value
    bindPoseS                           typically all values 1 (scaling)
    linkMode                            typically all values 0
    transformMatrix                     typically 4x4 identity matrix
    transformAssociateModelMatrix       typically 4x4 identity matrix
    cdata                               <vertex index>/<weight> values (See note 7)
    transformLinkMatrix                 typically contains joint translation matrix
    bindPoseT                           typically all values 0 (translation)
    bindPoseR                           typically all values 0 (rotation)
```

#### Joint Vertex Weights
The "cdata" Data section for each joint ("linkData" item) contains <vertex index>/<weight> values, such as:
```
  cdata: Data (3096 bytes)
    // even/odd values contain <vertex index>/<joint weight> values
    00 00 00 00 =  0          <- index
    3C D9 5A 0D =  0.0265322  <- weight

    00 00 00 01 =  1          <- index
    3C D8 83 9D =  0.0264299  <- weight
    ... repeats, as needed
```
Notice the bytes making up each `int` and `float` are in big endian format!

#### Joint Heirarchies

Joints, when used, are linked together in a parent->children type tree structure where a key value called "`child`" contains a list of the child Joints attached to that Joint.

#### Keyframe Values
Keyframe data is stored as Data in a Dictionary item with the key "`keys`".  The first 4 bytes form an `int` value that indicates the number of keyframes in the block.  The next 4 bytes form an `int` that always seems to be set to a value of 27.  After this, each keyframe is encoded as a block of 27 bytes.  The first 24 bytes form 6, 32 bit `float` values with the 6th and last value of each block being the keyframe parameter.  The purpose of the other values in these blocks is currently unknown.  For example:
```
  index[0]: Dictionary (3 items)
    keys: Data (872 bytes)
    // mixamorig:Hips - position X
    00 00 00 20 = 32          <- number of frames
    00 00 00 1B = 27          <- always 27

    00 00 00 00 =  0
    BB B6 0B 61 = -0.0055556
    3B B6 0B 61 =  0.0055556
    BB 1F 85 C5 = -0.0024341
    3B 1F 85 C5 =  0.0024341
    3C 2D CE 54 =  0.0106083  <- keyframe value
    00 00 00
    ... repeats, as needed
```

Notice the bytes for each `int` and `float` are in big endian format!

Each Keyframe value is labelled by "`name`" and "`parameter`" values in a preceding Dictionary that indicates the Joint name such as "`mixamorig:Hips`".  The "`parameter`" value can be "`position`", "`rotation`", or "`scale`".  See the code for more details on how this data is detected.

#### Vertex Normals
As near as I can determine, Vertex normals do not appear to be saved in the `.jas` files I used as test examples.  However, it's possible there may be circumstances where vertex normals are stored.

#### Materials Info
If materials are used, the key "`shaderTagMaterial`" contains an integer value that is used as a numeric "Id" that links to an item in a Dictionary in the "Materials3 subsection with a key value of "`ID`" and which links to an Integer value that matches the value of "`shaderTagMaterial`".  In addition, XML data in the "xmlDef" (in the "baseData" dictionary) contain "id" and "conID" vaules that are used to determine which texture maps are active and the order in which the material information is enumerated n the "tracks2" list (see code for more details.)

### **Requirements**
A [Java JDK or JVM](https://www.java.com/en/) or [OpenJDK](http://openjdk.java.net) version 8, or later must be installed in order to run the code.  There is also a [**Runnable JAR file**](https://github.com/wholder/Cheetah3DParser/blob/master/out/artifacts/Cheetah3DParser_jar) included in the checked in code that you can download and run without having to compile the source code.

## Credits
Cheetah3DParser uses the following Java code to perform some of its functions, or build this project:
- [dd-plist](https://github.com/3breadt/dd-plist) is used to read the Apple plist format.
- [IntelliJ IDEA from JetBrains](https://www.jetbrains.com/idea/) (my favorite development environment for Java coding. Thanks JetBrains!)
- [Alan Scrivener](https://www.linkedin.com/in/alanscrivener) for assistance with 3D matrix math.
