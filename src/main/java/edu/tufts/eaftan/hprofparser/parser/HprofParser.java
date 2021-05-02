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
import edu.tufts.eaftan.hprofparser.handler.RecordHandler;
import edu.tufts.eaftan.hprofparser.parser.datastructures.*;

import java.io.*;
import java.util.ArrayList;
import java.util.HashMap;

/**
 * Parses an hprof heap dump file in binary format.  The hprof dump file format is documented in
 * the hprof_b_spec.h file in the hprof source, which is open-source and available from Oracle.
 */
public class HprofParser {

    private final RecordHandler handler;
    private final HashMap<Long, ClassInfo> classMap;
    private final HashMap<Long, String> strings;
    private long toRemove = -1;
    private long newHeapdumpSize;

    public HprofParser(RecordHandler handler) {
        this.handler = handler;
        classMap = new HashMap<>();
        strings = new HashMap<>();
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
        FileOutputStream fo = new FileOutputStream("out.hprof");
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(fo));

        // header
        String format = readUntilNull(in, out);
        int idSize = in.readInt();
        out.writeInt(idSize);
        long startTime = in.readLong();
        out.writeLong(startTime);
        handler.header(format, idSize, startTime);

        // records
        boolean done;
        do {
            done = parseRecord(in, DummyDataOutput.INSTANCE, idSize, true);
        } while (!done);
        System.out.println("First pass done");
        in.close();
        fs = new FileInputStream(file);
        in = new DataInputStream(new BufferedInputStream(fs));
        readUntilNull(in, DummyDataOutput.INSTANCE);
        in.readInt();
        in.readLong();
        do {
            done = parseRecord(in, out, idSize, false);
        } while (!done);
        System.out.println("Second pass done");
        out.close();
        fs = new FileInputStream("out.hprof");
        in = new DataInputStream(new BufferedInputStream(fs));
        readUntilNull(in, DummyDataOutput.INSTANCE);
        in.readInt();
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

        // otherwise propagate the EOFException
        rereadInt(in, out); // Time
        final int bytesLeftRaw = in.readInt();
        if (tag == 0x1c && !isFirst) {
            System.out.println("USing new HD size: " + newHeapdumpSize + " instead of " + bytesLeftRaw);
            out.writeInt((int) newHeapdumpSize);
        } else {
            out.writeInt(bytesLeftRaw);
        }
        long bytesTotal = Integer.toUnsignedLong(bytesLeftRaw);
        long bytesLeft = bytesTotal;

        switch (tag) {
            case 0x1 -> {
                // String in UTF-8
                long stringId = rereadId(idSize, in, out);
                bytesLeft -= idSize;
                byte[] stringBytes = new byte[(int) bytesLeft];
                rereadFully(in, out, stringBytes);
                handler.stringInUTF8(stringId, new String(stringBytes));
                strings.put(stringId, new String(stringBytes));
            }
            case 0x2 -> {
                // Load class
                int serialNum = rereadInt(in, out);
                long classObjId = rereadId(idSize, in, out);
                int stackSerialNum = rereadInt(in, out);
                long nameStringId = rereadId(idSize, in, out);
                handler.loadClass(serialNum, classObjId, stackSerialNum, nameStringId);
                if (toRemove < 0 && "java/util/HashMap$Node".equals(strings.get(nameStringId))) {
                    toRemove = classObjId;
                    System.out.println("Found target class: " + toRemove);
                }
            }
            case 0x3 -> {
                // Unload class
                int serialNum = rereadInt(in, out);
                handler.unloadClass(serialNum);
            }
            case 0x4 -> {
                // Stack frame
                long stackframeId = rereadId(idSize, in, out);
                long methodNameId = rereadId(idSize, in, out);
                long sigId = rereadId(idSize, in, out);
                long sourceNameId = rereadId(idSize, in, out);
                int serialNum = rereadInt(in, out);
                int location = rereadInt(in, out);
                handler.stackFrame(stackframeId, methodNameId, sigId, sourceNameId, serialNum, location);
            }
            case 0x5 -> {
                // Stack trace
                int stackSerialNum = rereadInt(in, out);
                int threadSerialNum = rereadInt(in, out);
                int numFrames = rereadInt(in, out);
                bytesLeft -= 12;
                long[] frameIds = new long[(int) bytesLeft / idSize];
                for (int i = 0; i < frameIds.length; i++) {
                    frameIds[i] = rereadId(idSize, in, out);
                }
                handler.stackTrace(stackSerialNum, threadSerialNum, numFrames, frameIds);
            }
            case 0x6 -> {
                // Alloc sites
                short flagMask = rereadShort(in, out);
                float cutoffRatio = rereadFloat(in, out);
                int totalLiveBytes = rereadInt(in, out);
                int totLiveInstances = rereadInt(in, out);
                long totalBytesAlloc = rereadLong(in, out);
                long totalInstancesAlloc = rereadLong(in, out);
                AllocSite[] allocSites = new AllocSite[rereadInt(in, out)];
                for (int i = 0; i < allocSites.length; i++) {
                    byte arrayIndicator = rereadByte(in, out);
                    int classSerial = rereadInt(in, out);
                    int stackSerial = rereadInt(in, out);
                    int numLiveBytes = rereadInt(in, out);
                    int numLiveInstances = rereadInt(in, out);
                    int bytesAlloced = rereadInt(in, out);
                    int instancesAlloced = rereadInt(in, out);

                    allocSites[i] = new AllocSite(
                            arrayIndicator,
                            classSerial,
                            stackSerial,
                            numLiveBytes,
                            numLiveInstances,
                            bytesAlloced,
                            instancesAlloced
                    );
                }
                handler.allocSites(
                        flagMask,
                        cutoffRatio,
                        totalLiveBytes,
                        totLiveInstances,
                        totalBytesAlloc,
                        totalInstancesAlloc,
                        allocSites
                );
            }
            case 0x7 -> {
                // Heap summary
                int totalLiveBytes = rereadInt(in, out);
                int totLiveInstances = rereadInt(in, out);
                long totalBytesAlloc = rereadLong(in, out);
                long totalInstancesAlloc = rereadLong(in, out);
                handler.heapSummary(totalLiveBytes, totLiveInstances, totalBytesAlloc, totalInstancesAlloc);
            }
            case 0xa -> {
                // Start thread
                int threadSerialNum = rereadInt(in, out);
                long threadObjectId = rereadId(idSize, in, out);
                int stackSerialNum = rereadInt(in, out);
                long threadNameId = rereadId(idSize, in, out);
                long groupNameId = rereadId(idSize, in, out);
                long parentGroupNameId = rereadId(idSize, in, out);
                handler.startThread(
                        threadSerialNum,
                        threadObjectId,
                        stackSerialNum,
                        threadNameId,
                        groupNameId,
                        parentGroupNameId
                );
            }
            case 0xb -> {
                // End thread
                int threadSerialNum = rereadInt(in, out);
                handler.endThread(threadSerialNum);
            }
            case 0xc ->
                    // Heap dump
                    throw new RuntimeException();
            case 0x1c -> {
                // Heap dump segment
                handler.heapDumpSegment();
                if (isFirst) {
                    newHeapdumpSize = bytesTotal;
                }
                while (bytesLeft > 0) {
                    bytesLeft -= parseHeapDump(in, out, idSize, isFirst);
                }
                System.out.println("Bytes left: " + bytesLeft);
            }
            case 0x2c ->
                    // Heap dump end (of segments)
                    handler.heapDumpEnd();
            case 0xd -> {
                // CPU samples
                int totalNumSamples = rereadInt(in, out);
                CPUSample[] samples = new CPUSample[rereadInt(in, out)];
                for (int i = 0; i < samples.length; i++) {
                    int numSamples = rereadInt(in, out);
                    int stackSerial = rereadInt(in, out);
                    samples[i] = new CPUSample(numSamples, stackSerial);
                }
                handler.cpuSamples(totalNumSamples, samples);
            }
            case 0xe -> {
                // Control settings
                int bitmaskFlags = rereadInt(in, out);
                short stacktraceDepth = rereadShort(in, out);
                handler.controlSettings(bitmaskFlags, stacktraceDepth);
            }
            default -> throw new HprofParserException("Unexpected top-level record type: " + tag);
        }

        return false;
    }

    // returns number of bytes parsed
    private int parseHeapDump(DataInput in, DataOutput out, int idSize, boolean firstPass) throws IOException {

        byte tag = rereadByte(in, out);
        int bytesRead = 1;

        switch (tag) {
            case -1 -> {    // 0xFF
                // Root unknown
                long objId = rereadId(idSize, in, out);
                handler.rootUnknown(objId);
                bytesRead += idSize;
            }
            case 0x01 -> {
                // Root JNI global
                long objId = rereadId(idSize, in, out);
                long jniGlobalRef = rereadId(idSize, in, out);
                handler.rootJNIGlobal(objId, jniGlobalRef);
                bytesRead += 2 * idSize;
            }
            case 0x02 -> {
                // Root JNI local
                long objId = rereadId(idSize, in, out);
                int threadSerial = rereadInt(in, out);
                int frameNum = rereadInt(in, out);
                handler.rootJNILocal(objId, threadSerial, frameNum);
                bytesRead += idSize + 8;
            }
            case 0x03 -> {
                // Root Java frame
                long objId = rereadId(idSize, in, out);
                int threadSerial = rereadInt(in, out);
                int frameNum = rereadInt(in, out);
                handler.rootJavaFrame(objId, threadSerial, frameNum);
                bytesRead += idSize + 8;
            }
            case 0x04 -> {
                // Root native stack
                long objId = rereadId(idSize, in, out);
                int threadSerial = rereadInt(in, out);
                handler.rootNativeStack(objId, threadSerial);
                bytesRead += idSize + 4;
            }
            case 0x05 -> {
                // Root sticky class
                long objId = rereadId(idSize, in, out);
                handler.rootStickyClass(objId);
                bytesRead += idSize;
            }
            case 0x06 -> {
                // Root thread block
                long objId = rereadId(idSize, in, out);
                int threadSerial = rereadInt(in, out);
                handler.rootThreadBlock(objId, threadSerial);
                bytesRead += idSize + 4;
            }
            case 0x07 -> {
                // Root monitor used
                long objId = rereadId(idSize, in, out);
                handler.rootMonitorUsed(objId);
                bytesRead += idSize;
            }
            case 0x08 -> {
                // Root thread object
                long objId = rereadId(idSize, in, out);
                int threadSerial = rereadInt(in, out);
                int stackSerial = rereadInt(in, out);
                handler.rootThreadObj(objId, threadSerial, stackSerial);
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
                final boolean remove = !firstPass && classObjId == toRemove;
                int instanceSize = rereadInt(in, out);//TODO zero?
                bytesRead += idSize * 7 + 8;

                /* Constants */
                short numConstants = rereadShort(in, out);
                bytesRead += 2;
                Preconditions.checkState(numConstants >= 0);
                Constant[] constants = new Constant[numConstants];
                for (int i = 0; i < numConstants; i++) {
                    short constantPoolIndex = in.readShort();
                    out.writeShort(constantPoolIndex);
                    byte btype = rereadByte(in, out);
                    bytesRead += 3;
                    Type type = Type.hprofTypeToEnum(btype);
                    int[] bytesReadRef = {bytesRead};
                    Value<?> v = rereadValue(type, idSize, in, out, bytesReadRef);
                    bytesRead = bytesReadRef[0];

                    constants[i] = new Constant(constantPoolIndex, v);
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
                    Value<?> v = rereadValue(type, idSize, in, out, bytesReadRef);
                    bytesRead = bytesReadRef[0];

                    statics[i] = new Static(staticFieldNameStringId, v);
                }

                /* Instance fields */
                short numFields = in.readShort();
                if (remove) {
                    out.writeShort(0);
                } else {
                    out.writeShort(numFields);
                }
                bytesRead += 2;
                Preconditions.checkState(numFields >= 0);
                InstanceField[] instanceFields = new InstanceField[numFields];
                final int bytesBeforeFields = bytesRead;
                for (int i = 0; i < numFields; i++) {
                    long fieldNameStringId = rereadId(idSize, in, remove ? DummyDataOutput.INSTANCE : out);
                    byte btype = in.readByte();
                    if (!remove) out.writeByte(btype);
                    bytesRead += idSize + 1;
                    Type type = Type.hprofTypeToEnum(btype);
                    instanceFields[i] = new InstanceField(fieldNameStringId, type);
                }
                if (firstPass && classObjId == toRemove) {
                    newHeapdumpSize -= bytesRead - bytesBeforeFields;
                }

                /*
                 * We need to know the types of the values in an instance record when
                 * we parse that record.  To do that we need to look up the class and
                 * its superclasses.  So we need to store class records in a hash
                 * table.
                 */
                classMap.put(classObjId, new ClassInfo(classObjId, superclassObjId, instanceSize, instanceFields));
                handler.classDump(classObjId,
                        stackSerial,
                        superclassObjId,
                        loaderObjId,
                        signerObjId,
                        protectDomainId,
                        reserved1,
                        reserved2,
                        instanceSize,
                        constants,
                        statics,
                        instanceFields
                );
            }
            case 0x21 -> {
                // Instance dump
                long objId = rereadId(idSize, in, out);
                int stackSerial = rereadInt(in, out);
                long classObjId = rereadId(idSize, in, out);
                int bytesFollowing = in.readInt();
                final boolean remove2 = !firstPass && classObjId == toRemove;
                if (!remove2) {
                    out.writeInt(bytesFollowing);
                } else {
                    out.writeInt(0);
                }
                Preconditions.checkState(bytesFollowing >= 0);
                byte[] packedData = new byte[bytesFollowing];
                in.readFully(packedData);
                if (!remove2) out.write(packedData);
                if (firstPass && classObjId == toRemove) {
                    newHeapdumpSize -= bytesFollowing;
                }

                /*
                 * because class dump records come *after* instance dump records,
                 * we don't know how to interpret the values yet.  we have to
                 * record the instances and process them at the end.
                 */
                processInstance(new Instance(objId, stackSerial, classObjId, packedData), idSize);
                bytesRead += idSize * 2 + 8 + bytesFollowing;
            }
            case 0x22 -> {
                // Object array dump
                long objId = rereadId(idSize, in, out);
                int stackSerial = rereadInt(in, out);
                int arraySize = rereadInt(in, out);
                long elementClassObjId = rereadId(idSize, in, out);
                Preconditions.checkState(arraySize >= 0);
                long[] elementIds = new long[arraySize];
                for (int i = 0; i < arraySize; i++) {
                    elementIds[i] = rereadId(idSize, in, out);
                }
                handler.objArrayDump(objId, stackSerial, elementClassObjId, elementIds);
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
                Value<?>[] values = new Value[arraySize];
                Type t = Type.hprofTypeToEnum(elementType);
                int[] byteReadRef = {bytesRead};
                for (int i = 0; i < values.length; i++) {
                    values[i] = rereadValue(t, idSize, in, out, byteReadRef);
                }
                bytesRead = byteReadRef[0];
                handler.primArrayDump(objId, stackSerial, elementType, values);
            }
            default -> throw new HprofParserException("Unexpected heap dump sub-record type: " + tag);
        }

        return bytesRead;

    }

    private void processInstance(Instance i, int idSize) throws IOException {
        ByteArrayInputStream bs = new ByteArrayInputStream(i.packedValues);
        DataInputStream input = new DataInputStream(bs);

        ArrayList<Value<?>> values = new ArrayList<>();

        // superclass of Object is 0
        long nextClass = i.classObjId;
        while (nextClass != 0) {
            ClassInfo ci = classMap.get(nextClass);
            nextClass = ci.superClassObjId;
            for (InstanceField field : ci.instanceFields) {
                int[] bytesReadRef = {0};
                Value<?> v = rereadValue(field.type, idSize, input, DummyDataOutput.INSTANCE, bytesReadRef);
                values.add(v);
            }
        }
        Value<?>[] valuesArr = new Value[values.size()];
        valuesArr = values.toArray(valuesArr);
        handler.instanceDump(i.objId, i.stackTraceSerialNum, i.classObjId, valuesArr);
    }

    private static long rereadId(int idSize, DataInput in, DataOutput out) throws IOException {
        long id;
        if (idSize == 4) {
            id = rereadInt(in, out);
            id &= 0x00000000ffffffff;     // undo sign extension
        } else if (idSize == 8) {
            id = rereadLong(in, out);
        } else {
            throw new IllegalArgumentException("Invalid identifier size " + idSize);
        }

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

    private static void rereadFully(DataInput in, DataOutput out, byte[] data) throws IOException {
        in.readFully(data);
        out.write(data);
    }

    private Value<?> rereadValue(
            Type type,
            int idSize,
            DataInput in,
            DataOutput out,
            int[] bytesReadRef
    ) throws IOException {
        return switch (type) {
            case OBJ -> {
                long vid = rereadId(idSize, in, out);
                bytesReadRef[0] += idSize;
                yield new Value<>(type, vid);
            }
            case BOOL -> {
                boolean vbool = rereadBoolean(in, out);
                bytesReadRef[0] += 1;
                yield new Value<>(type, vbool);
            }
            case CHAR -> {
                char vc = rereadChar(in, out);
                bytesReadRef[0] += 2;
                yield new Value<>(type, vc);
            }
            case FLOAT -> {
                float vf = rereadFloat(in, out);
                bytesReadRef[0] += 4;
                yield new Value<>(type, vf);
            }
            case DOUBLE -> {
                double vd = rereadDouble(in, out);
                bytesReadRef[0] += 8;
                yield new Value<>(type, vd);
            }
            case BYTE -> {
                byte vbyte = rereadByte(in, out);
                bytesReadRef[0] += 1;
                yield new Value<>(type, vbyte);
            }
            case SHORT -> {
                short vs = rereadShort(in, out);
                bytesReadRef[0] += 2;
                yield new Value<>(type, vs);
            }
            case INT -> {
                int vi = rereadInt(in, out);
                bytesReadRef[0] += 4;
                yield new Value<>(type, vi);
            }
            case LONG -> {
                long vl = rereadLong(in, out);
                bytesReadRef[0] += 8;
                yield new Value<>(type, vl);
            }
        };
    }
}

