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
import edu.tufts.eaftan.hprofparser.parser.datastructures.AllocSite;
import edu.tufts.eaftan.hprofparser.parser.datastructures.CPUSample;
import edu.tufts.eaftan.hprofparser.parser.datastructures.ClassInfo;
import edu.tufts.eaftan.hprofparser.parser.datastructures.Constant;
import edu.tufts.eaftan.hprofparser.parser.datastructures.Instance;
import edu.tufts.eaftan.hprofparser.parser.datastructures.InstanceField;
import edu.tufts.eaftan.hprofparser.parser.datastructures.Static;
import edu.tufts.eaftan.hprofparser.parser.datastructures.Type;
import edu.tufts.eaftan.hprofparser.parser.datastructures.Value;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Queue;

/**
 * Parses an hprof heap dump file in binary format.  The hprof dump file format is documented in
 * the hprof_b_spec.h file in the hprof source, which is open-source and available from Oracle.
 */
public class HprofParser {

    private RecordHandler handler;
    private HashMap<Long, ClassInfo> classMap;
    private HashMap<Long, String> strings;
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
                for (int i = 0; i < bytes.length; i++) {
                    newBytes[i] = bytes[i];
                }
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
            tag = in.readByte();
            out.writeByte(tag);
        } catch (EOFException e) {
            return true;
        }

        // otherwise propagate the EOFException
        int time = in.readInt();
        out.writeInt(time);
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
            case 0x1:
                // String in UTF-8
                l1 = readId(idSize, in, out);
                bytesLeft -= idSize;
                bArr1 = new byte[(int) bytesLeft];
                in.readFully(bArr1);
                out.write(bArr1);
                handler.stringInUTF8(l1, new String(bArr1));
                strings.put(l1, new String(bArr1));
                break;

            case 0x2:
                // Load class
                i1 = in.readInt();
                out.writeInt(i1);
                l1 = readId(idSize, in, out);
                i2 = in.readInt();
                out.writeInt(i2);
                l2 = readId(idSize, in, out);
                handler.loadClass(i1, l1, i2, l2);
                if (toRemove < 0 && "java/util/HashMap$Node".equals(strings.get(l2))) {
                    toRemove = l1;
                    System.out.println("Found target class: " + toRemove);
                }
                break;

            case 0x3:
                // Unload class
                i1 = in.readInt();
                out.writeInt(i1);
                handler.unloadClass(i1);
                break;

            case 0x4:
                // Stack frame
                l1 = readId(idSize, in, out);
                l2 = readId(idSize, in, out);
                l3 = readId(idSize, in, out);
                l4 = readId(idSize, in, out);
                i1 = in.readInt();
                out.writeInt(i1);
                i2 = in.readInt();
                out.writeInt(i2);
                handler.stackFrame(l1, l2, l3, l4, i1, i2);
                break;

            case 0x5:
                // Stack trace
                i1 = in.readInt();
                out.writeInt(i1);
                i2 = in.readInt();
                out.writeInt(i2);
                i3 = in.readInt();
                out.writeInt(i3);
                bytesLeft -= 12;
                lArr1 = new long[(int) bytesLeft / idSize];
                for (int i = 0; i < lArr1.length; i++) {
                    lArr1[i] = readId(idSize, in, out);
                }
                handler.stackTrace(i1, i2, i3, lArr1);
                break;

            case 0x6:
                // Alloc sites
                s1 = in.readShort();
                out.writeShort(s1);
                f1 = in.readFloat();
                out.writeFloat(f1);
                i1 = in.readInt();
                out.writeInt(i1);
                i2 = in.readInt();
                out.writeInt(i2);
                l1 = in.readLong();
                out.writeLong(l1);
                l2 = in.readLong();
                out.writeLong(l2);
                i3 = in.readInt();
                out.writeInt(i3);    // num of sites that follow

                AllocSite[] allocSites = new AllocSite[i3];
                for (int i = 0; i < allocSites.length; i++) {
                    b1 = in.readByte();
                    out.writeByte(b1);
                    i4 = in.readInt();
                    out.writeInt(i4);
                    i5 = in.readInt();
                    out.writeInt(i5);
                    i6 = in.readInt();
                    out.writeInt(i6);
                    i7 = in.readInt();
                    out.writeInt(i7);
                    i8 = in.readInt();
                    out.writeInt(i8);
                    i9 = in.readInt();
                    out.writeInt(i9);

                    allocSites[i] = new AllocSite(b1, i4, i5, i6, i7, i8, i9);
                }
                handler.allocSites(s1, f1, i1, i2, l1, l2, allocSites);
                break;

            case 0x7:
                // Heap summary
                i1 = in.readInt();
                out.writeInt(i1);
                i2 = in.readInt();
                out.writeInt(i2);
                l1 = in.readLong();
                out.writeLong(l1);
                l2 = in.readLong();
                out.writeLong(l2);
                handler.heapSummary(i1, i2, l1, l2);
                break;

            case 0xa:
                // Start thread
                i1 = in.readInt();
                out.writeInt(i1);
                l1 = readId(idSize, in, out);
                i2 = in.readInt();
                out.writeInt(i2);
                l2 = readId(idSize, in, out);
                l3 = readId(idSize, in, out);
                l4 = readId(idSize, in, out);
                handler.startThread(i1, l1, i2, l2, l3, l4);
                break;

            case 0xb:
                // End thread
                i1 = in.readInt();
                out.writeInt(i1);
                handler.endThread(i1);
                break;

            case 0xc:
                // Heap dump
                throw new RuntimeException();

            case 0x1c:
                // Heap dump segment
                handler.heapDumpSegment();
                if (isFirst) {
                    newHeapdumpSize = bytesTotal;
                }
                while (bytesLeft > 0) {
                    bytesLeft -= parseHeapDump(in, out, idSize, isFirst);
                }
                System.out.println("Bytes left: " + bytesLeft);
                break;

            case 0x2c:
                // Heap dump end (of segments)
                handler.heapDumpEnd();
                break;

            case 0xd:
                // CPU samples
                i1 = in.readInt();
                out.writeInt(i1);
                i2 = in.readInt();
                out.writeInt(i2);    // num samples that follow

                CPUSample[] samples = new CPUSample[i2];
                for (int i = 0; i < samples.length; i++) {
                    i3 = in.readInt();
                    out.writeInt(i3);
                    i4 = in.readInt();
                    out.writeInt(i4);
                    samples[i] = new CPUSample(i3, i4);
                }
                handler.cpuSamples(i1, samples);
                break;

            case 0xe:
                // Control settings
                i1 = in.readInt();
                out.writeInt(i1);
                s1 = in.readShort();
                out.writeShort(s1);
                handler.controlSettings(i1, s1);
                break;

            default:
                throw new HprofParserException("Unexpected top-level record type: " + tag);
        }

        return false;
    }

    // returns number of bytes parsed
    private int parseHeapDump(DataInput in, DataOutput out, int idSize, boolean firstPass) throws IOException {

        byte tag = in.readByte();
        out.writeByte(tag);
        int bytesRead = 1;

        long l1, l2, l3, l4, l5, l6, l7;
        int i1, i2;
        short s1, s2, s3;
        byte b1;
        byte[] bArr1;
        long[] lArr1;

        switch (tag) {

            case -1:    // 0xFF
                // Root unknown
                l1 = readId(idSize, in, out);
                handler.rootUnknown(l1);
                bytesRead += idSize;
                break;

            case 0x01:
                // Root JNI global
                l1 = readId(idSize, in, out);
                l2 = readId(idSize, in, out);
                handler.rootJNIGlobal(l1, l2);
                bytesRead += 2 * idSize;
                break;

            case 0x02:
                // Root JNI local
                l1 = readId(idSize, in, out);
                i1 = in.readInt();
                out.writeInt(i1);
                i2 = in.readInt();
                out.writeInt(i2);
                handler.rootJNILocal(l1, i1, i2);
                bytesRead += idSize + 8;
                break;

            case 0x03:
                // Root Java frame
                l1 = readId(idSize, in, out);
                i1 = in.readInt();
                out.writeInt(i1);
                i2 = in.readInt();
                out.writeInt(i2);
                handler.rootJavaFrame(l1, i1, i2);
                bytesRead += idSize + 8;
                break;

            case 0x04:
                // Root native stack
                l1 = readId(idSize, in, out);
                i1 = in.readInt();
                out.writeInt(i1);
                handler.rootNativeStack(l1, i1);
                bytesRead += idSize + 4;
                break;

            case 0x05:
                // Root sticky class
                l1 = readId(idSize, in, out);
                handler.rootStickyClass(l1);
                bytesRead += idSize;
                break;

            case 0x06:
                // Root thread block
                l1 = readId(idSize, in, out);
                i1 = in.readInt();
                out.writeInt(i1);
                handler.rootThreadBlock(l1, i1);
                bytesRead += idSize + 4;
                break;

            case 0x07:
                // Root monitor used
                l1 = readId(idSize, in, out);
                handler.rootMonitorUsed(l1);
                bytesRead += idSize;
                break;

            case 0x08:
                // Root thread object
                l1 = readId(idSize, in, out);
                i1 = in.readInt();
                out.writeInt(i1);
                i2 = in.readInt();
                out.writeInt(i2);
                handler.rootThreadObj(l1, i1, i2);
                bytesRead += idSize + 8;
                break;

            case 0x20:
                // Class dump
                l1 = readId(idSize, in, out);
                i1 = in.readInt();
                out.writeInt(i1);
                l2 = readId(idSize, in, out);
                l3 = readId(idSize, in, out);
                l4 = readId(idSize, in, out);
                l5 = readId(idSize, in, out);
                l6 = readId(idSize, in, out);
                l7 = readId(idSize, in, out);
                final boolean remove = !firstPass && l1 == toRemove;
                i2 = in.readInt();
                out.writeInt(i2);//TODO zero?
                bytesRead += idSize * 7 + 8;

                /* Constants */
                s1 = in.readShort();
                out.writeShort(s1);    // number of constants
                bytesRead += 2;
                Preconditions.checkState(s1 >= 0);
                Constant[] constants = new Constant[s1];
                for (int i = 0; i < s1; i++) {
                    short constantPoolIndex = in.readShort();
                    out.writeShort(constantPoolIndex);
                    byte btype = in.readByte();
                    out.writeByte(btype);
                    bytesRead += 3;
                    Type type = Type.hprofTypeToEnum(btype);
                    Value<?> v = null;

                    switch (type) {
                        case OBJ:
                            long vid = readId(idSize, in, out);
                            bytesRead += idSize;
                            v = new Value<>(type, vid);
                            break;
                        case BOOL:
                            boolean vbool = in.readBoolean();
                            out.writeBoolean(vbool);
                            bytesRead += 1;
                            v = new Value<>(type, vbool);
                            break;
                        case CHAR:
                            char vc = in.readChar();
                            out.writeChar(vc);
                            bytesRead += 2;
                            v = new Value<>(type, vc);
                            break;
                        case FLOAT:
                            float vf = in.readFloat();
                            out.writeFloat(vf);
                            bytesRead += 4;
                            v = new Value<>(type, vf);
                            break;
                        case DOUBLE:
                            double vd = in.readDouble();
                            out.writeDouble(vd);
                            bytesRead += 8;
                            v = new Value<>(type, vd);
                            break;
                        case BYTE:
                            byte vbyte = in.readByte();
                            out.writeByte(vbyte);
                            bytesRead += 1;
                            v = new Value<>(type, vbyte);
                            break;
                        case SHORT:
                            short vs = in.readShort();
                            out.writeShort(vs);
                            bytesRead += 2;
                            v = new Value<>(type, vs);
                            break;
                        case INT:
                            int vi = in.readInt();
                            out.writeInt(vi);
                            bytesRead += 4;
                            v = new Value<>(type, vi);
                            break;
                        case LONG:
                            long vl = in.readLong();
                            out.writeLong(vl);
                            bytesRead += 8;
                            v = new Value<>(type, vl);
                            break;
                    }

                    constants[i] = new Constant(constantPoolIndex, v);
                }

                /* Statics */
                s2 = in.readShort();
                out.writeShort(s2);    // number of static fields
                bytesRead += 2;
                Preconditions.checkState(s2 >= 0);
                Static[] statics = new Static[s2];
                for (int i = 0; i < s2; i++) {
                    long staticFieldNameStringId = readId(idSize, in, out);
                    byte btype = in.readByte();
                    out.writeByte(btype);
                    bytesRead += idSize + 1;
                    Type type = Type.hprofTypeToEnum(btype);
                    Value<?> v = null;

                    switch (type) {
                        case OBJ:     // object
                            long vid = readId(idSize, in, out);
                            bytesRead += idSize;
                            v = new Value<>(type, vid);
                            break;
                        case BOOL:     // boolean
                            boolean vbool = in.readBoolean();
                            out.writeBoolean(vbool);
                            bytesRead += 1;
                            v = new Value<>(type, vbool);
                            break;
                        case CHAR:     // char
                            char vc = in.readChar();
                            out.writeChar(vc);
                            bytesRead += 2;
                            v = new Value<>(type, vc);
                            break;
                        case FLOAT:     // float
                            float vf = in.readFloat();
                            out.writeFloat(vf);
                            bytesRead += 4;
                            v = new Value<>(type, vf);
                            break;
                        case DOUBLE:     // double
                            double vd = in.readDouble();
                            out.writeDouble(vd);
                            bytesRead += 8;
                            v = new Value<>(type, vd);
                            break;
                        case BYTE:     // byte
                            byte vbyte = in.readByte();
                            out.writeByte(vbyte);
                            bytesRead += 1;
                            v = new Value<>(type, vbyte);
                            break;
                        case SHORT:     // short
                            short vs = in.readShort();
                            out.writeShort(vs);
                            bytesRead += 2;
                            v = new Value<>(type, vs);
                            break;
                        case INT:    // int
                            int vi = in.readInt();
                            out.writeInt(vi);
                            bytesRead += 4;
                            v = new Value<>(type, vi);
                            break;
                        case LONG:    // long
                            long vl = in.readLong();
                            out.writeLong(vl);
                            bytesRead += 8;
                            v = new Value<>(type, vl);
                            break;
                    }

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
                    long fieldNameStringId = readId(idSize, in, remove ? DummyDataOutput.INSTANCE : out);
                    byte btype = in.readByte();
                    if (!remove) out.writeByte(btype);
                    bytesRead += idSize + 1;
                    Type type = Type.hprofTypeToEnum(btype);
                    instanceFields[i] = new InstanceField(fieldNameStringId, type);
                }
                if (firstPass && l1 == toRemove) {
                    newHeapdumpSize -= bytesRead - bytesBeforeFields;
                }

                /**
                 * We need to know the types of the values in an instance record when
                 * we parse that record.  To do that we need to look up the class and
                 * its superclasses.  So we need to store class records in a hash
                 * table.
                 */
                classMap.put(l1, new ClassInfo(l1, l2, i2, instanceFields));
                handler.classDump(l1, i1, l2, l3, l4, l5, l6, l7, i2, constants,
                        statics, instanceFields
                );
                break;

            case 0x21:
                // Instance dump
                l1 = readId(idSize, in, out);
                i1 = in.readInt();
                out.writeInt(i1);
                l2 = readId(idSize, in, out);    // class obj id
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

                /**
                 * because class dump records come *after* instance dump records,
                 * we don't know how to interpret the values yet.  we have to
                 * record the instances and process them at the end.
                 */
                processInstance(new Instance(l1, i1, l2, bArr1), idSize);

                bytesRead += idSize * 2 + 8 + i2;
                break;

            case 0x22:
                // Object array dump
                l1 = readId(idSize, in, out);
                i1 = in.readInt();
                out.writeInt(i1);
                i2 = in.readInt();
                out.writeInt(i2);    // number of elements
                l2 = readId(idSize, in, out);

                Preconditions.checkState(i2 >= 0);
                lArr1 = new long[i2];
                for (int i = 0; i < i2; i++) {
                    lArr1[i] = readId(idSize, in, out);
                }
                handler.objArrayDump(l1, i1, l2, lArr1);
                bytesRead += (2 + i2) * idSize + 8;
                break;

            case 0x23:
                // Primitive array dump
                l1 = readId(idSize, in, out);
                i1 = in.readInt();
                out.writeInt(i1);
                i2 = in.readInt();
                out.writeInt(i2);    // number of elements
                b1 = in.readByte();
                out.writeByte(b1);
                bytesRead += idSize + 9;

                Preconditions.checkState(i2 >= 0);
                Value<?>[] vs = new Value[i2];
                Type t = Type.hprofTypeToEnum(b1);
                for (int i = 0; i < vs.length; i++) {
                    switch (t) {
                        case OBJ:
                            long vobj = readId(idSize, in, out);
                            vs[i] = new Value<>(t, vobj);
                            bytesRead += idSize;
                            break;
                        case BOOL:
                            boolean vbool = in.readBoolean();
                            out.writeBoolean(vbool);
                            vs[i] = new Value<>(t, vbool);
                            bytesRead += 1;
                            break;
                        case CHAR:
                            char vc = in.readChar();
                            out.writeChar(vc);
                            vs[i] = new Value<>(t, vc);
                            bytesRead += 2;
                            break;
                        case FLOAT:
                            float vf = in.readFloat();
                            out.writeFloat(vf);
                            vs[i] = new Value<>(t, vf);
                            bytesRead += 4;
                            break;
                        case DOUBLE:
                            double vd = in.readDouble();
                            out.writeDouble(vd);
                            vs[i] = new Value<>(t, vd);
                            bytesRead += 8;
                            break;
                        case BYTE:
                            byte vbyte = in.readByte();
                            out.writeByte(vbyte);
                            vs[i] = new Value<>(t, vbyte);
                            bytesRead += 1;
                            break;
                        case SHORT:
                            short vshort = in.readShort();
                            out.writeShort(vshort);
                            vs[i] = new Value<>(t, vshort);
                            bytesRead += 2;
                            break;
                        case INT:
                            int vi = in.readInt();
                            out.writeInt(vi);
                            vs[i] = new Value<>(t, vi);
                            bytesRead += 4;
                            break;
                        case LONG:
                            long vlong = in.readLong();
                            out.writeLong(vlong);
                            vs[i] = new Value<>(t, vlong);
                            bytesRead += 8;
                            break;
                    }
                }
                handler.primArrayDump(l1, i1, b1, vs);
                break;

            default:
                throw new HprofParserException("Unexpected heap dump sub-record type: " + tag);
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
                Value<?> v = null;
                switch (field.type) {
                    case OBJ:     // object
                        long vid = readId(idSize, input, DummyDataOutput.INSTANCE);
                        v = new Value<>(field.type, vid);
                        break;
                    case BOOL:     // boolean
                        boolean vbool = input.readBoolean();
                        v = new Value<>(field.type, vbool);
                        break;
                    case CHAR:     // char
                        char vc = input.readChar();
                        v = new Value<>(field.type, vc);
                        break;
                    case FLOAT:     // float
                        float vf = input.readFloat();
                        v = new Value<>(field.type, vf);
                        break;
                    case DOUBLE:     // double
                        double vd = input.readDouble();
                        v = new Value<>(field.type, vd);
                        break;
                    case BYTE:     // byte
                        byte vbyte = input.readByte();
                        v = new Value<>(field.type, vbyte);
                        break;
                    case SHORT:     // short
                        short vs = input.readShort();
                        v = new Value<>(field.type, vs);
                        break;
                    case INT:    // int
                        int vi = input.readInt();
                        v = new Value<>(field.type, vi);
                        break;
                    case LONG:    // long
                        long vl = input.readLong();
                        v = new Value<>(field.type, vl);
                        break;
                }
                values.add(v);
            }
        }
        Value<?>[] valuesArr = new Value[values.size()];
        valuesArr = values.toArray(valuesArr);
        handler.instanceDump(i.objId, i.stackTraceSerialNum, i.classObjId, valuesArr);
    }

    private static long readId(int idSize, DataInput in, DataOutput out) throws IOException {
        long id = -1;
        if (idSize == 4) {
            id = in.readInt();
            out.writeInt((int) id);
            id &= 0x00000000ffffffff;     // undo sign extension
        } else if (idSize == 8) {
            id = in.readLong();
            out.writeLong(id);
        } else {
            throw new IllegalArgumentException("Invalid identifier size " + idSize);
        }

        return id;
    }


    /* Utility */

    private int mySkipBytes(int n, DataInput in) throws IOException {
        int bytesRead = 0;

        try {
            while (bytesRead < n) {
                in.readByte();
                bytesRead++;
            }
        } catch (EOFException e) {
            // expected
        }

        return bytesRead;
    }
}

