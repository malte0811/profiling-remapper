/*
 * Copyright 2014 Edward Aftandilian. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package edu.tufts.eaftan.hprofparser.parser;

import com.google.common.base.Preconditions;
import edu.tufts.eaftan.hprofparser.parser.datastructures.ClassInfo;
import edu.tufts.eaftan.hprofparser.parser.datastructures.InstanceField;
import edu.tufts.eaftan.hprofparser.parser.datastructures.Static;
import edu.tufts.eaftan.hprofparser.parser.datastructures.Type;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraftforge.srgutils.IMappingFile;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.function.ToLongFunction;

/**
 * Parses an hprof heap dump file in binary format.  The hprof dump file format is documented in
 * the hprof_b_spec.h file in the hprof source, which is open-source and available from Oracle.
 */
public class HprofParser {

    private final Long2ObjectMap<ClassInfo> classMap = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectMap<String> strings = new Long2ObjectOpenHashMap<>();
    private final Long2LongMap classIdToNameId = new Long2LongOpenHashMap();
    private final IMappingFile mappings;
    private boolean logging = false;

    public HprofParser(IMappingFile mappings) {
        this.mappings = mappings;
    }

    public void parse(File file) throws IOException {
        /* The file format looks like this:
         *
         * header:
         *   [u1]* - a null-terminated sequence of bytes representing the format
         *           name and version
         *   u4 - size of identifiers/pointers
         *   u4 - high number of word of number of milliseconds since 0:00 GMT,
         *        1/1/70
         *   u4 - low number of word of number of milliseconds since 0:00 GMT,
         *        1/1/70
         *
         * records:
         *   u1 - tag denoting the type of record
         *   u4 - number of microseconds since timestamp in header
         *   u4 - number of bytes that follow this field in this record
         *   [u1]* - body
         */

        FileInputStream fs = new FileInputStream(file);
        DataInputStream in = new DataInputStream(new BufferedInputStream(fs));
        boolean done;
        int idSize;

        System.out.println("Starting first pass");
        // header
        String format = readUntilNull(in, DummyDataOutput.INSTANCE);
        idSize = in.readInt();
        long startTime = in.readLong();

        // records
        do {
            done = parseRecord(in, DummyDataOutput.INSTANCE, idSize, true);
        } while (!done);
        System.out.println("First pass done");
        in.close();

        remap();

        fs = new FileInputStream(file);
        in = new DataInputStream(new BufferedInputStream(fs));
        FileOutputStream fo = new FileOutputStream("out.hprof");
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(fo));
        readUntilNull(in, out);
        out.writeInt(idSize);
        out.writeLong(startTime);
        in.readInt();
        in.readLong();
        do {
            done = parseRecord(in, out, idSize, false);
        } while (!done);
        System.out.println("Second pass done");
        out.close();

        //logging = true;
        fs = new FileInputStream("out.hprof");
        in = new DataInputStream(new BufferedInputStream(fs));
        readUntilNull(in, DummyDataOutput.INSTANCE);
        idSize = in.readInt();
        in.readLong();
        do {
            done = parseRecord(in, DummyDataOutput.INSTANCE, idSize, true);
        } while (!done);
        System.out.println("Parsed output");
    }

    public static String readUntilNull(DataInput in, DataOutput out) throws IOException {

        int bytesRead = 0;
        byte[] bytes = new byte[25];

        while ((bytes[bytesRead] = in.readByte()) != 0) {
            out.write(bytes[bytesRead]);
            bytesRead++;
            if (bytesRead >= bytes.length) {
                byte[] newBytes = new byte[bytesRead + 20];
                System.arraycopy(bytes, 0, newBytes, 0, bytes.length);
                bytes = newBytes;
            }
        }
        out.write(0);
        return new String(bytes, 0, bytesRead);
    }

    /**
     * @return true if there are no more records to parse
     */
    @SuppressWarnings("unused")
    private boolean parseRecord(DataInput in, DataOutput out, int idSize, boolean isFirst) throws IOException {

        /* format:
         *   u1 - tag
         *   u4 - time
         *   u4 - length
         *   [u1]* - body
         */

        // if we get an EOF on this read, it just means we're done
        byte tag;
        try {
            tag = rereadByte(in, out);
        } catch (EOFException e) {
            return true;
        }

        if (logging) System.out.println("Read tag " + Integer.toHexString(tag));
        // otherwise propagate the EOFException
        rereadInt(in, out); // Time
        final int bytesLeftRaw = in.readInt();
        final long bytesTotal = Integer.toUnsignedLong(bytesLeftRaw);
        long bytesLeft = bytesTotal;
        if (tag == 0x1) {
            // String in UTF-8
            long stringId = readId(idSize, in);
            bytesLeft -= idSize;
            byte[] stringBytes = new byte[(int) bytesLeft];
            in.readFully(stringBytes);
            String stringToWrite;
            if (!isFirst) {
                stringToWrite = strings.get(stringId);
            } else {
                stringToWrite = new String(stringBytes);
                strings.put(stringId, stringToWrite);
            }
            var bytes = stringToWrite.getBytes(StandardCharsets.UTF_8);
            out.writeInt(bytes.length + idSize);
            writeId(idSize, stringId, out);
            if (logging)
                System.out.println("Read string " + stringToWrite + " (length " + bytes.length + ") for ID " + Long.toHexString(
                        stringId));

            out.write(bytes);
        } else {
            out.writeInt(bytesLeftRaw);
        }

        switch (tag) {
            case 0x1 -> {
            }
            case 0x2 -> {
                // Load class
                int serialNum = rereadInt(in, out);
                long classObjId = rereadId(idSize, in, out);
                int stackSerialNum = rereadInt(in, out);
                long nameStringId = rereadId(idSize, in, out);
                classIdToNameId.put(classObjId, nameStringId);
            }
            case 0x3 -> {
                // Unload class
                int serialNum = rereadInt(in, out);
            }
            case 0x4 -> {
                // Stack frame
                long stackframeId = rereadId(idSize, in, out);
                long methodNameId = rereadId(idSize, in, out);
                long sigId = rereadId(idSize, in, out);
                long sourceNameId = rereadId(idSize, in, out);
                int serialNum = rereadInt(in, out);
                int location = rereadInt(in, out);
            }
            case 0x5 -> {
                // Stack trace
                int stackSerialNum = rereadInt(in, out);
                int threadSerialNum = rereadInt(in, out);
                int numFrames = rereadInt(in, out);
                bytesLeft -= 12;
                var numFrames2 = (int) (bytesLeft / idSize);//TODO == numFrames?
                for (int i = 0; i < numFrames2; i++) {
                    long frameId = rereadId(idSize, in, out);
                }
            }
            case 0x6 -> {
                // Alloc sites
                short flagMask = rereadShort(in, out);
                float cutoffRatio = rereadFloat(in, out);
                int totalLiveBytes = rereadInt(in, out);
                int totLiveInstances = rereadInt(in, out);
                long totalBytesAlloc = rereadLong(in, out);
                long totalInstancesAlloc = rereadLong(in, out);
                var numAllocs = rereadInt(in, out);
                for (int i = 0; i < numAllocs; i++) {
                    byte arrayIndicator = rereadByte(in, out);
                    int classSerial = rereadInt(in, out);
                    int stackSerial = rereadInt(in, out);
                    int numLiveBytes = rereadInt(in, out);
                    int numLiveInstances = rereadInt(in, out);
                    int bytesAlloced = rereadInt(in, out);
                    int instancesAlloced = rereadInt(in, out);
                }
            }
            case 0x7 -> {
                // Heap summary
                int totalLiveBytes = rereadInt(in, out);
                int totLiveInstances = rereadInt(in, out);
                long totalBytesAlloc = rereadLong(in, out);
                long totalInstancesAlloc = rereadLong(in, out);
            }
            case 0xa -> {
                // Start thread
                int threadSerialNum = rereadInt(in, out);
                long threadObjectId = rereadId(idSize, in, out);
                int stackSerialNum = rereadInt(in, out);
                long threadNameId = rereadId(idSize, in, out);
                long groupNameId = rereadId(idSize, in, out);
                long parentGroupNameId = rereadId(idSize, in, out);
            }
            case 0xb -> {
                // End thread
                int threadSerialNum = rereadInt(in, out);
            }
            case 0xc ->
                    // Heap dump
                    throw new RuntimeException();
            case 0x1c -> {
                // Heap dump segment
                while (bytesLeft > 0) {
                    bytesLeft -= parseHeapDump(in, out, idSize, isFirst);
                }
            }
            case 0x2c -> {
            }
            case 0xd -> {
                // CPU samples
                int totalNumSamples = rereadInt(in, out);
                int numTraces = rereadInt(in, out);
                for (int i = 0; i < numTraces; i++) {
                    int numSamples = rereadInt(in, out);
                    int stackSerial = rereadInt(in, out);
                }
            }
            case 0xe -> {
                // Control settings
                int bitmaskFlags = rereadInt(in, out);
                short stacktraceDepth = rereadShort(in, out);
            }
            default -> throw new HprofParserException("Unexpected top-level record type: " + tag);
        }

        return false;
    }

    // returns number of bytes parsed
    @SuppressWarnings("unused")
    private int parseHeapDump(DataInput in, DataOutput out, int idSize, boolean firstPass) throws IOException {

        byte tag = rereadByte(in, out);
        int bytesRead = 1;

        switch (tag) {
            case -1 -> {    // 0xFF
                // Root unknown
                long objId = rereadId(idSize, in, out);
                bytesRead += idSize;
            }
            case 0x01 -> {
                // Root JNI global
                long objId = rereadId(idSize, in, out);
                long jniGlobalRef = rereadId(idSize, in, out);
                bytesRead += 2 * idSize;
            }
            case 0x02 -> {
                // Root JNI local
                long objId = rereadId(idSize, in, out);
                int threadSerial = rereadInt(in, out);
                int frameNum = rereadInt(in, out);
                bytesRead += idSize + 8;
            }
            case 0x03 -> {
                // Root Java frame
                long objId = rereadId(idSize, in, out);
                int threadSerial = rereadInt(in, out);
                int frameNum = rereadInt(in, out);
                bytesRead += idSize + 8;
            }
            case 0x04 -> {
                // Root native stack
                long objId = rereadId(idSize, in, out);
                int threadSerial = rereadInt(in, out);
                bytesRead += idSize + 4;
            }
            case 0x05 -> {
                // Root sticky class
                long objId = rereadId(idSize, in, out);
                bytesRead += idSize;
            }
            case 0x06 -> {
                // Root thread block
                long objId = rereadId(idSize, in, out);
                int threadSerial = rereadInt(in, out);
                bytesRead += idSize + 4;
            }
            case 0x07 -> {
                // Root monitor used
                long objId = rereadId(idSize, in, out);
                bytesRead += idSize;
            }
            case 0x08 -> {
                // Root thread object
                long objId = rereadId(idSize, in, out);
                int threadSerial = rereadInt(in, out);
                int stackSerial = rereadInt(in, out);
                bytesRead += idSize + 8;
            }
            case 0x20 -> {
                // Class dump
                long classObjId = rereadId(idSize, in, out);
                int stackSerial = rereadInt(in, out);
                long superclassObjId = rereadId(idSize, in, out);
                long loaderObjId = rereadId(idSize, in, out);
                long signerObjId = rereadId(idSize, in, out);
                long protectDomainId = rereadId(idSize, in, out);
                long reserved1 = rereadId(idSize, in, out);
                long reserved2 = rereadId(idSize, in, out);
                int instanceSize = rereadInt(in, out);
                bytesRead += idSize * 7 + 8;

                /* Constants */
                short numConstants = rereadShort(in, out);
                bytesRead += 2;
                Preconditions.checkState(numConstants >= 0);
                for (int i = 0; i < numConstants; i++) {
                    short constantPoolIndex = in.readShort();
                    out.writeShort(constantPoolIndex);
                    byte btype = rereadByte(in, out);
                    bytesRead += 3;
                    Type type = Type.hprofTypeToEnum(btype);
                    int[] bytesReadRef = {bytesRead};
                    rereadValue(type, idSize, in, out, bytesReadRef);
                    bytesRead = bytesReadRef[0];
                }

                /* Statics */
                short numStatics = rereadShort(in, out);
                bytesRead += 2;
                Preconditions.checkState(numStatics >= 0);
                Static[] statics = new Static[numStatics];
                for (int i = 0; i < numStatics; i++) {
                    long staticFieldNameStringId = rereadId(idSize, in, out);
                    byte btype = rereadByte(in, out);
                    bytesRead += idSize + 1;
                    Type type = Type.hprofTypeToEnum(btype);
                    int[] bytesReadRef = {bytesRead};
                    rereadValue(type, idSize, in, out, bytesReadRef);
                    bytesRead = bytesReadRef[0];

                    statics[i] = new Static(staticFieldNameStringId);
                }

                /* Instance fields */
                short numFields = rereadShort(in, out);
                bytesRead += 2;
                Preconditions.checkState(numFields >= 0);
                InstanceField[] instanceFields = new InstanceField[numFields];
                for (int i = 0; i < numFields; i++) {
                    long fieldNameStringId = rereadId(idSize, in, out);
                    byte btype = in.readByte();
                    out.writeByte(btype);
                    bytesRead += idSize + 1;
                    Type type = Type.hprofTypeToEnum(btype);
                    instanceFields[i] = new InstanceField(fieldNameStringId, type);
                }

                /*
                 * We need to know the types of the values in an instance record when
                 * we parse that record.  To do that we need to look up the class and
                 * its superclasses.  So we need to store class records in a hash
                 * table.
                 */
                classMap.put(
                        classObjId,
                        new ClassInfo(classObjId, superclassObjId, instanceSize, instanceFields, statics)
                );
            }
            case 0x21 -> {
                // Instance dump
                long objId = rereadId(idSize, in, out);
                int stackSerial = rereadInt(in, out);
                long classObjId = rereadId(idSize, in, out);
                int bytesFollowing = in.readInt();
                    out.writeInt(bytesFollowing);
                Preconditions.checkState(bytesFollowing >= 0);
                byte[] packedData = new byte[bytesFollowing];
                in.readFully(packedData);
                out.write(packedData);

                bytesRead += idSize * 2 + 8 + bytesFollowing;
            }
            case 0x22 -> {
                // Object array dump
                long objId = rereadId(idSize, in, out);
                int stackSerial = rereadInt(in, out);
                int arraySize = rereadInt(in, out);
                long elementClassObjId = rereadId(idSize, in, out);
                Preconditions.checkState(arraySize >= 0);
                for (int i = 0; i < arraySize; i++) {
                    long elementId = rereadId(idSize, in, out);
                }
                bytesRead += (2 + arraySize) * idSize + 8;
            }
            case 0x23 -> {
                // Primitive array dump
                long objId = rereadId(idSize, in, out);
                int stackSerial = rereadInt(in, out);
                int arraySize = rereadInt(in, out);
                byte elementType = rereadByte(in, out);
                bytesRead += idSize + 9;
                Preconditions.checkState(arraySize >= 0);
                Type t = Type.hprofTypeToEnum(elementType);
                int[] byteReadRef = {bytesRead};
                for (int i = 0; i < arraySize; i++) {
                    rereadValue(t, idSize, in, out, byteReadRef);
                }
                bytesRead = byteReadRef[0];
            }
            default -> throw new HprofParserException("Unexpected heap dump sub-record type: " + tag);
        }

        return bytesRead;

    }

    private static long readId(int idSize, DataInput in) throws IOException {
        long id;
        if (idSize == 4) {
            id = in.readInt();
            id &= 0x00000000ffffffff;     // undo sign extension
        } else if (idSize == 8) {
            id = in.readLong();
        } else {
            throw new IllegalArgumentException("Invalid identifier size " + idSize);
        }

        return id;
    }

    private static void writeId(int idSize, long id, DataOutput out) throws IOException {
        if (idSize == 4) {
            out.writeInt((int) id);//TODO may be wrong?
        } else if (idSize == 8) {
            out.writeLong(id);
        } else {
            throw new IllegalArgumentException("Invalid identifier size " + idSize);
        }
    }

    private static long rereadId(int idSize, DataInput in, DataOutput out) throws IOException {
        long id = readId(idSize, in);
        writeId(idSize, id, out);
        return id;
    }

    private static long rereadLong(DataInput in, DataOutput out) throws IOException {
        final long result = in.readLong();
        out.writeLong(result);
        return result;
    }

    private static byte rereadByte(DataInput in, DataOutput out) throws IOException {
        final byte result = in.readByte();
        out.writeByte(result);
        return result;
    }

    private static int rereadInt(DataInput in, DataOutput out) throws IOException {
        final int result = in.readInt();
        out.writeInt(result);
        return result;
    }

    private static boolean rereadBoolean(DataInput in, DataOutput out) throws IOException {
        final boolean result = in.readBoolean();
        out.writeBoolean(result);
        return result;
    }

    private static char rereadChar(DataInput in, DataOutput out) throws IOException {
        final char result = in.readChar();
        out.writeChar(result);
        return result;
    }

    private static float rereadFloat(DataInput in, DataOutput out) throws IOException {
        final float result = in.readFloat();
        out.writeFloat(result);
        return result;
    }

    private static double rereadDouble(DataInput in, DataOutput out) throws IOException {
        final double result = in.readDouble();
        out.writeDouble(result);
        return result;
    }

    private static short rereadShort(DataInput in, DataOutput out) throws IOException {
        final short result = in.readShort();
        out.writeShort(result);
        return result;
    }

    private void rereadValue(
            Type type, int idSize, DataInput in, DataOutput out, int[] bytesReadRef
    ) throws IOException {
        switch (type) {
            case OBJ -> {
                long vid = rereadId(idSize, in, out);
                bytesReadRef[0] += idSize;
            }
            case BOOL -> {
                boolean vbool = rereadBoolean(in, out);
                bytesReadRef[0] += 1;
            }
            case CHAR -> {
                char vc = rereadChar(in, out);
                bytesReadRef[0] += 2;
            }
            case FLOAT -> {
                float vf = rereadFloat(in, out);
                bytesReadRef[0] += 4;
            }
            case DOUBLE -> {
                double vd = rereadDouble(in, out);
                bytesReadRef[0] += 8;
            }
            case BYTE -> {
                byte vbyte = rereadByte(in, out);
                bytesReadRef[0] += 1;
            }
            case SHORT -> {
                short vs = rereadShort(in, out);
                bytesReadRef[0] += 2;
            }
            case INT -> {
                int vi = rereadInt(in, out);
                bytesReadRef[0] += 4;
            }
            case LONG -> {
                long vl = rereadLong(in, out);
                bytesReadRef[0] += 8;
            }
        };
    }

    private void remap() {
        for (var clazz : classMap.values()) {
            var classNameId = classIdToNameId.get(clazz.classObjId());
            var className = strings.get(classNameId);
            if (className == null) {
                System.out.println("Failed to find class name for ID " + classNameId);
                continue;
            }
            var classMappings = mappings.getClass(className);
            if (classMappings == null) {
                continue;
            }
            remapFields(clazz.instanceFields(), InstanceField::fieldNameId, classMappings, className);
            remapFields(clazz.statics(), Static::fieldNameId, classMappings, className);
        }
    }

    private <T> void remapFields(
            T[] fields, ToLongFunction<T> getName, IMappingFile.IClass mappings, String className
    ) {
        for (var field : fields) {
            var nameId = getName.applyAsLong(field);
            var oldName = strings.get(nameId);
            Preconditions.checkNotNull(oldName);
            var newName = mappings.remapField(oldName);
            if (newName == null) {
                System.out.println("Failed to remap field " + oldName + " of class " + className);
                continue;
            }
            strings.put(nameId, newName);
        }
    }
}

