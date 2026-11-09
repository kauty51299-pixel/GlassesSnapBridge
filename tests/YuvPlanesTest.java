import com.sunny.glassesbridge.YuvPlanes;
import java.nio.ByteBuffer;
import java.util.Arrays;

public class YuvPlanesTest {
    private static int checks;
    private static void check(boolean value) {
        checks++;
        if (!value) throw new AssertionError("Check " + checks + " failed");
    }
    private static void rejects(Runnable task) {
        boolean rejected = false;
        try { task.run(); } catch (IllegalArgumentException e) { rejected = true; }
        check(rejected);
    }
    public static void main(String[] args) {
        byte[] source = {1, 2, 3, 4, 5, 6, 7, 8};
        ByteBuffer padded = ByteBuffer.allocate(12);
        YuvPlanes.writePlane(source, 0, 4, 2, padded, 6, 1);
        check(Arrays.equals(padded.array(), new byte[]{1,2,3,4,0,0,5,6,7,8,0,0}));

        // Chroma planes can overlap the same backing buffer with pixel stride 2.
        byte[] uv = {10, 11, 12, 13, 20, 21, 22, 23};
        ByteBuffer shared = ByteBuffer.allocate(8);
        ByteBuffer u = shared.duplicate();
        ByteBuffer v = shared.duplicate(); v.position(1);
        YuvPlanes.writePlane(uv, 0, 2, 2, u, 4, 2);
        YuvPlanes.writePlane(uv, 4, 2, 2, v, 4, 2);
        check(Arrays.equals(shared.array(), new byte[]{10,20,11,21,12,22,13,23}));

        // A sliced plane starts at its own nonzero position, including the final byte.
        ByteBuffer offset = ByteBuffer.allocate(10); offset.position(2);
        YuvPlanes.writePlane(source, 0, 4, 2, offset, 4, 1);
        check(offset.get(2) == 1 && offset.get(9) == 8 && offset.position() == 2);

        byte[] i420 = {1,2,3,4,5,6,7,8, 10,11, 20,21};
        check(Arrays.equals(YuvPlanes.toNv21(i420, 4, 2), new byte[]{1,2,3,4,5,6,7,8,20,10,21,11}));
        rejects(() -> YuvPlanes.toNv21(new byte[11], 4, 2));
        rejects(() -> YuvPlanes.toNv21(new byte[12], 3, 2));
        rejects(() -> YuvPlanes.writePlane(source, 0, 4, 2, ByteBuffer.allocate(7), 4, 1));
        rejects(() -> YuvPlanes.writePlane(source, 2, 4, 2, ByteBuffer.allocate(8), 4, 1));
        rejects(() -> YuvPlanes.writePlane(source, 0, 4, 2, ByteBuffer.allocate(8), 3, 1));
        System.out.println("PASS: " + checks + " frame-layout checks");
    }
}
