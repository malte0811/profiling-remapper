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

package edu.tufts.eaftan.hprofparser.parser.datastructures;

import edu.tufts.eaftan.hprofparser.parser.HprofParserException;

public enum Type {

    OBJ("Object"),
    BOOL("boolean"),
    CHAR("char"),
    FLOAT("float"),
    DOUBLE("double"),
    BYTE("byte"),
    SHORT("short"),
    INT("int"),
    LONG("long");

    private final String name;

    Type(String name) {
        this.name = name;
    }

    public static Type hprofTypeToEnum(byte type) {
        return switch (type) {
            case 2 -> OBJ;
            case 4 -> BOOL;
            case 5 -> CHAR;
            case 6 -> FLOAT;
            case 7 -> DOUBLE;
            case 8 -> BYTE;
            case 9 -> SHORT;
            case 10 -> INT;
            case 11 -> LONG;
            default -> throw new HprofParserException("Unexpected type in heap dump: " + type);
        };
    }

    @Override
    public String toString() {
        return name;
    }
}


