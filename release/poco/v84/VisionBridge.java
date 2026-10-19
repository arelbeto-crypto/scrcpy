package com.genymobile.scrcpy.video;

import android.graphics.Bitmap;

import com.genymobile.scrcpy.model.Codec;
import com.genymobile.scrcpy.util.Ln;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

public final class VisionBridge implements Closeable {

    private static final int MAGIC = 0x50565231; // PVR1
    private static final int PROTOCOL = 1;
    private static final int DISCOVERY_PORT = 48764;
    private static final int TCP_PORT = 48765;

    private static final int MSG_HELLO = 1;
    private static final int MSG_SESSION = 2;
    private static final int MSG_VIDEO = 3;
    private static final int MSG_ANALYSIS_JPEG = 4;
    private static final int MSG_MASK = 16;
    private static final int MSG_FACE = 17;
    private static final int MSG_CAPABILITIES = 32;

    private static final int FLAG_CONFIG = 1;
    private static final int FLAG_KEY = 2;
    private static final int MAX_MESSAGE = 16 * 1024 * 1024;

    private static final VisionBridge INSTANCE = new VisionBridge();

    private final RemoteVisionState visionState = RemoteVisionState.get();
    private final AtomicBoolean running = new AtomicBoolean();
    private final Object clientLock = new Object();
    private final AtomicBoolean analysisBusy = new AtomicBoolean();
    private ExecutorService analysisExecutor;
    private volatile long lastAnalysisUs;
    private int[] analysisColors;
    private Bitmap analysisBitmap;

    private volatile Client client;
    private volatile int codecId;
    private volatile int width;
    private volatile int height;
    private volatile byte[] codecConfig;

    private ServerSocket serverSocket;
    private Thread acceptThread;
    private Thread beaconThread;

    private VisionBridge() {
    }

    public static VisionBridge get() {
        return INSTANCE;
    }

    public synchronized void start(Codec codec) {
        if (running.get()) {
            return;
        }
        codecId = codec.getId();
        analysisExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "vision-jpeg");
            t.setDaemon(true);
            return t;
        });
        running.set(true);

        acceptThread = new Thread(this::acceptLoop, "vision-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();

        beaconThread = new Thread(this::beaconLoop, "vision-beacon");
        beaconThread.setDaemon(true);
        beaconThread.start();

        Ln.i("POCO V8.6 Vision Bridge: waiting for Redmi coprocessor on TCP " + TCP_PORT);
    }

    public boolean isRunning() {
        return running.get();
    }

    public void onSession(int width, int height) {
        synchronized (clientLock) {
            this.width = width;
            this.height = height;
            visionState.resetTracking();
            lastAnalysisUs = 0;
            Client c = client;
            if (c != null) {
                c.resetForSession();
                c.enqueue(Message.session(width, height));
            }
        }
    }

    public void offerPacket(ByteBuffer source, long ptsUs, boolean config, boolean keyFrame) {
        if (!running.get()) {
            return;
        }

        if (config) {
            codecConfig = copy(source);
            Client c = client;
            if (c != null) {
                c.setConfig(codecConfig);
            }
            return;
        }

        Client c = client;
        if (c == null) {
            return;
        }
        if (c.rawAnalysis) return;

        if (c.waitingForKeyFrame()) {
            if (!keyFrame) {
                return;
            }
            byte[] configBytes = codecConfig;
            if (configBytes != null) {
                c.enqueue(Message.video(ptsUs, FLAG_CONFIG, configBytes));
            }
            c.markKeyFrameReady();
        }

        int flags = keyFrame ? FLAG_KEY : 0;
        byte[] data = copy(source);
        if (!c.enqueue(Message.video(ptsUs, flags, data))) {
            Ln.w("POCO V8.6 Vision Bridge: analysis queue full, dropping backlog until next keyframe");
            c.resyncAtNextKeyFrame();
        }
    }

    /** Called on the GL thread. At most one readback/compression is ever in flight. */
    public boolean reserveAnalysisFrame(long nowUs) {
        Client c = client;
        if (!running.get() || c == null || !c.rawAnalysis || !c.alive.get()) return false;
        if (!visionState.isBokehEnabled() && visionState.getArMode() == 0
                && visionState.getBackgroundMode() == 0) return false;
        long intervalUs = Math.max(83_333L, Math.min(180_000L, visionState.getRemoteLatencyMs() * 1000L));
        if (nowUs - lastAnalysisUs < intervalUs || !analysisBusy.compareAndSet(false, true)) return false;
        lastAnalysisUs = nowUs;
        return true;
    }

    public void cancelAnalysisFrame() { analysisBusy.set(false); }

    public void offerRgbaFrame(byte[] rgba, int frameWidth, int frameHeight, long ptsUs) {
        final Client target = client;
        final long generation = visionState.getSessionGeneration();
        ExecutorService executor = analysisExecutor;
        if (target == null || !target.rawAnalysis || executor == null) {
            analysisBusy.set(false);
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    if (!target.alive.get() || target != client || !visionState.isCurrentSource(ptsUs)
                            || generation != visionState.getSessionGeneration()) return;
                    int count = frameWidth * frameHeight;
                    if (analysisColors == null || analysisColors.length != count) analysisColors = new int[count];
                    for (int y = 0; y < frameHeight; y++) {
                        for (int x = 0; x < frameWidth; x++) {
                            int src = ((frameHeight - 1 - y) * frameWidth + x) * 4;
                            analysisColors[y * frameWidth + x] = 0xFF000000
                                    | (rgba[src] & 255) << 16 | (rgba[src + 1] & 255) << 8 | (rgba[src + 2] & 255);
                        }
                    }
                    if (analysisBitmap == null || analysisBitmap.getWidth() != frameWidth
                            || analysisBitmap.getHeight() != frameHeight) {
                        if (analysisBitmap != null) analysisBitmap.recycle();
                        analysisBitmap = Bitmap.createBitmap(frameWidth, frameHeight, Bitmap.Config.ARGB_8888);
                    }
                    analysisBitmap.setPixels(analysisColors, 0, frameWidth, 0, 0, frameWidth, frameHeight);
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream(48 * 1024);
                    if (!analysisBitmap.compress(Bitmap.CompressFormat.JPEG, 88, bytes)) return;
                    // Fresh images replace queued work; never build an inference backlog.
                    synchronized (clientLock) {
                        if (target == client && generation == visionState.getSessionGeneration()
                                && visionState.isCurrentSource(ptsUs) && System.nanoTime() / 1000 - ptsUs < 250_000) {
                            target.removeQueued(MSG_ANALYSIS_JPEG);
                            target.enqueue(new Message(MSG_ANALYSIS_JPEG, ptsUs, 0, bytes.toByteArray(), frameWidth, frameHeight));
                        }
                    }
                } catch (RuntimeException e) {
                    Ln.w("Vision analysis frame skipped: " + e.getMessage());
                } finally {
                    analysisBusy.set(false);
                }
            });
        } catch (RejectedExecutionException e) {
            analysisBusy.set(false);
        }
    }

    private static byte[] copy(ByteBuffer source) {
        ByteBuffer duplicate = source.duplicate();
        byte[] data = new byte[duplicate.remaining()];
        duplicate.get(data);
        return data;
    }

    private void acceptLoop() {
        try (ServerSocket server = new ServerSocket(TCP_PORT)) {
            serverSocket = server;
            server.setReuseAddress(true);
            while (running.get()) {
                Socket socket = server.accept();
                socket.setTcpNoDelay(true);
                socket.setKeepAlive(true);
                attachClient(socket);
            }
        } catch (SocketException e) {
            if (running.get()) {
                Ln.w("POCO V8.6 Vision Bridge socket error: " + e.getMessage());
            }
        } catch (IOException e) {
            if (running.get()) {
                Ln.e("POCO V8.6 Vision Bridge accept failed", e);
            }
        }
    }

    private void attachClient(Socket socket) {
        synchronized (clientLock) {
            Client old = client;
            if (old != null) {
                old.close();
            }
            try {
                Client next = new Client(socket);
                client = next;
                visionState.setConnected(true, socket.getInetAddress().getHostAddress());
                next.start();
                Ln.i("POCO V8.6 Vision Bridge: Redmi connected from "
                        + socket.getInetAddress().getHostAddress());
            } catch (IOException e) {
                closeQuietly(socket);
            }
        }
    }

    private void detachClient(Client candidate) {
        synchronized (clientLock) {
            if (client == candidate) {
                client = null;
                visionState.setConnected(false, "-");
            }
        }
    }

    private void beaconLoop() {
        byte[] payload = ("POCO_VISION_V1 " + TCP_PORT).getBytes(StandardCharsets.US_ASCII);
        while (running.get()) {
            try (DatagramSocket socket = new DatagramSocket()) {
                socket.setBroadcast(true);
                DatagramPacket packet = new DatagramPacket(payload, payload.length,
                        InetAddress.getByName("255.255.255.255"), DISCOVERY_PORT);
                socket.send(packet);
            } catch (IOException e) {
                // Wi-Fi can disappear temporarily; the next beacon retries.
            }
            try {
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void readMask(DataInputStream in, int payloadSize, Client sender, long generation) throws IOException {
        if (payloadSize < 8 + 4 + 4 + 4 + 4 + 10 * 4 + 1 + 4) {
            throw new IOException("Invalid Redmi mask payload");
        }
        long ptsUs = in.readLong();
        int maskWidth = in.readInt();
        int maskHeight = in.readInt();
        float fps = in.readFloat();
        int latencyMs = in.readInt();

        float x = in.readFloat();
        float y = in.readFloat();
        float w = in.readFloat();
        float h = in.readFloat();
        float leftEyeX = in.readFloat();
        float leftEyeY = in.readFloat();
        float rightEyeX = in.readFloat();
        float rightEyeY = in.readFloat();
        float mouthX = in.readFloat();
        float mouthY = in.readFloat();

        int compression = in.readUnsignedByte();
        int dataLength = in.readInt();
        if (maskWidth <= 0 || maskHeight <= 0 || maskWidth > 1024 || maskHeight > 1024
                || dataLength < 0 || dataLength > 1024 * 1024 || payloadSize != 69 + dataLength) {
            throw new IOException("Invalid Redmi mask dimensions");
        }
        byte[] payload = new byte[dataLength];
        in.readFully(payload);

        int expected = maskWidth * maskHeight;
        byte[] mask;
        if (compression == 1) {
            mask = inflate(payload, expected);
        } else if (compression == 0 && payload.length == expected) {
            mask = payload;
        } else {
            throw new IOException("Invalid Redmi mask compression");
        }

        if (!sender.sourceClock) ptsUs = System.nanoTime() / 1000 - Math.max(0, latencyMs) * 1000L;
        synchronized (clientLock) {
            if (sender == client && sender.alive.get() && generation == visionState.getSessionGeneration()) {
                visionState.updateMask(mask, maskWidth, maskHeight, ptsUs, fps, latencyMs);
            }
        }
    }

    private void readFace(DataInputStream in, int payloadSize, Client sender, long generation) throws IOException {
        VisionFacePacket packet = VisionFacePacket.read(in, payloadSize);
        float[] f = packet.face;
        long ptsUs = sender.sourceClock ? packet.ptsUs
                : System.nanoTime() / 1000 - Math.max(0, packet.latencyMs) * 1000L;
        synchronized (clientLock) {
            if (sender == client && sender.alive.get() && generation == visionState.getSessionGeneration()) {
                visionState.updateRemoteFace(f[0], f[1], f[2], f[3], f[4], f[5], f[6], f[7], f[8], f[9],
                        ptsUs, packet.mesh, packet.triangles);
                visionState.updateRemoteStats(packet.fps, packet.latencyMs);
            }
        }
    }

    private static byte[] inflate(byte[] compressed, int expected) throws IOException {
        Inflater inflater = new Inflater();
        inflater.setInput(compressed);
        ByteArrayOutputStream out = new ByteArrayOutputStream(expected);
        byte[] buffer = new byte[8192];
        try {
            while (!inflater.finished() && !inflater.needsInput()) {
                int count = inflater.inflate(buffer);
                if (count == 0) throw new IOException("Compressed mask made no progress");
                out.write(buffer, 0, count);
                if (out.size() > expected) {
                    throw new IOException("Mask is larger than expected");
                }
            }
            if (!inflater.finished() || inflater.getRemaining() != 0) throw new IOException("Incomplete compressed mask");
        } catch (DataFormatException e) {
            throw new IOException("Invalid compressed mask", e);
        } finally {
            inflater.end();
        }
        byte[] result = out.toByteArray();
        if (result.length != expected) {
            throw new IOException("Incomplete mask: " + result.length + "/" + expected);
        }
        return result;
    }

    @Override
    public synchronized void close() {
        stop();
    }

    public synchronized void stop() {
        if (!running.getAndSet(false)) {
            return;
        }
        Client c = client;
        if (c != null) {
            c.close();
        }
        closeQuietly(serverSocket);
        if (acceptThread != null) {
            acceptThread.interrupt();
        }
        if (beaconThread != null) {
            beaconThread.interrupt();
        }
        serverSocket = null;
        acceptThread = null;
        beaconThread = null;
        codecConfig = null;
        ExecutorService executor = analysisExecutor;
        analysisExecutor = null;
        if (executor != null) executor.shutdownNow();
        visionState.setConnected(false, "-");
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException ignored) {
                // ignored
            }
        }
    }

    private static final class Message {
        final int type;
        final long ptsUs;
        final int flags;
        final byte[] data;
        final int width;
        final int height;

        private Message(int type, long ptsUs, int flags, byte[] data, int width, int height) {
            this.type = type;
            this.ptsUs = ptsUs;
            this.flags = flags;
            this.data = data;
            this.width = width;
            this.height = height;
        }

        static Message video(long ptsUs, int flags, byte[] data) {
            return new Message(MSG_VIDEO, ptsUs, flags, data, 0, 0);
        }

        static Message session(int width, int height) {
            return new Message(MSG_SESSION, 0, 0, null, width, height);
        }
    }

    private final class Client implements Closeable {
        private final Socket socket;
        private final DataInputStream in;
        private final DataOutputStream out;
        private final ArrayBlockingQueue<Message> queue = new ArrayBlockingQueue<>(24);
        private final AtomicBoolean alive = new AtomicBoolean(true);
        private volatile boolean waitingKeyFrame = true;
        private volatile boolean rawAnalysis;
        private volatile boolean sourceClock;
        private volatile Thread writerThread;
        private volatile Thread readerThread;

        Client(Socket socket) throws IOException {
            this.socket = socket;
            in = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 256 * 1024));
            out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), 256 * 1024));
        }

        void start() {
            Thread writer = new Thread(this::writeLoop, "vision-writer");
            writerThread = writer;
            writer.setDaemon(true);
            writer.start();

            Thread reader = new Thread(this::readLoop, "vision-reader");
            readerThread = reader;
            reader.setDaemon(true);
            reader.start();
        }

        boolean enqueue(Message message) {
            return alive.get() && queue.offer(message);
        }

        void removeQueued(int type) {
            // Collection.removeIf requires Android 24; this bridge also supports older servers.
            for (Message message : queue) {
                if (message.type == type) queue.remove(message);
            }
        }

        boolean waitingForKeyFrame() {
            return waitingKeyFrame;
        }

        void markKeyFrameReady() {
            waitingKeyFrame = false;
        }

        void setConfig(byte[] ignored) {
            // The cached config is sent immediately before the next keyframe.
        }

        void resetForSession() {
            waitingKeyFrame = true;
            queue.clear();
        }

        void resyncAtNextKeyFrame() {
            waitingKeyFrame = true;
            queue.clear();
        }

        private void writeLoop() {
            try {
                writeHello();
                if (width > 0 && height > 0) {
                    writeSession(width, height);
                }
                while (alive.get()) {
                    Message message = queue.take();
                    if (message.type == MSG_SESSION) {
                        writeSession(message.width, message.height);
                    } else if (message.type == MSG_VIDEO) {
                        if (!rawAnalysis) writeVideo(message);
                    } else if (message.type == MSG_ANALYSIS_JPEG) {
                        if (visionState.isCurrentSource(message.ptsUs)
                                && System.nanoTime() / 1000 - message.ptsUs < 250_000) writeAnalysis(message);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                if (alive.get()) {
                    Ln.w("POCO V8.6 Vision Bridge writer closed: " + e.getMessage());
                }
            } finally {
                close();
            }
        }

        private void readLoop() {
            try {
                while (alive.get()) {
                    int magic = in.readInt();
                    if (magic != MAGIC) {
                        throw new IOException("Invalid Redmi protocol magic");
                    }
                    int type = in.readUnsignedByte();
                    int size = in.readInt();
                    if (size < 0 || size > MAX_MESSAGE) {
                        throw new IOException("Invalid Redmi message size");
                    }
                    if (type == MSG_MASK) {
                        readMask(in, size, this, visionState.getSessionGeneration());
                    } else if (type == MSG_FACE) {
                        readFace(in, size, this, visionState.getSessionGeneration());
                    } else if (type == MSG_CAPABILITIES) {
                        if (size != 4) throw new IOException("Invalid capabilities");
                        int capabilities = in.readInt();
                        sourceClock = (capabilities & 2) != 0;
                        rawAnalysis = (capabilities & 1) != 0;
                        if (rawAnalysis) removeQueued(MSG_VIDEO);
                    } else {
                        skipFully(in, size);
                    }
                }
            } catch (EOFException ignored) {
                // normal disconnect
            } catch (IOException e) {
                if (alive.get()) {
                    Ln.w("POCO V8.6 Vision Bridge reader closed: " + e.getMessage());
                }
            } finally {
                close();
            }
        }

        private void writeHello() throws IOException {
            out.writeInt(MAGIC);
            out.writeByte(MSG_HELLO);
            out.writeInt(4 + 4 + 4 + 4 + 4);
            out.writeInt(PROTOCOL);
            out.writeInt(codecId);
            out.writeInt(width);
            out.writeInt(height);
            out.writeInt(1); // server supports a clean, bounded JPEG analysis tap
            out.flush();
        }

        private void writeSession(int width, int height) throws IOException {
            out.writeInt(MAGIC);
            out.writeByte(MSG_SESSION);
            out.writeInt(8);
            out.writeInt(width);
            out.writeInt(height);
            out.flush();
        }

        private void writeVideo(Message message) throws IOException {
            int dataLength = message.data.length;
            out.writeInt(MAGIC);
            out.writeByte(MSG_VIDEO);
            out.writeInt(8 + 4 + 4 + dataLength);
            out.writeLong(message.ptsUs);
            out.writeInt(message.flags);
            out.writeInt(dataLength);
            out.write(message.data);
            out.flush();
        }

        private void writeAnalysis(Message message) throws IOException {
            out.writeInt(MAGIC);
            out.writeByte(MSG_ANALYSIS_JPEG);
            out.writeInt(20 + message.data.length);
            out.writeLong(message.ptsUs);
            out.writeInt(message.width);
            out.writeInt(message.height);
            out.writeInt(message.data.length);
            out.write(message.data);
            out.flush();
        }

        @Override
        public void close() {
            if (!alive.getAndSet(false)) {
                return;
            }
            closeQuietly(socket);
            queue.clear();
            Thread writer = writerThread;
            if (writer != null) writer.interrupt();
            Thread reader = readerThread;
            if (reader != null) reader.interrupt();
            detachClient(this);
        }
    }

    private static void skipFully(DataInputStream in, int size) throws IOException {
        int remaining = size;
        byte[] temp = new byte[Math.min(8192, Math.max(1, size))];
        while (remaining > 0) {
            int count = in.read(temp, 0, Math.min(temp.length, remaining));
            if (count < 0) {
                throw new EOFException();
            }
            remaining -= count;
        }
    }
}
