package com.poco.visioncoprocessor;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.ImageFormat;
import android.graphics.PointF;
import android.graphics.Rect;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;

import com.google.android.gms.tasks.Task;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;
import com.google.mlkit.vision.face.FaceLandmark;
import com.google.mlkit.vision.segmentation.Segmentation;
import com.google.mlkit.vision.segmentation.SegmentationMask;
import com.google.mlkit.vision.segmentation.Segmenter;
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions;

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
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.Deflater;

public final class VisionService extends Service {

    public static final String ACTION_START = "com.poco.visioncoprocessor.START";
    public static final String ACTION_STOP = "com.poco.visioncoprocessor.STOP";
    public static final String EXTRA_HOST = "host";

    private static final int NOTIFICATION_ID = 84;
    private static final String CHANNEL_ID = "redmi_vision";

    private static final int MAGIC = 0x50565231; // PVR1
    private static final int DISCOVERY_PORT = 48764;
    private static final int TCP_PORT = 48765;
    private static final int H264_CODEC_ID = 0x68323634;

    private static final int MSG_HELLO = 1;
    private static final int MSG_SESSION = 2;
    private static final int MSG_VIDEO = 3;
    private static final int MSG_MASK = 16;
    private static final int MSG_FACE = 17;

    private static final int FLAG_CONFIG = 1;
    private static final int MAX_MESSAGE = 16 * 1024 * 1024;

    private static volatile String status = "Detenido";
    private static volatile String requestedHost = "";

    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean segmentationBusy = new AtomicBoolean();
    private final AtomicBoolean faceBusy = new AtomicBoolean();

    private final Object decoderLock = new Object();
    private final Object outputLock = new Object();

    private Thread networkThread;
    private volatile Socket activeSocket;
    private volatile DataOutputStream activeOut;

    private Segmenter segmenter;
    private FaceDetector faceDetector;
    private ExecutorService inferenceExecutor;
    private HandlerThread imageThread;
    private Handler imageHandler;

    private MediaCodec decoder;
    private ImageReader imageReader;
    private int decoderWidth;
    private int decoderHeight;
    private byte[] pendingConfig;
    private boolean configQueued;

    private long fpsWindowStartMs;
    private int fpsWindowFrames;
    private float inferenceFps;
    private long faceFpsWindowStartMs;
    private int faceFpsWindowFrames;
    private float faceFps;

    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private WifiManager.MulticastLock multicastLock;

    public static String getStatus() {
        return status;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(NOTIFICATION_ID, buildNotification("Esperando POCO"));

        SelfieSegmenterOptions segmentationOptions =
                new SelfieSegmenterOptions.Builder()
                        .setDetectorMode(SelfieSegmenterOptions.STREAM_MODE)
                        .enableRawSizeMask()
                        .build();
        segmenter = Segmentation.getClient(segmentationOptions);

        FaceDetectorOptions faceOptions =
                new FaceDetectorOptions.Builder()
                        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                        .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
                        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                        .setMinFaceSize(0.08f)
                        .enableTracking()
                        .build();
        faceDetector = FaceDetection.getClient(faceOptions);

        inferenceExecutor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "redmi-vision-inference");
            t.setPriority(Thread.NORM_PRIORITY + 1);
            return t;
        });

        imageThread = new HandlerThread("redmi-vision-images");
        imageThread.start();
        imageHandler = new Handler(imageThread.getLooper());

        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RedmiVision:Coprocessor");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();

        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "RedmiVision:WiFi");
        wifiLock.setReferenceCounted(false);
        wifiLock.acquire();

        multicastLock = wifi.createMulticastLock("RedmiVision:Discovery");
        multicastLock.setReferenceCounted(false);
        multicastLock.acquire();

        fpsWindowStartMs = SystemClock.elapsedRealtime();
        faceFpsWindowStartMs = fpsWindowStartMs;
        status = "Servicio listo. Esperando anuncio del POCO...";
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            running.set(false);
            closeQuietly(activeSocket);
            stopSelf();
            return START_NOT_STICKY;
        }

        String host = intent != null ? intent.getStringExtra(EXTRA_HOST) : null;
        requestedHost = host != null ? host.trim() : "";
        if (running.compareAndSet(false, true)) {
            networkThread = new Thread(this::networkLoop, "redmi-vision-network");
            networkThread.start();
        } else if (!requestedHost.isEmpty()) {
            // Force a reconnect if the user explicitly selected a POCO address.
            closeQuietly(activeSocket);
        }

        return START_STICKY;
    }

    private void networkLoop() {
        while (running.get()) {
            String host = requestedHost;
            if (host.isEmpty()) {
                status = "Buscando POCO V8.5 por Wi-Fi...";
                host = discoverHost();
            }
            if (!running.get()) {
                break;
            }
            if (host == null || host.isEmpty()) {
                sleep(500);
                continue;
            }

            try {
                connectAndRun(host);
            } catch (IOException | RuntimeException e) {
                status = "Desconectado de " + host + ": " + e.getMessage();
            } finally {
                closeQuietly(activeSocket);
                activeSocket = null;
                activeOut = null;
                releaseDecoder();
            }

            sleep(900);
        }
    }

    private String discoverHost() {
        byte[] buffer = new byte[128];
        try (DatagramSocket socket = new DatagramSocket(null)) {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(DISCOVERY_PORT));
            socket.setSoTimeout(2500);

            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            socket.receive(packet);
            String message = new String(packet.getData(), packet.getOffset(),
                    packet.getLength(), StandardCharsets.US_ASCII).trim();
            if (message.startsWith("POCO_VISION_V1")) {
                return packet.getAddress().getHostAddress();
            }
        } catch (SocketTimeoutException ignored) {
            // normal: keep scanning
        } catch (IOException e) {
            status = "Autodescubrimiento Wi-Fi: " + e.getMessage();
        }
        return null;
    }

    private void connectAndRun(String host) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(host, TCP_PORT), 3500);
        socket.setTcpNoDelay(true);
        socket.setKeepAlive(true);
        activeSocket = socket;

        DataInputStream in = new DataInputStream(
                new BufferedInputStream(socket.getInputStream(), 256 * 1024));
        DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(socket.getOutputStream(), 256 * 1024));
        activeOut = out;

        status = "Conectado al POCO " + host + ". Esperando video H.264...";
        updateNotification("Conectado a " + host);

        while (running.get() && !socket.isClosed()) {
            int magic;
            try {
                magic = in.readInt();
            } catch (EOFException e) {
                break;
            }
            if (magic != MAGIC) {
                throw new IOException("Protocolo invalido");
            }
            int type = in.readUnsignedByte();
            int payloadSize = in.readInt();
            if (payloadSize < 0 || payloadSize > MAX_MESSAGE) {
                throw new IOException("Mensaje demasiado grande: " + payloadSize);
            }

            switch (type) {
                case MSG_HELLO:
                    readHello(in, payloadSize);
                    break;
                case MSG_SESSION:
                    readSession(in, payloadSize);
                    break;
                case MSG_VIDEO:
                    readVideo(in, payloadSize);
                    break;
                default:
                    skipFully(in, payloadSize);
                    break;
            }
        }
    }

    private void readHello(DataInputStream in, int size) throws IOException {
        if (size < 16) {
            throw new IOException("HELLO incompleto");
        }
        int protocol = in.readInt();
        int codecId = in.readInt();
        int width = in.readInt();
        int height = in.readInt();
        skipFully(in, size - 16);

        if (protocol != 1) {
            throw new IOException("Version de protocolo no compatible: " + protocol);
        }
        if (codecId != H264_CODEC_ID) {
            throw new IOException("El coprocesador V8.4 requiere H.264");
        }
        if (width > 0 && height > 0) {
            configureDecoder(width, height);
        }
    }

    private void readSession(DataInputStream in, int size) throws IOException {
        if (size < 8) {
            throw new IOException("SESSION incompleta");
        }
        int width = in.readInt();
        int height = in.readInt();
        skipFully(in, size - 8);
        configureDecoder(width, height);
    }

    private void readVideo(DataInputStream in, int size) throws IOException {
        if (size < 16) {
            throw new IOException("VIDEO incompleto");
        }
        long ptsUs = in.readLong();
        int flags = in.readInt();
        int dataLength = in.readInt();
        if (dataLength < 0 || dataLength > MAX_MESSAGE || 16 + dataLength > size) {
            throw new IOException("Paquete H.264 invalido");
        }
        byte[] data = new byte[dataLength];
        in.readFully(data);
        skipFully(in, size - 16 - dataLength);

        if ((flags & FLAG_CONFIG) != 0) {
            pendingConfig = data;
        }
        feedDecoder(data, ptsUs, flags);
    }

    private void configureDecoder(int width, int height) throws IOException {
        if (width <= 0 || height <= 0) {
            return;
        }
        synchronized (decoderLock) {
            if (decoder != null && decoderWidth == width && decoderHeight == height) {
                return;
            }
            releaseDecoderLocked();

            try {
                imageReader = ImageReader.newInstance(width, height,
                        ImageFormat.YUV_420_888, 4);
                imageReader.setOnImageAvailableListener(this::onImageAvailable, imageHandler);

                MediaFormat format = MediaFormat.createVideoFormat(
                        MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
                format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,
                        Math.max(2 * 1024 * 1024, width * height));

                decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
                decoder.configure(format, imageReader.getSurface(), null, 0);
                decoder.start();

                decoderWidth = width;
                decoderHeight = height;
                configQueued = false;

                if (pendingConfig != null) {
                    queueInputLocked(pendingConfig, 0,
                            MediaCodec.BUFFER_FLAG_CODEC_CONFIG);
                    configQueued = true;
                }

                status = "POCO conectado: " + width + "x" + height
                        + ". Segmentacion + tracking activos.";
            } catch (Exception e) {
                releaseDecoderLocked();
                throw new IOException("No se pudo iniciar decoder H.264: " + e.getMessage(), e);
            }
        }
    }

    private void feedDecoder(byte[] data, long ptsUs, int flags) throws IOException {
        synchronized (decoderLock) {
            if (decoder == null) {
                return;
            }
            drainDecoderLocked();

            int codecFlags = (flags & FLAG_CONFIG) != 0
                    ? MediaCodec.BUFFER_FLAG_CODEC_CONFIG : 0;
            if (codecFlags != 0 && configQueued) {
                return;
            }
            queueInputLocked(data, ptsUs, codecFlags);
            if (codecFlags != 0) {
                configQueued = true;
            }
            drainDecoderLocked();
        }
    }

    private void queueInputLocked(byte[] data, long ptsUs, int flags)
            throws IOException {
        int index;
        try {
            index = decoder.dequeueInputBuffer(20_000);
        } catch (IllegalStateException e) {
            throw new IOException("Decoder no disponible", e);
        }
        if (index < 0) {
            // One retry after draining output; never block the network forever.
            drainDecoderLocked();
            index = decoder.dequeueInputBuffer(20_000);
        }
        if (index < 0) {
            throw new IOException("Decoder H.264 saturado");
        }

        ByteBuffer buffer = decoder.getInputBuffer(index);
        if (buffer == null || buffer.capacity() < data.length) {
            throw new IOException("NAL H.264 excede el buffer del decoder");
        }
        buffer.clear();
        buffer.put(data);
        decoder.queueInputBuffer(index, 0, data.length, ptsUs, flags);
    }

    private void drainDecoderLocked() {
        if (decoder == null) {
            return;
        }
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (true) {
            int index;
            try {
                index = decoder.dequeueOutputBuffer(info, 0);
            } catch (IllegalStateException e) {
                return;
            }
            if (index >= 0) {
                decoder.releaseOutputBuffer(index, true);
                continue;
            }
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                continue;
            }
            break;
        }
    }

    private void onImageAvailable(ImageReader reader) {
        Image image = reader.acquireLatestImage();
        if (image == null) {
            return;
        }

        boolean doFace = faceBusy.compareAndSet(false, true);
        boolean doSegmentation = segmentationBusy.compareAndSet(false, true);
        if (!doFace && !doSegmentation) {
            image.close();
            return;
        }

        final long startedMs = SystemClock.elapsedRealtime();
        final int imageWidth = image.getWidth();
        final int imageHeight = image.getHeight();
        final AtomicInteger pending = new AtomicInteger((doFace ? 1 : 0) + (doSegmentation ? 1 : 0));

        final InputImage input;
        try {
            input = InputImage.fromMediaImage(image, 0);
        } catch (RuntimeException e) {
            if (doFace) faceBusy.set(false);
            if (doSegmentation) segmentationBusy.set(false);
            image.close();
            return;
        }

        if (doFace) {
            try {
                faceDetector.process(input)
                        .addOnCompleteListener(inferenceExecutor, task -> {
                            try {
                                List<Face> faces = task.isSuccessful() ? task.getResult() : null;
                                sendFaceResult(faces, imageWidth, imageHeight, startedMs);
                            } catch (RuntimeException e) {
                                status = "Tracking: " + e.getMessage();
                            } finally {
                                faceBusy.set(false);
                                if (pending.decrementAndGet() == 0) {
                                    image.close();
                                }
                            }
                        });
            } catch (RuntimeException e) {
                faceBusy.set(false);
                if (pending.decrementAndGet() == 0) {
                    image.close();
                }
            }
        }

        if (doSegmentation) {
            try {
                segmenter.process(input)
                        .addOnCompleteListener(inferenceExecutor, task -> {
                            try {
                                SegmentationMask mask = task.isSuccessful() ? task.getResult() : null;
                                if (mask != null) {
                                    sendMaskResult(mask, startedMs);
                                }
                            } catch (RuntimeException e) {
                                status = "Segmentacion: " + e.getMessage();
                            } finally {
                                segmentationBusy.set(false);
                                if (pending.decrementAndGet() == 0) {
                                    image.close();
                                }
                            }
                        });
            } catch (RuntimeException e) {
                segmentationBusy.set(false);
                if (pending.decrementAndGet() == 0) {
                    image.close();
                }
            }
        }
    }

    private void sendFaceResult(List<Face> faces, int imageWidth, int imageHeight,
                                long startedMs) {
        FaceData face = FaceData.NONE;
        long now = SystemClock.elapsedRealtime();
        int latencyMs = (int) Math.min(Integer.MAX_VALUE, now - startedMs);
        updateFaceFps(now);

        DataOutputStream out = activeOut;
        if (out == null) {
            return;
        }

        long ptsUs = System.nanoTime() / 1000L;
        synchronized (outputLock) {
            try {
                int bodySize = 8 + 4 + 4 + 10 * 4;
                out.writeInt(MAGIC);
                out.writeByte(MSG_FACE);
                out.writeInt(bodySize);
                out.writeLong(ptsUs);
                out.writeFloat(faceFps);
                out.writeInt(latencyMs);

                out.writeFloat(face.x);
                out.writeFloat(face.y);
                out.writeFloat(face.w);
                out.writeFloat(face.h);
                out.writeFloat(face.leftEyeX);
                out.writeFloat(face.leftEyeY);
                out.writeFloat(face.rightEyeX);
                out.writeFloat(face.rightEyeY);
                out.writeFloat(face.mouthX);
                out.writeFloat(face.mouthY);
                out.flush();
            } catch (IOException e) {
                closeQuietly(activeSocket);
            }
        }
    }

    private void sendMaskResult(SegmentationMask mask, long startedMs) {
        int maskWidth = mask.getWidth();
        int maskHeight = mask.getHeight();
        int count = maskWidth * maskHeight;
        byte[] raw = new byte[count];

        ByteBuffer confidence = mask.getBuffer().duplicate().order(ByteOrder.nativeOrder());
        confidence.rewind();
        for (int i = 0; i < count && confidence.remaining() >= 4; ++i) {
            float value = confidence.getFloat();
            int q = Math.round(Math.max(0f, Math.min(1f, value)) * 255f);
            raw[i] = (byte) q;
        }

        FaceData face = selectFace(faces, imageWidth, imageHeight);
        byte[] compressed = deflate(raw);
        int compression = compressed.length < raw.length ? 1 : 0;
        byte[] payload = compression == 1 ? compressed : raw;

        long now = SystemClock.elapsedRealtime();
        int latencyMs = (int) Math.min(Integer.MAX_VALUE, now - startedMs);
        updateFps(now);

        DataOutputStream out = activeOut;
        if (out == null) {
            return;
        }

        // The exact source PTS is not exposed by ImageReader. The bridge uses
        // this timestamp for freshness only, not A/V synchronization.
        long ptsUs = System.nanoTime() / 1000L;

        synchronized (outputLock) {
            try {
                int bodySize = 8 + 4 + 4 + 4 + 4 + 10 * 4 + 1 + 4 + payload.length;
                out.writeInt(MAGIC);
                out.writeByte(MSG_MASK);
                out.writeInt(bodySize);
                out.writeLong(ptsUs);
                out.writeInt(maskWidth);
                out.writeInt(maskHeight);
                out.writeFloat(inferenceFps);
                out.writeInt(latencyMs);

                out.writeFloat(face.x);
                out.writeFloat(face.y);
                out.writeFloat(face.w);
                out.writeFloat(face.h);
                out.writeFloat(face.leftEyeX);
                out.writeFloat(face.leftEyeY);
                out.writeFloat(face.rightEyeX);
                out.writeFloat(face.rightEyeY);
                out.writeFloat(face.mouthX);
                out.writeFloat(face.mouthY);

                out.writeByte(compression);
                out.writeInt(payload.length);
                out.write(payload);
                out.flush();

                status = String.format(Locale.ROOT,
                        "V8.5: tracking %.1f fps | mask %.1f fps | %d ms | %dx%d",
                        faceFps, inferenceFps, latencyMs, maskWidth, maskHeight);
            } catch (IOException e) {
                closeQuietly(activeSocket);
            }
        }
    }

    private void updateFps(long nowMs) {
        ++fpsWindowFrames;
        long elapsed = nowMs - fpsWindowStartMs;
        if (elapsed >= 1500) {
            inferenceFps = fpsWindowFrames * 1000f / elapsed;
            fpsWindowFrames = 0;
            fpsWindowStartMs = nowMs;
        }
    }

    private void updateFaceFps(long nowMs) {
        ++faceFpsWindowFrames;
        long elapsed = nowMs - faceFpsWindowStartMs;
        if (elapsed >= 1000) {
            faceFps = faceFpsWindowFrames * 1000f / elapsed;
            faceFpsWindowFrames = 0;
            faceFpsWindowStartMs = nowMs;
        }
    }

    private static FaceData selectFace(List<Face> faces, int width, int height) {
        if (faces == null || faces.isEmpty() || width <= 0 || height <= 0) {
            return FaceData.NONE;
        }

        Face best = null;
        long bestArea = 0;
        for (Face face : faces) {
            Rect box = face.getBoundingBox();
            long area = (long) box.width() * box.height();
            if (area > bestArea) {
                bestArea = area;
                best = face;
            }
        }
        if (best == null) {
            return FaceData.NONE;
        }

        Rect box = best.getBoundingBox();
        float x = box.left / (float) width;
        float y = box.top / (float) height;
        float w = box.width() / (float) width;
        float h = box.height() / (float) height;

        float[] leftEye = landmark(best, FaceLandmark.LEFT_EYE, width, height);
        float[] rightEye = landmark(best, FaceLandmark.RIGHT_EYE, width, height);
        float[] mouth = landmark(best, FaceLandmark.MOUTH_BOTTOM, width, height);

        if (leftEye == null) {
            leftEye = new float[]{x + w * 0.34f, y + h * 0.40f};
        }
        if (rightEye == null) {
            rightEye = new float[]{x + w * 0.66f, y + h * 0.40f};
        }
        if (mouth == null) {
            mouth = new float[]{x + w * 0.50f, y + h * 0.70f};
        }

        return new FaceData(x, y, w, h,
                leftEye[0], leftEye[1], rightEye[0], rightEye[1],
                mouth[0], mouth[1]);
    }

    private static float[] landmark(Face face, int type, int width, int height) {
        FaceLandmark landmark = face.getLandmark(type);
        if (landmark == null) {
            return null;
        }
        PointF point = landmark.getPosition();
        return new float[]{point.x / width, point.y / height};
    }

    private static byte[] deflate(byte[] input) {
        Deflater deflater = new Deflater(Deflater.BEST_SPEED);
        deflater.setInput(input);
        deflater.finish();

        ByteArrayOutputStream out = new ByteArrayOutputStream(input.length / 2);
        byte[] buffer = new byte[8192];
        try {
            while (!deflater.finished()) {
                int count = deflater.deflate(buffer);
                if (count <= 0) {
                    break;
                }
                out.write(buffer, 0, count);
            }
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    private void releaseDecoder() {
        synchronized (decoderLock) {
            releaseDecoderLocked();
        }
    }

    private void releaseDecoderLocked() {
        if (decoder != null) {
            try {
                decoder.stop();
            } catch (Exception ignored) {
                // ignored
            }
            decoder.release();
            decoder = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
        decoderWidth = 0;
        decoderHeight = 0;
        configQueued = false;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Redmi Vision Coprocessor",
                    NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setContentTitle("Redmi Vision Coprocessor V8.5")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager manager =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.notify(NOTIFICATION_ID, buildNotification(text));
    }

    @Override
    public void onDestroy() {
        running.set(false);
        closeQuietly(activeSocket);
        if (networkThread != null) {
            networkThread.interrupt();
        }
        releaseDecoder();

        if (segmenter != null) {
            segmenter.close();
        }
        if (faceDetector != null) {
            faceDetector.close();
        }
        if (inferenceExecutor != null) {
            inferenceExecutor.shutdownNow();
        }
        if (imageThread != null) {
            imageThread.quitSafely();
        }

        if (multicastLock != null && multicastLock.isHeld()) {
            multicastLock.release();
        }
        if (wifiLock != null && wifiLock.isHeld()) {
            wifiLock.release();
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }

        status = "Detenido";
        stopForeground(true);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private static void skipFully(DataInputStream in, int size) throws IOException {
        int remaining = size;
        byte[] temp = new byte[Math.min(8192, Math.max(1, size))];
        while (remaining > 0) {
            int read = in.read(temp, 0, Math.min(temp.length, remaining));
            if (read < 0) {
                throw new EOFException();
            }
            remaining -= read;
        }
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

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class FaceData {
        static final FaceData NONE = new FaceData(-1, -1, -1, -1,
                -1, -1, -1, -1, -1, -1);

        final float x;
        final float y;
        final float w;
        final float h;
        final float leftEyeX;
        final float leftEyeY;
        final float rightEyeX;
        final float rightEyeY;
        final float mouthX;
        final float mouthY;

        FaceData(float x, float y, float w, float h,
                 float leftEyeX, float leftEyeY,
                 float rightEyeX, float rightEyeY,
                 float mouthX, float mouthY) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.leftEyeX = leftEyeX;
            this.leftEyeY = leftEyeY;
            this.rightEyeX = rightEyeX;
            this.rightEyeY = rightEyeY;
            this.mouthX = mouthX;
            this.mouthY = mouthY;
        }
    }
}
