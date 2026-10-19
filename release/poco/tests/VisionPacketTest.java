package com.genymobile.scrcpy.video;

import java.io.*;

public final class VisionPacketTest {
    private static byte[] packet(boolean dense, int index, boolean nan) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeLong(1234567); out.writeFloat(15); out.writeInt(20);
        for (int i = 0; i < 10; i++) out.writeFloat(i == 0 && nan ? Float.NaN : .3f);
        if (dense) {
            out.writeInt(3);
            for (int i = 0; i < 9; i++) out.writeFloat(.25f);
            out.writeInt(3); out.writeShort(0); out.writeShort(1); out.writeShort(index);
        }
        return bytes.toByteArray();
    }
    private static VisionFacePacket read(byte[] bytes) throws IOException {
        return VisionFacePacket.read(new DataInputStream(new ByteArrayInputStream(bytes)), bytes.length);
    }
    private static void rejected(byte[] bytes, String reason) throws Exception {
        try { read(bytes); throw new AssertionError(reason); }
        catch (IOException expected) { }
    }
    public static void main(String[] args) throws Exception {
        VisionFacePacket old;
        try { old = read(packet(false, 0, false)); }
        catch (IOException e) { throw new AssertionError("A valid legacy header must remain readable", e); }
        if (old.ptsUs != 1234567 || old.mesh != null) throw new AssertionError("Legacy PTS must be preserved");
        VisionFacePacket mesh = read(packet(true, 2, false));
        if (mesh.mesh.length != 9 || mesh.triangles[2] != 2) throw new AssertionError("Dense topology lost");
        rejected(packet(true, 3, false), "Out-of-range mesh index accepted");
        rejected(packet(false, 0, true), "NaN face accepted");
        byte[] truncated = packet(true, 2, false);
        rejected(java.util.Arrays.copyOf(truncated, truncated.length - 1), "Truncated frame accepted");
        byte[] oversized = packet(true, 2, false);
        java.nio.ByteBuffer.wrap(oversized).putInt(56, Integer.MAX_VALUE);
        rejected(oversized, "Unbounded allocation accepted");
        System.out.println("VisionPacketTest: 6 behavior checks passed");
    }
}
