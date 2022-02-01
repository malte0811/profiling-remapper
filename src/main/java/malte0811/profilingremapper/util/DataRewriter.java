package malte0811.profilingremapper.util;

import malte0811.profilingremapper.hprof.datastructures.Type;

import javax.annotation.Nullable;
import java.io.*;

public class DataRewriter implements AutoCloseable {
    private int idSize = 4;
    private final InputStream innerIn;
    private final OutputStream innerOut;
    private final DataInput in;
    private final DataOutput out;

    public DataRewriter(InputStream in, @Nullable OutputStream out) {
        this.innerIn = in;
        this.innerOut = out;
        this.in = new DataInputStream(in);
        if (out != null) {
            this.out = new DataOutputStream(out);
        } else {
            this.out = DummyDataOutput.INSTANCE;
        }
    }

    public DataRewriter(File input, File output) throws IOException {
        this(
                new BufferedInputStream(new FileInputStream(input)),
                new BufferedOutputStream(new FileOutputStream(output))
        );
    }

    public int idSize() {
        return idSize;
    }

    public DataInput in() {
        return in;
    }

    public DataOutput out() {
        return out;
    }

    public OutputStream rawOut() {
        return innerOut;
    }

    public void setIdSize(int idSize) {
        this.idSize = idSize;
    }

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

    public String rereadUTF() throws IOException {
        final var result = in.readUTF();
        out.writeUTF(result);
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

    public String rereadUntilNull() throws IOException {
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

    @Override
    public void close() throws IOException {
        innerIn.close();
        if (innerOut != null) {
            innerOut.close();
        }
    }
}
