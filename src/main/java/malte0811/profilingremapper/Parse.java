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
import net.minecraftforge.srgutils.IMappingFile;

import java.io.File;
import java.nio.file.Path;

public class Parse {
    private static final String MAPPINGS = "/media/data/gradle_cache/caches/fabric-loom/1.19.2/loom.mappings.1_19_2.layered+hash.2198-v2/mappings-base.tiny";

    public static void main(String[] args) throws Exception {
        var mappings = IMappingFile.load(Path.of(MAPPINGS).toFile());
        var parser = new HprofParser(mappings);
        parser.remap(
                new File("/media/data/MultiMC/instances/Quilt 1.19.2/.minecraft/world_creation_screen.hprof"),
                new File("/media/data/MultiMC/instances/Quilt 1.19.2/.minecraft/world_creation_screen_mapped.hprof")
        );
    }

}
