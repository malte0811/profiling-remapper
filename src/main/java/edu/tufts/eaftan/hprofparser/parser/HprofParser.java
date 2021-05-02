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
        int time = rereadInt(in, out);
        final int bytesLeftRaw = in.readInt();
        if (tag == 0x1c && !isFirst) {
            System.out.println("USing new HD size: " + newHeapdumpSize + " instead of " + bytesLeftRaw);
            out.writeInt((int) newHeapdumpSize);
        } else {
            out.writeInt(bytesLeftRaw);
        }
        long bytesTotal = Integer.toUnsignedLong(bytesLeftRaw);
        long bytesLeft = bytesTotal;

        long l1, l2, l3, l4;
        int i1, i2, i3, i4, i5, i6, i7, i8, i9;
        short s1;
        byte b1;
        float f1;
        byte[] bArr1;
        long[] lArr1;

        switch (tag) {
            case 0x1 -> {
                // String in UTF-8
                l1 = rereadId(idSize, in, out);
                bytesLeft -= idSize;
                bArr1 = new byte[(int) bytesLeft];
                rereadFully(in, out, bArr1);
                handler.stringInUTF8(l1, new String(bArr1));
                strings.put(l1, new String(bArr1));
            }
            case 0x2 -> {
                // Load class
                i1 = rereadInt(in, out);
                l1 = rereadId(idSize, in, out);
                i2 = rereadInt(in, out);
                l2 = rereadId(idSize, in, out);
                handler.loadClass(i1, l1, i2, l2);
                if (toRemove < 0 && "java/util/HashMap$Node".equals(strings.get(l2))) {
                    toRemove = l1;
                    System.out.println("Found target class: " + toRemove);
                }
            }
            case 0x3 -> {
                // Unload class
                i1 = rereadInt(in, out);
                handler.unloadClass(i1);
            }
            case 0x4 -> {
                // Stack frame
                l1 = rereadId(idSize, in, out);
                l2 = rereadId(idSize, in, out);
                l3 = rereadId(idSize, in, out);
                l4 = rereadId(idSize, in, out);
                i1 = rereadInt(in, out);
                i2 = rereadInt(in, out);
                handler.stackFrame(l1, l2, l3, l4, i1, i2);
            }
            case 0x5 -> {
                // Stack trace
                i1 = rereadInt(in, out);
                i2 = rereadInt(in, out);
                i3 = rereadInt(in, out);
                bytesLeft -= 12;
                lArr1 = new long[(int) bytesLeft / idSize];
                for (int i = 0; i < lArr1.length; i++) {
                    lArr1[i] = rereadId(idSize, in, out);
                }
                handler.stackTrace(i1, i2, i3, lArr1);
            }
            case 0x6 -> {
                // Alloc sites
                s1 = rereadShort(in, out);
                f1 = rereadFloat(in, out);
                i1 = rereadInt(in, out);
                i2 = rereadInt(in, out);
                l1 = rereadLong(in, out);
                l2 = rereadLong(in, out);
                i3 = rereadInt(in, out);    // num of sites that follow
                AllocSite[] allocSites = new AllocSite[i3];
                for (int i = 0; i < allocSites.length; i++) {
                    b1 = rereadByte(in, out);
                    i4 = rereadInt(in, out);
                    i5 = rereadInt(in, out);
                    i6 = rereadInt(in, out);
                    i7 = rereadInt(in, out);
                    i8 = rereadInt(in, out);
                    i9 = rereadInt(in, out);

                    allocSites[i] = new AllocSite(b1, i4, i5, i6, i7, i8, i9);
                }
                handler.allocSites(s1, f1, i1, i2, l1, l2, allocSites);
            }
            case 0x7 -> {
                // Heap summary
                i1 = rereadInt(in, out);
                i2 = rereadInt(in, out);
                l1 = rereadLong(in, out);
                l2 = rereadLong(in, out);
                handler.heapSummary(i1, i2, l1, l2);
            }
            case 0xa -> {
                // Start thread
                i1 = rereadInt(in, out);
                l1 = rereadId(idSize, in, out);
                i2 = rereadInt(in, out);
                l2 = rereadId(idSize, in, out);
                l3 = rereadId(idSize, in, out);
                l4 = rereadId(idSize, in, out);
                handler.startThread(i1, l1, i2, l2, l3, l4);
            }
            case 0xb -> {
                // End thread
                i1 = rereadInt(in, out);
                handler.endThread(i1);
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
                i1 = rereadInt(in, out);
                i2 = rereadInt(in, out);    // num samples that follow
                CPUSample[] samples = new CPUSample[i2];
                for (int i = 0; i < samples.length; i++) {
                    i3 = rereadInt(in, out);
                    i4 = rereadInt(in, out);
                    samples[i] = new CPUSample(i3, i4);
                }
                handler.cpuSamples(i1, samples);
            }
            case 0xe -> {
                // Control settings
                i1 = rereadInt(in, out);
                s1 = rereadShort(in, out);
                handler.controlSettings(i1, s1);
            }
            default -> throw new HprofParserException("Unexpected top-level record type: " + tag);
        }

        return false;
    }

    // returns number of bytes parsed
    private int parseHeapDump(DataInput in, DataOutput out, int idSize, boolean firstPass) throws IOException {

        byte tag = rereadByte(in, out);
        int bytesRead = 1;

        long l1, l2, l3, l4, l5, l6, l7;
        int i1, i2;
        short s1, s2, s3;
        byte b1;
        byte[] bArr1;
        long[] lArr1;

        switch (tag) {
            case -1 -> {    // 0xFF
                // Root unknown
                l1 = rereadId(idSize, in, out);
                handler.rootUnknown(l1);
                bytesRead += idSize;
            }
            case 0x01 -> {
                // Root JNI global
                l1 = rereadId(idSize, in, out);
                l2 = rereadId(idSize, in, out);
                handler.rootJNIGlobal(l1, l2);
                bytesRead += 2 * idSize;
            }
            case 0x02 -> {
                // Root JNI local
                l1 = rereadId(idSize, in, out);
                i1 = rereadInt(in, out);
                i2 = rereadInt(in, out);
                handler.rootJNILocal(l1, i1, i2);
                bytesRead += idSize + 8;
            }
            case 0x03 -> {
                // Root Java frame
                l1 = rereadId(idSize, in, out);
                i1 = rereadInt(in, out);
                i2 = rereadInt(in, out);
                handler.rootJavaFrame(l1, i1, i2);
                bytesRead += idSize + 8;
            }
            case 0x04 -> {
                // Root native stack
                l1 = rereadId(idSize, in, out);
                i1 = rereadInt(in, out);
                handler.rootNativeStack(l1, i1);
                bytesRead += idSize + 4;
            }
            case 0x05 -> {
                // Root sticky class
                l1 = rereadId(idSize, in, out);
                handler.rootStickyClass(l1);
                bytesRead += idSize;
            }
            case 0x06 -> {
                // Root thread block
                l1 = rereadId(idSize, in, out);
                i1 = rereadInt(in, out);
                handler.rootThreadBlock(l1, i1);
                bytesRead += idSize + 4;
            }
            case 0x07 -> {
                // Root monitor used
                l1 = rereadId(idSize, in, out);
                handler.rootMonitorUsed(l1);
                bytesRead += idSize;
            }
            case 0x08 -> {
                // Root thread object
                l1 = rereadId(idSize, in, out);
                i1 = rereadInt(in, out);
                i2 = rereadInt(in, out);
                handler.rootThreadObj(l1, i1, i2);
                bytesRead += idSize + 8;
            }
            case 0x20 -> {
                // Class dump
                l1 = rereadId(idSize, in, out);
                i1 = rereadInt(in, out);
                l2 = rereadId(idSize, in, out);
                l3 = rereadId(idSize, in, out);
                l4 = rereadId(idSize, in, out);
                l5 = rereadId(idSize, in, out);
                l6 = rereadId(idSize, in, out);
                l7 = rereadId(idSize, in, out);
                final boolean remove = !firstPass && l1 == toRemove;
                i2 = rereadInt(in, out);//TODO zero?
                bytesRead += idSize * 7 + 8;

                /* Constants */
                s1 = rereadShort(in, out);    // number of constants
                bytesRead += 2;
                Preconditions.checkState(s1 >= 0);
                Constant[] constants = new Constant[s1];
                for (int i = 0; i < s1; i++) {
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
                s2 = rereadShort(in, out);    // number of static fields
                bytesRead += 2;
                Preconditions.checkState(s2 >= 0);
                Static[] statics = new Static[s2];
                for (int i = 0; i < s2; i++) {
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
                s3 = in.readShort();     // number of instance fields
                if (remove) {
                    out.writeShort(0);
                } else {
                    out.writeShort(s3);
                }
                bytesRead += 2;
                Preconditions.checkState(s3 >= 0);
                InstanceField[] instanceFields = new InstanceField[s3];
                final int bytesBeforeFields = bytesRead;
                for (int i = 0; i < s3; i++) {
                    long fieldNameStringId = rereadId(idSize, in, remove ? DummyDataOutput.INSTANCE : out);
                    byte btype = in.readByte();
                    if (!remove) out.writeByte(btype);
                    bytesRead += idSize + 1;
                    Type type = Type.hprofTypeToEnum(btype);
                    instanceFields[i] = new InstanceField(fieldNameStringId, type);
                }
                if (firstPass && l1 == toRemove) {
                    newHeapdumpSize -= bytesRead - bytesBeforeFields;
                }

                /*
                 * We need to know the types of the values in an instance record when
                 * we parse that record.  To do that we need to look up the class and
                 * its superclasses.  So we need to store class records in a hash
                 * table.
                 */
                classMap.put(l1, new ClassInfo(l1, l2, i2, instanceFields));
                handler.classDump(l1, i1, l2, l3, l4, l5, l6, l7, i2, constants,
                        statics, instanceFields
                );
            }
            case 0x21 -> {
                // Instance dump
                l1 = rereadId(idSize, in, out);
                i1 = rereadInt(in, out);
                l2 = rereadId(idSize, in, out);    // class obj id
                i2 = in.readInt();     // num of bytes that follow
                final boolean remove2 = !firstPass && l2 == toRemove;
                if (!remove2) {
                    out.writeInt(i2);
                } else {
                    out.writeInt(0);
                }
                Preconditions.checkState(i2 >= 0);
                bArr1 = new byte[i2];
                in.readFully(bArr1);
                if (!remove2) out.write(bArr1);
                if (firstPass && l2 == toRemove) {
                    newHeapdumpSize -= i2;
                }

                /*
                 * because class dump records come *after* instance dump records,
                 * we don't know how to interpret the values yet.  we have to
                 * record the instances and process them at the end.
                 */
                processInstance(new Instance(l1, i1, l2, bArr1), idSize);
                bytesRead += idSize * 2 + 8 + i2;
            }
            case 0x22 -> {
                // Object array dump
                l1 = rereadId(idSize, in, out);
                i1 = rereadInt(in, out);
                i2 = rereadInt(in, out);    // number of elements
                l2 = rereadId(idSize, in, out);
                Preconditions.checkState(i2 >= 0);
                lArr1 = new long[i2];
                for (int i = 0; i < i2; i++) {
                    lArr1[i] = rereadId(idSize, in, out);
                }
                handler.objArrayDump(l1, i1, l2, lArr1);
                bytesRead += (2 + i2) * idSize + 8;
            }
            case 0x23 -> {
                // Primitive array dump
                l1 = rereadId(idSize, in, out);
                i1 = rereadInt(in, out);
                i2 = rereadInt(in, out);    // number of elements
                b1 = rereadByte(in, out);
                bytesRead += idSize + 9;
                Preconditions.checkState(i2 >= 0);
                Value<?>[] vs = new Value[i2];
                Type t = Type.hprofTypeToEnum(b1);
                int[] byteReadRef = {bytesRead};
                for (int i = 0; i < vs.length; i++) {
                    vs[i] = rereadValue(t, idSize, in, out, byteReadRef);
                }
                bytesRead = byteReadRef[0];
                handler.primArrayDump(l1, i1, b1, vs);
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

