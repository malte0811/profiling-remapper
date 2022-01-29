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

package edu.tufts.eaftan.hprofparser;

import edu.tufts.eaftan.hprofparser.parser.HprofParser;
import net.minecraftforge.srgutils.IMappingFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

public class Parse {
    private static final String MAPPINGS = "/media/data/gradle_cache/caches/forge_gradle/minecraft_user_repo/de/oceanlabs/mcp/mcp_config/1.18.1-20211210.034407/srg_to_official_1.18.1.tsrg";

    public static void main(String[] args) throws Exception {
        var mappings = IMappingFile.load(Path.of(MAPPINGS).toFile());
        HprofParser parser = new HprofParser(mappings);

        try {
            parser.parse(new File("/media/data/Modding/IE-server-118/general.hprof"));
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

}
