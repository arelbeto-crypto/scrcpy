package com.genymobile.scrcpy.video;

import android.graphics.Bitmap;
import com.genymobile.scrcpy.model.Codec;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

public final class VisionBridgeTest {
    private static final int MAGIC = 0x50565231;
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static void await(BooleanSupplier condition, String failure) throws Exception {
        long deadline = System.nanoTime() + 2_000_000_000L;
        do {
            if (condition.getAsBoolean()) return;
            Thread.sleep(1);
        } while (System.nanoTime() < deadline);
        throw new AssertionError(failure);
    }
    private static int packet(DataInputStream in) throws Exception {
        check(in.readInt() == MAGIC, "Protocol magic");
        int type = in.readUnsignedByte(), length = in.readInt();
        check(length >= 0 && length < 1_000_000, "Packet bound");
        in.readFully(new byte[length]);
        return type;
    }
    private static Socket connect() throws Exception {
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (true) {
            try { return new Socket("127.0.0.1", 48765); }
            catch (java.net.ConnectException e) { if (System.nanoTime() > deadline) throw e; Thread.sleep(1); }
        }
    }
    private static long writerCount() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.isAlive() && t.getName().equals("vision-writer")).count();
    }
    public static void main(String[] args) throws Exception {
        VisionBridge bridge = VisionBridge.get();
        bridge.start(Codec.H264);
        bridge.onSession(640, 480);
        try {
            try (Socket socket = connect()) {
                socket.setSoTimeout(2000);
                DataInputStream in = new DataInputStream(socket.getInputStream());
                DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                check(packet(in) == 1 && packet(in) == 2, "Initial hello/session");
                out.writeInt(MAGIC); out.writeByte(32); out.writeInt(4); out.writeInt(3); out.flush();
                await(() -> bridge.reserveAnalysisFrame(System.nanoTime() / 1000), "Clean analysis capability negotiation");
                Bitmap.entered = new CountDownLatch(1); Bitmap.resume = new CountDownLatch(1);
                long oldPtsUs = System.nanoTime() / 1000 - 1;
                bridge.offerRgbaFrame(new byte[16], 2, 2, oldPtsUs);
                check(Bitmap.entered.await(2, TimeUnit.SECONDS), "JPEG worker entered");
                bridge.onSession(480, 640);
                check(packet(in) == 2, "New session must reach the peer");
                Bitmap.resume.countDown();
                socket.setSoTimeout(180);
                boolean oldImageDelivered = false;
                try { oldImageDelivered = packet(in) == 4; } catch (SocketTimeoutException expected) { }
                check(!oldImageDelivered, "An old JPEG finishing after reset must not enter the new session");
                socket.setSoTimeout(2000);
                // Pause a response in its body, then change session before publication.
                out.writeInt(MAGIC); out.writeByte(17); out.writeInt(56); out.flush();
                await(() -> Thread.getAllStackTraces().entrySet().stream().anyMatch(entry ->
                        entry.getKey().getName().equals("vision-reader") && java.util.Arrays.stream(entry.getValue())
                        .anyMatch(frame -> frame.getClassName().equals(VisionFacePacket.class.getName()))),
                        "Reader entered the partial face packet");
                bridge.onSession(640, 480);
                check(packet(in) == 2, "Response reset session");
                long currentPtsUs = System.nanoTime() / 1000 + 1;
                out.writeLong(currentPtsUs); out.writeFloat(10); out.writeInt(10);
                for (float f : new float[] {.1f,.2f,.3f,.4f,.2f,.3f,.3f,.3f,.25f,.45f}) out.writeFloat(f);
                // A following accepted mask proves the first face packet has been fully consumed.
                out.writeInt(MAGIC); out.writeByte(16); out.writeInt(70);
                out.writeLong(currentPtsUs); out.writeInt(1); out.writeInt(1); out.writeFloat(10); out.writeInt(10);
                for (int i = 0; i < 10; i++) out.writeFloat(-1);
                out.writeByte(0); out.writeInt(1); out.writeByte(255); out.flush();
                await(() -> RemoteVisionState.get().getFreshMask() != null, "Current-session result accepted");
                check(RemoteVisionState.get().getFreshFace() == null,
                        "A response being parsed across reset must not publish in the new session");
            }
            Bitmap.entered = null; Bitmap.resume = null;
            await(() -> writerCount() == 0, "Closing an idle client must terminate its blocked writer");
            for (int i = 0; i < 8; i++) {
                try (Socket socket = connect()) {
                    socket.setSoTimeout(2000);
                    DataInputStream in = new DataInputStream(socket.getInputStream());
                    check(packet(in) == 1 && packet(in) == 2, "Reconnect handshake");
                }
                await(() -> writerCount() == 0, "Idle reconnects must not accumulate writer threads");
            }
            try (Socket socket = connect()) {
                socket.setSoTimeout(2000);
                DataInputStream in = new DataInputStream(socket.getInputStream());
                check(packet(in) == 1 && packet(in) == 2, "Legacy handshake");
                bridge.onSession(480, 640);
                boolean closed = false;
                try { packet(in); } catch (java.io.EOFException expected) { closed = true; }
                check(closed, "A legacy peer without source timestamps must reconnect across session changes");
            }
            try (Socket socket = connect()) {
                socket.setSoTimeout(2000);
                DataInputStream in = new DataInputStream(socket.getInputStream());
                check(packet(in) == 1 && packet(in) == 2, "Legacy reconnect after changed session");
            }
            await(() -> writerCount() == 0, "Legacy session reset must also terminate its writer");
            System.out.println("VisionBridgeTest: compression/response races, legacy reset, and 11 disconnects passed");
        } finally {
            if (Bitmap.resume != null) Bitmap.resume.countDown();
            bridge.stop();
        }
    }
}
