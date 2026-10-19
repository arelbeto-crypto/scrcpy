package com.genymobile.scrcpy.video;

import java.io.DataInputStream;
import java.io.IOException;

/** Version-one face header with an optional bounded dense-mesh extension. */
public final class VisionFacePacket {
    public long ptsUs;
    public float fps;
    public int latencyMs;
    public float[] face;
    public float[] mesh;
    public short[] triangles;

    public static VisionFacePacket read(DataInputStream in, int size) throws IOException {
        if (size < 56 || size > 24_000) throw new IOException("Invalid face packet size");
        VisionFacePacket result = new VisionFacePacket();
        result.ptsUs = in.readLong();
        result.fps = finite(in.readFloat());
        result.latencyMs = in.readInt();
        result.face = new float[10];
        for (int i = 0; i < result.face.length; i++) result.face[i] = finite(in.readFloat());
        if (size == 56) return result;
        if (size < 64) throw new IOException("Truncated mesh extension");
        int count = in.readInt();
        if (count < 0 || count > 478 || (count > 0 && count < 3)) {
            throw new IOException("Invalid mesh point count");
        }
        int remaining = size - 64 - count * 12;
        if (remaining < 0) throw new IOException("Truncated mesh points");
        if (count > 0) {
            result.mesh = new float[count * 3];
            for (int i = 0; i < result.mesh.length; i++) {
                float coordinate = finite(in.readFloat());
                if (Math.abs(coordinate) > 4) throw new IOException("Unbounded mesh coordinate");
                result.mesh[i] = coordinate;
            }
        }
        int indices = in.readInt();
        if (indices < 0 || indices > 9000 || indices % 3 != 0 || remaining != indices * 2
                || (count == 0 && indices != 0)) throw new IOException("Invalid mesh topology size");
        if (indices > 0) {
            result.triangles = new short[indices];
            for (int i = 0; i < indices; i++) {
                int index = in.readUnsignedShort();
                if (index >= count) throw new IOException("Mesh index out of range");
                result.triangles[i] = (short) index;
            }
        }
        return result;
    }

    private static float finite(float value) throws IOException {
        if (Float.isNaN(value) || Float.isInfinite(value)) throw new IOException("Non-finite face data");
        return value;
    }
}
