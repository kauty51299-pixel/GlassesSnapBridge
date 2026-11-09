package com.sunny.glassesbridge;

import java.nio.ByteBuffer;

/** Copies tightly packed I420 into Android YUV_420_888 planes, including padding. */
public final class YuvPlanes {
    private YuvPlanes() {}

    public static void writePlane(byte[] source, int offset, int width, int height,
                                  ByteBuffer destination, int rowStride, int pixelStride) {
        if (width <= 0 || height <= 0 || pixelStride <= 0 || rowStride < (width - 1) * pixelStride + 1)
            throw new IllegalArgumentException("Invalid plane geometry");
        if (offset < 0 || (long) offset + (long) width * height > source.length)
            throw new IllegalArgumentException("Truncated source plane");
        int base = destination.position();
        long end = (long) base + (long) (height - 1) * rowStride + (long) (width - 1) * pixelStride;
        if (end >= destination.limit()) throw new IllegalArgumentException("Truncated destination plane");
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                destination.put(base + y * rowStride + x * pixelStride, source[offset + y * width + x]);
            }
        }
    }

    public static byte[] toNv21(byte[] i420, int width, int height) {
        if (width <= 0 || height <= 0 || width % 2 != 0 || height % 2 != 0)
            throw new IllegalArgumentException("Even dimensions required");
        int y = Math.multiplyExact(width, height);
        if (i420.length != y + y / 2) throw new IllegalArgumentException("Unexpected frame size");
        byte[] out = new byte[i420.length];
        System.arraycopy(i420, 0, out, 0, y);
        for (int i = 0; i < y / 4; i++) {
            out[y + 2 * i] = i420[y + y / 4 + i];
            out[y + 2 * i + 1] = i420[y + i];
        }
        return out;
    }
}
