package malte0811.profilingremapper.nps;

import com.google.common.base.Preconditions;
import malte0811.profilingremapper.util.DataRewriter;
import net.minecraftforge.srgutils.IMappingFile;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

public record NPSParser(IMappingFile mappings) {
    private static final byte[] MAGIC_BYTES = "nBpRoFiLeR".getBytes(StandardCharsets.UTF_8);

    public void remap(File input, File output) throws IOException {
        var outputStream = new FileOutputStream(output);
        try (var uncompressed = new DataRewriter(new FileInputStream(input), outputStream)) {
            var magic = new byte[MAGIC_BYTES.length];
            uncompressed.rereadFully(magic);
            Preconditions.checkState(Arrays.equals(MAGIC_BYTES, magic));
            uncompressed.rereadShort(); // Version bytes
            var type = uncompressed.rereadInt();
            Preconditions.checkState(type == 1); // 1: CPU sampling
            final var oldCompressedSize = uncompressed.in().readInt();
            final var oldUncompressedSize = uncompressed.in().readInt();
            byte[] oldCompressed = new byte[oldCompressedSize];
            uncompressed.in().readFully(oldCompressed);
            var newUncompressed = new ByteArrayOutputStream();
            var inFromArray = new ByteArrayInputStream(oldCompressed);
            try (var compressed = new DataRewriter(new InflaterInputStream(inFromArray), newUncompressed)) {
                remapSnapshot(compressed);
            }
            var newCompressed = new ByteArrayOutputStream();
            try (var deflaterStream = new DeflaterOutputStream(newCompressed)) {
                deflaterStream.write(newUncompressed.toByteArray());
            }
            uncompressed.out().writeInt(newCompressed.size());
            uncompressed.out().writeInt(newUncompressed.size());
            uncompressed.out().write(newCompressed.toByteArray());
            final var settingsLength = uncompressed.rereadInt();
            uncompressed.rereadFully(new byte[settingsLength]);
            uncompressed.rereadUTF();// Comments
        }
    }

    private void remapSnapshot(DataRewriter reader) throws IOException {
        reader.rereadInt(); // version
        reader.rereadLong(); // begin time
        reader.rereadLong(); // time taken
        reader.rereadBoolean(); // "collectingTwoTimeStamps", whatever that means
        int numMethods = reader.rereadInt();
        for (int i = 0; i < numMethods; ++i) {
            var clazz = reader.in().readUTF();
            var methodName = reader.in().readUTF();
            final var methodDesc = reader.in().readUTF();
            var classMappings = mappings.getClass(clazz.replace('.', '/'));
            if (classMappings != null) {
                clazz = classMappings.getMapped();
                // Can't use a direct lookup since that would require VVM to actually produce sensible methodDesc's
                // instead of empty strings
                for (var method : classMappings.getMethods()) {
                    if (method.getOriginal().equals(methodName)) {
                        methodName = method.getMapped();
                        break;
                    }
                }
            }
            reader.out().writeUTF(clazz);
            reader.out().writeUTF(methodName);
            reader.out().writeUTF(methodDesc);
        }
        final var numThreads = reader.rereadInt();
        for (int i = 0; i < numThreads; ++i) {
            reader.rereadInt(); // thread ID
            reader.rereadUTF(); // thread name
            reader.rereadBoolean(); // "collectingTwoTimeStamps"
            final var length = reader.rereadInt();
            // Call trees and misc, too lazy for that
            reader.rereadFully(new byte[length + 77]);
        }
    }
}
