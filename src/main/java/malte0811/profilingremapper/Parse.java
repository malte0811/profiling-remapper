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

package malte0811.profilingremapper;

import malte0811.profilingremapper.hprof.HprofParser;
import malte0811.profilingremapper.nps.NPSParser;
import net.minecraftforge.srgutils.IMappingFile;

import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Locale;

public class Parse {
    private static final String INCORRECT_ARGS = """
            ProfilingRemapper takes two or three arguments:
            java -jar profiling-remapper.jar <mapping file> <input file>
            java -jar profiling-remapper.jar <mapping file> <input file> <output file>
                        
            <mapping file>: Mappings file in a format that can be parsed by Forge's SRGUtils. This includes SRG/TSRG and
            tiny/tinyv2. Example paths:
            Forge: ~/.gradle/caches/forge_gradle/minecraft_user_repo/de/oceanlabs/mcp/mcp_config/1.19.3-20221207.122022/srg_to_official_1.19.3.tsrg
            Fabric/Quilt: ~/.gradle/caches/fabric-loom/1.19.3/loom.mappings.1_19_3.layered+hash.2198-v2/mappings-base.tiny
                        
            <input file>: HPROF or NPS file to be remapped
                        
            <input file>: File to write the mapped result to. If omitted, the result will be written in the same
            directory as <input file> and named input.mapped.extension if the input file was input.extension.""";

    public static void main(String[] args) {
        final var spec = parseArgs(args);
        if (spec == null) { return; }
        final var task = spec.prepare();
        if (task == null) { return; }
        try {
            task.run();
        } catch (IOException e) {
            e.printStackTrace();
            System.err.println("Failed to run remapping task");
        }
    }

    @Nullable
    private static RemappingTaskSpec parseArgs(String[] args) {
        if (args.length < 2 || args.length > 3) {
            System.err.println(INCORRECT_ARGS);
            return null;
        }
        final var mappingFile = args[0];
        final var inputFile = args[1];
        final var inputType = FileType.fromName(inputFile);
        if (inputType == null) {
            System.err.println("Failed to recognize input file type");
            return null;
        }
        final String outputFile;
        if (args.length == 3) {
            outputFile = args[2];
        } else {
            outputFile = makeOutputFile(inputFile);
            System.out.println("Setting output file to " + outputFile);
        }
        final var outputType = FileType.fromName(inputFile);
        if (outputType != inputType) {
            System.err.println("Output file type does not match input file type");
            return null;
        }
        return new RemappingTaskSpec(mappingFile, inputFile, outputFile, inputType);
    }

    private static String makeOutputFile(String input) {
        final var lastDot = input.lastIndexOf('.');
        return input.substring(0, lastDot) + ".mapped" + input.substring(lastDot);
    }

    private record RemappingTaskSpec(
            String mappingFile,
            String inputFile,
            String outputFile,
            FileType type
    ) {
        @Nullable
        public RemappingTask prepare() {
            final var mappings = verifyMappingFile();
            if (mappings == null) { return null; }
            final var inputFile = verifyInputFile();
            if (inputFile == null) { return null; }
            final var outputFile = verifyOutputFile();
            if (outputFile == null) { return null; }
            return new RemappingTask(mappings, inputFile, outputFile, type);
        }

        private IMappingFile verifyMappingFile() {
            final var mappingFile = new File(this.mappingFile);
            if (!Files.exists(mappingFile.toPath())) {
                System.err.println("Specified mapping file does not exist");
                return null;
            }
            try {
                final var mappings = IMappingFile.load(mappingFile);
                if (mappings.getClass("a") != null) {
                    System.out.println("Warning: The specified mapping file maps from OBF. You probably want to use a" +
                            " file mapping from SRG or intermediary instead.");
                }
                return mappings;
            } catch (IOException e) {
                e.printStackTrace();
                System.err.println("Failed to parse mapping file");
                return null;
            }
        }

        private File verifyInputFile() {
            final var inputFile = new File(this.inputFile);
            if (!Files.exists(inputFile.toPath())) {
                System.out.println("Specified input file does not exist");
                return null;
            }
            return inputFile;
        }

        private File verifyOutputFile() {
            final var outputFile = new File(this.outputFile);
            if (Files.exists(outputFile.toPath())) {
                System.err.println("Specified output file already exists. Overwrite (y/n)?");
                try {
                    int choice = System.in.read();
                    if (choice != 'y') {
                        System.err.println("Aborting");
                        return null;
                    }
                    System.err.println("Overwriting");
                } catch (IOException e) {
                    e.printStackTrace();
                    System.err.println("Failed to read overwrite choice");
                }
            }
            return outputFile;
        }
    }

    private record RemappingTask(
            IMappingFile mappingFile,
            File inputFile,
            File outputFile,
            FileType type
    ) {
        public void run() throws IOException {
            switch (type) {
                case HPROF -> {
                    final var remapper = new HprofParser(mappingFile);
                    remapper.remap(inputFile, outputFile);
                }
                case NPS -> {
                    final var remapper = new NPSParser(mappingFile);
                    remapper.remap(inputFile, outputFile);
                }
            }
        }
    }

    private enum FileType {
        HPROF, NPS;

        @Nullable
        static FileType fromName(String fileName) {
            fileName = fileName.toLowerCase(Locale.ROOT);
            if (fileName.endsWith(".hprof")) {
                return HPROF;
            } else if (fileName.endsWith(".nps")) {
                return NPS;
            } else {
                return null;
            }
        }
    }
}
