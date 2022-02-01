package edu.tufts.eaftan.hprofparser.parser;

import edu.tufts.eaftan.hprofparser.parser.datastructures.Type;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

public record DataRewriter(int idSize, DataInput in, DataOutput out) {
    public long rereadId() throws IOException {
        long id = readId();
        writeId(id);
        return id;
    }

    public long rereadLong() throws IOException {
        final long result = in.readLong();
        out.writeLong(result);
        return result;
    }

    public byte rereadByte() throws IOException {
        final byte result = in.readByte();
        out.writeByte(result);
        return result;
    }

    public int rereadInt() throws IOException {
        final int result = in.readInt();
        out.writeInt(result);
        return result;
    }

    public boolean rereadBoolean() throws IOException {
        final boolean result = in.readBoolean();
        out.writeBoolean(result);
        return result;
    }

    public char rereadChar() throws IOException {
        final char result = in.readChar();
        out.writeChar(result);
        return result;
    }

    public float rereadFloat() throws IOException {
        final float result = in.readFloat();
        out.writeFloat(result);
        return result;
    }

    public double rereadDouble() throws IOException {
        final double result = in.readDouble();
        out.writeDouble(result);
        return result;
    }

    public short rereadShort() throws IOException {
        final short result = in.readShort();
        out.writeShort(result);
        return result;
    }

    public void rereadFully(byte[] data) throws IOException {
        in.readFully(data);
        out.write(data);
    }

    public void rereadValue(Type type, int[] bytesReadRef) throws IOException {
        bytesReadRef[0] += type.sizeInBytes(idSize());
        switch (type) {
            case OBJ -> {
                long vid = rereadId();
            }
            case BOOL -> {
                boolean vbool = rereadBoolean();
            }
            case CHAR -> {
                char vc = rereadChar();
            }
            case FLOAT -> {
                float vf = rereadFloat();
            }
            case DOUBLE -> {
                double vd = rereadDouble();
            }
            case BYTE -> {
                byte vbyte = rereadByte();
            }
            case SHORT -> {
                short vs = rereadShort();
            }
            case INT -> {
                int vi = rereadInt();
            }
            case LONG -> {
                long vl = rereadLong();
            }
        }
        ;
    }

    public long readId() throws IOException {
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

    public void writeId(long id) throws IOException {
        if (idSize == 4) {
            out.writeInt((int) id);//TODO may be wrong?
        } else if (idSize == 8) {
            out.writeLong(id);
        } else {
            throw new IllegalArgumentException("Invalid identifier size " + idSize);
        }
    }
}
