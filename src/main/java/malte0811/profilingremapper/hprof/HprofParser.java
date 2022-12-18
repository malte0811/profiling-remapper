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

package malte0811.profilingremapper.hprof;

import com.google.common.base.Preconditions;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import malte0811.profilingremapper.hprof.datastructures.ClassInfo;
import malte0811.profilingremapper.hprof.datastructures.InstanceField;
import malte0811.profilingremapper.hprof.datastructures.Static;
import malte0811.profilingremapper.hprof.datastructures.Type;
import malte0811.profilingremapper.util.DataRewriter;
import net.minecraftforge.srgutils.IMappingFile;

import javax.annotation.Nullable;
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

    public HprofParser(IMappingFile mappings) {
        this.mappings = mappings;
    }

    public void remap(File input, File output) throws IOException {
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

        boolean done;

        System.out.println("Starting first pass");
        try (var rewriter = new DataRewriter(new BufferedInputStream(new FileInputStream(input)), null)) {
            // header
            rewriter.rereadUntilNull();// format
            rewriter.setIdSize(rewriter.rereadInt());
            rewriter.rereadLong(); // start time

            // records
            do {
                done = parseRecord(rewriter, true);
            } while (!done);
        }
        System.out.println("First pass done");

        remap();
        System.out.println("Remapped string indices");

        try (var rewriter = new DataRewriter(input, output)) {
            rewriter.rereadUntilNull();
            rewriter.setIdSize(rewriter.rereadInt());
            rewriter.rereadLong();
            do {
                done = parseRecord(rewriter, false);
            } while (!done);
        }
        System.out.println("Second pass done");

        try (var rewriter = new DataRewriter(new BufferedInputStream(new FileInputStream(output)), null)) {
            rewriter.rereadUntilNull();
            rewriter.setIdSize(rewriter.rereadInt());
            rewriter.rereadLong();
            do {
                done = parseRecord(rewriter, true);
            } while (!done);
        }
        System.out.println("Parsed output");
    }

    /**
     * @return true if there are no more records to parse
     */
    @SuppressWarnings("unused")
    private boolean parseRecord(DataRewriter io, boolean isFirst) throws IOException {

        /* format:
         *   u1 - tag
         *   u4 - time
         *   u4 - length
         *   [u1]* - body
         */

        // if we get an EOF on this read, it just means we're done
        byte tag;
        try {
            tag = io.rereadByte();
        } catch (EOFException e) {
            return true;
        }

        // otherwise propagate the EOFException
        io.rereadInt(); // Time
        final int bytesLeftRaw = io.in().readInt();
        final long bytesTotal = Integer.toUnsignedLong(bytesLeftRaw);
        long bytesLeft = bytesTotal;
        if (tag == 0x1) {
            // String in UTF-8
            long stringId = io.readId();
            bytesLeft -= io.idSize();
            byte[] stringBytes = new byte[(int) bytesLeft];
            io.in().readFully(stringBytes);
            String stringToWrite;
            if (!isFirst) {
                stringToWrite = strings.get(stringId);
            } else {
                stringToWrite = new String(stringBytes);
                strings.put(stringId, stringToWrite);
            }
            var bytes = stringToWrite.getBytes(StandardCharsets.UTF_8);
            io.out().writeInt(bytes.length + io.idSize());
            io.writeId(stringId);

            io.out().write(bytes);
        } else {
            io.out().writeInt(bytesLeftRaw);
        }

        switch (tag) {
            case 0x1 -> {
            }
            case 0x2 -> {
                // Load class
                int serialNum = io.rereadInt();
                long classObjId = io.rereadId();
                int stackSerialNum = io.rereadInt();
                long nameStringId = io.rereadId();
                classIdToNameId.put(classObjId, nameStringId);
            }
            case 0x3 -> {
                // Unload class
                int serialNum = io.rereadInt();
            }
            case 0x4 -> {
                // Stack frame
                long stackframeId = io.rereadId();
                long methodNameId = io.rereadId();
                long sigId = io.rereadId();
                long sourceNameId = io.rereadId();
                int serialNum = io.rereadInt();
                int location = io.rereadInt();
            }
            case 0x5 -> {
                // Stack trace
                int stackSerialNum = io.rereadInt();
                int threadSerialNum = io.rereadInt();
                int numFrames = io.rereadInt();
                bytesLeft -= 12;
                var numFrames2 = (int) (bytesLeft / io.idSize());//TODO == numFrames?
                for (int i = 0; i < numFrames2; i++) {
                    long frameId = io.rereadId();
                }
            }
            case 0x6 -> {
                // Alloc sites
                short flagMask = io.rereadShort();
                float cutoffRatio = io.rereadFloat();
                int totalLiveBytes = io.rereadInt();
                int totLiveInstances = io.rereadInt();
                long totalBytesAlloc = io.rereadLong();
                long totalInstancesAlloc = io.rereadLong();
                var numAllocs = io.rereadInt();
                for (int i = 0; i < numAllocs; i++) {
                    byte arrayIndicator = io.rereadByte();
                    int classSerial = io.rereadInt();
                    int stackSerial = io.rereadInt();
                    int numLiveBytes = io.rereadInt();
                    int numLiveInstances = io.rereadInt();
                    int bytesAlloced = io.rereadInt();
                    int instancesAlloced = io.rereadInt();
                }
            }
            case 0x7 -> {
                // Heap summary
                int totalLiveBytes = io.rereadInt();
                int totLiveInstances = io.rereadInt();
                long totalBytesAlloc = io.rereadLong();
                long totalInstancesAlloc = io.rereadLong();
            }
            case 0xa -> {
                // Start thread
                int threadSerialNum = io.rereadInt();
                long threadObjectId = io.rereadId();
                int stackSerialNum = io.rereadInt();
                long threadNameId = io.rereadId();
                long groupNameId = io.rereadId();
                long parentGroupNameId = io.rereadId();
            }
            case 0xb -> {
                // End thread
                int threadSerialNum = io.rereadInt();
            }
            case 0xc ->
                    // Heap dump
                    throw new RuntimeException();
            case 0x1c -> {
                // Heap dump segment
                while (bytesLeft > 0) {
                    bytesLeft -= parseHeapDump(io, isFirst);
                }
            }
            case 0x2c -> {
            }
            case 0xd -> {
                // CPU samples
                int totalNumSamples = io.rereadInt();
                int numTraces = io.rereadInt();
                for (int i = 0; i < numTraces; i++) {
                    int numSamples = io.rereadInt();
                    int stackSerial = io.rereadInt();
                }
            }
            case 0xe -> {
                // Control settings
                int bitmaskFlags = io.rereadInt();
                short stacktraceDepth = io.rereadShort();
            }
            default -> throw new HprofParserException("Unexpected top-level record type: " + tag);
        }

        return false;
    }

    // returns number of bytes parsed
    @SuppressWarnings("unused")
    private int parseHeapDump(DataRewriter io, boolean firstPass) throws IOException {

        byte tag = io.rereadByte();
        int bytesRead = 1;

        switch (tag) {
            case -1 -> {    // 0xFF
                // Root unknown
                long objId = io.rereadId();
                bytesRead += io.idSize();
            }
            case 0x01 -> {
                // Root JNI global
                long objId = io.rereadId();
                long jniGlobalRef = io.rereadId();
                bytesRead += 2 * io.idSize();
            }
            case 0x02 -> {
                // Root JNI local
                long objId = io.rereadId();
                int threadSerial = io.rereadInt();
                int frameNum = io.rereadInt();
                bytesRead += io.idSize() + 8;
            }
            case 0x03 -> {
                // Root Java frame
                long objId = io.rereadId();
                int threadSerial = io.rereadInt();
                int frameNum = io.rereadInt();
                bytesRead += io.idSize() + 8;
            }
            case 0x04 -> {
                // Root native stack
                long objId = io.rereadId();
                int threadSerial = io.rereadInt();
                bytesRead += io.idSize() + 4;
            }
            case 0x05 -> {
                // Root sticky class
                long objId = io.rereadId();
                bytesRead += io.idSize();
            }
            case 0x06 -> {
                // Root thread block
                long objId = io.rereadId();
                int threadSerial = io.rereadInt();
                bytesRead += io.idSize() + 4;
            }
            case 0x07 -> {
                // Root monitor used
                long objId = io.rereadId();
                bytesRead += io.idSize();
            }
            case 0x08 -> {
                // Root thread object
                long objId = io.rereadId();
                int threadSerial = io.rereadInt();
                int stackSerial = io.rereadInt();
                bytesRead += io.idSize() + 8;
            }
            case 0x20 -> {
                // Class dump
                long classObjId = io.rereadId();
                int stackSerial = io.rereadInt();
                long superclassObjId = io.rereadId();
                long loaderObjId = io.rereadId();
                long signerObjId = io.rereadId();
                long protectDomainId = io.rereadId();
                long reserved1 = io.rereadId();
                long reserved2 = io.rereadId();
                int instanceSize = io.rereadInt();
                bytesRead += io.idSize() * 7 + 8;

                /* Constants */
                short numConstants = io.rereadShort();
                bytesRead += 2;
                Preconditions.checkState(numConstants >= 0);
                for (int i = 0; i < numConstants; i++) {
                    short constantPoolIndex = io.rereadShort();
                    byte btype = io.rereadByte();
                    bytesRead += 3;
                    Type type = Type.hprofTypeToEnum(btype);
                    int[] bytesReadRef = {bytesRead};
                    io.rereadValue(type, bytesReadRef);
                    bytesRead = bytesReadRef[0];
                }

                /* Statics */
                short numStatics = io.rereadShort();
                bytesRead += 2;
                Preconditions.checkState(numStatics >= 0);
                Static[] statics = new Static[numStatics];
                for (int i = 0; i < numStatics; i++) {
                    long staticFieldNameStringId = io.rereadId();
                    byte btype = io.rereadByte();
                    bytesRead += io.idSize() + 1;
                    Type type = Type.hprofTypeToEnum(btype);
                    int[] bytesReadRef = {bytesRead};
                    io.rereadValue(type, bytesReadRef);
                    bytesRead = bytesReadRef[0];

                    statics[i] = new Static(staticFieldNameStringId);
                }

                /* Instance fields */
                short numFields = io.rereadShort();
                bytesRead += 2;
                Preconditions.checkState(numFields >= 0);
                InstanceField[] instanceFields = new InstanceField[numFields];
                for (int i = 0; i < numFields; i++) {
                    long fieldNameStringId = io.rereadId();
                    byte btype = io.rereadByte();
                    bytesRead += io.idSize() + 1;
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
                long objId = io.rereadId();
                int stackSerial = io.rereadInt();
                long classObjId = io.rereadId();
                int bytesFollowing = io.rereadInt();
                Preconditions.checkState(bytesFollowing >= 0);
                byte[] packedData = new byte[bytesFollowing];
                io.rereadFully(packedData);

                bytesRead += io.idSize() * 2 + 8 + bytesFollowing;
            }
            case 0x22 -> {
                // Object array dump
                long objId = io.rereadId();
                int stackSerial = io.rereadInt();
                int arraySize = io.rereadInt();
                long elementClassObjId = io.rereadId();
                Preconditions.checkState(arraySize >= 0);
                for (int i = 0; i < arraySize; i++) {
                    long elementId = io.rereadId();
                }
                bytesRead += (2 + arraySize) * io.idSize() + 8;
            }
            case 0x23 -> {
                // Primitive array dump
                long objId = io.rereadId();
                int stackSerial = io.rereadInt();
                int arraySize = io.rereadInt();
                byte elementType = io.rereadByte();
                bytesRead += io.idSize() + 9;
                Preconditions.checkState(arraySize >= 0);
                Type t = Type.hprofTypeToEnum(elementType);
                int[] byteReadRef = {bytesRead};
                for (int i = 0; i < arraySize; i++) {
                    io.rereadValue(t, byteReadRef);
                }
                bytesRead = byteReadRef[0];
            }
            default -> throw new HprofParserException("Unexpected heap dump sub-record type: " + tag);
        }

        return bytesRead;

    }

    private void remap() {
        for (var clazz : classMap.values()) {
            final var classNameId = classIdToNameId.get(clazz.classObjId());
            final var className = strings.get(classNameId);
            if (className == null) {
                System.out.println("Failed to find class name for ID " + classNameId);
                continue;
            }
            final var classMappings = mappings.getClass(className);
            strings.put(classNameId, remapClassName(className, classMappings));
            if (classMappings == null) {
                continue;
            }
            remapFields(clazz.instanceFields(), InstanceField::fieldNameId, classMappings, className);
            remapFields(clazz.statics(), Static::fieldNameId, classMappings, className);
        }
    }

    private String remapClassName(String name) {
        return remapClassName(name, mappings.getClass(name));
    }

    private String remapClassName(String className, @Nullable IMappingFile.IClass classObject) {
        if (classObject != null) {
            return classObject.getMapped();
        } else if (className.startsWith("[") && className.endsWith(";")) {
            final var prefixLength = className.indexOf('L') + 1;
            final var elementName = className.substring(prefixLength, className.length() - 1);
            final var elementMapped = remapClassName(elementName);
            return className.substring(0, prefixLength) + elementMapped + ";";
        }
        var namePrefix = className;
        int lastDollar;
        while ((lastDollar = namePrefix.lastIndexOf('$')) >= 0) {
            namePrefix = namePrefix.substring(0, lastDollar);
            final var prefixRemapped = mappings.remapClass(namePrefix);
            if (prefixRemapped != null) {
                return prefixRemapped + className.substring(namePrefix.length());
            }
        }
        return className;
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

