package okay.direct.espresso;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectInputStream;
import java.io.ObjectOutput;
import java.util.ArrayList;
import java.util.List;
import org.graalvm.continuations.Continuation;
import org.graalvm.continuations.ContinuationSerializable;
import org.graalvm.continuations.SuspendCapability;

/**
 * A SUSPENDED CONTINUATION COPIED: its frames, nothing else. Espresso writes a continuation's frames to an
 * {@link ObjectOutput} and reads them back; here the objects go by reference, not serialized, so the copy's frames
 * point to the same heap as the original's. Each frame's arrays are copied, so each copy has its own locals. The
 * caller then points the copied frames at the copy's own continuation and records ({@link #patch}).
 */
final class Copier {
    private Copier() {}

    /** A copy: the continuation, its capability to suspend, and its frames' reference arrays, to patch. */
    record Copy(Continuation continuation, SuspendCapability capability, List<Object[]> frames) {}

    static Copy copy(Continuation original) throws IOException, ClassNotFoundException {
        var objects = new ArrayList<Object>();
        var data = new ByteArrayOutputStream();
        var dataOut = new DataOutputStream(data);
        ContinuationSerializable.writeObjectExternal(original, new Out(objects, dataOut));
        dataOut.flush();
        var frames = new ArrayList<Object[]>();
        var in = new In(objects, frames, new DataInputStream(new ByteArrayInputStream(data.toByteArray())));
        var loader = Copier.class.getClassLoader();
        var copied = ContinuationSerializable.readObjectExternal(Continuation.class, in, loader, c -> {});
        // the copy's own capability, read as a capability reads itself: its continuation, the copy
        var capability = ContinuationSerializable.readObjectExternal(SuspendCapability.class, new Only(copied), loader,
            c -> {});
        return new Copy(copied, capability, frames);
    }

    /** The copied frames pointed at the copies: each reference found in {@code copies} replaced. */
    static void patch(List<Object[]> frames, java.util.Map<Object, Object> copies) {
        for (var pointers : frames)
            for (int i = 0; i < pointers.length; i++) {
                var copy = pointers[i] == null ? null : copies.get(pointers[i]);
                if (copy != null) pointers[i] = copy;
            }
    }

    private record Out(List<Object> objects, DataOutputStream data) implements ObjectOutput {
        public void writeObject(Object o) { objects.add(o); }
        public void write(int b) throws IOException { data.write(b); }
        public void write(byte[] b) throws IOException { data.write(b); }
        public void write(byte[] b, int off, int len) throws IOException { data.write(b, off, len); }
        public void writeBoolean(boolean v) throws IOException { data.writeBoolean(v); }
        public void writeByte(int v) throws IOException { data.writeByte(v); }
        public void writeShort(int v) throws IOException { data.writeShort(v); }
        public void writeChar(int v) throws IOException { data.writeChar(v); }
        public void writeInt(int v) throws IOException { data.writeInt(v); }
        public void writeLong(long v) throws IOException { data.writeLong(v); }
        public void writeFloat(float v) throws IOException { data.writeFloat(v); }
        public void writeDouble(double v) throws IOException { data.writeDouble(v); }
        public void writeBytes(String s) throws IOException { data.writeBytes(s); }
        public void writeChars(String s) throws IOException { data.writeChars(s); }
        public void writeUTF(String s) throws IOException { data.writeUTF(s); }
        public void flush() {}
        public void close() {}
    }

    private static final class In implements ObjectInput {
        private final List<Object> objects;
        private final List<Object[]> frames;
        private final DataInputStream data;
        private int next;

        In(List<Object> objects, List<Object[]> frames, DataInputStream data) {
            this.objects = objects;
            this.frames = frames;
            this.data = data;
        }

        /** A frame's arrays copied, its own locals; anything else the same object. */
        public Object readObject() {
            var o = objects.get(next++);
            if (o instanceof Object[] pointers) {
                var copy = pointers.clone();
                frames.add(copy);
                return copy;
            }
            if (o instanceof long[] primitives) return primitives.clone();
            return o;
        }

        public int read() throws IOException { return data.read(); }
        public int read(byte[] b) throws IOException { return data.read(b); }
        public int read(byte[] b, int off, int len) throws IOException { return data.read(b, off, len); }
        public long skip(long n) throws IOException { return data.skip(n); }
        public int available() throws IOException { return data.available(); }
        public void close() {}
        public void readFully(byte[] b) throws IOException { data.readFully(b); }
        public void readFully(byte[] b, int off, int len) throws IOException { data.readFully(b, off, len); }
        public int skipBytes(int n) throws IOException { return data.skipBytes(n); }
        public boolean readBoolean() throws IOException { return data.readBoolean(); }
        public byte readByte() throws IOException { return data.readByte(); }
        public int readUnsignedByte() throws IOException { return data.readUnsignedByte(); }
        public short readShort() throws IOException { return data.readShort(); }
        public int readUnsignedShort() throws IOException { return data.readUnsignedShort(); }
        public char readChar() throws IOException { return data.readChar(); }
        public int readInt() throws IOException { return data.readInt(); }
        public long readLong() throws IOException { return data.readLong(); }
        public float readFloat() throws IOException { return data.readFloat(); }
        public double readDouble() throws IOException { return data.readDouble(); }
        @SuppressWarnings("deprecation") public String readLine() throws IOException { return data.readLine(); }
        public String readUTF() throws IOException { return data.readUTF(); }
    }

    /** An ObjectInput of one object. */
    private static final class Only extends ObjectInputStream {
        private final Object only;

        Only(Object only) throws IOException {
            this.only = only;
        }

        @Override
        protected Object readObjectOverride() {
            return only;
        }
    }
}
