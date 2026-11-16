import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
public class MatrixMathTest {

    private static final int REPEATS = 100;
    private static final MatrixMath.Mode[] MODES = {MatrixMath.Mode.STATIC, MatrixMath.Mode.DYNAMIC};

    @Test
    public void smallMatrixKnownResult() {
        long[][] A = {{1, 2}, {3, 4}};
        long[][] B = {{5, 6}, {7, 8}};
        long[][] expected = {{19, 22}, {43, 50}};
        for (MatrixMath.Mode mode : MODES) {
            assertMatrixEquals(expected, MatrixMath.multiply(A, B, 2, mode, 1, MatrixMath.LoopOrder.IKJ, null));
        }
    }

    @Test
    public void rectangularKnownResult() {
        long[][] A = {{1, 2, 3}, {4, 5, 6}};
        long[][] B = {{7, 8}, {9, 10}, {11, 12}};
        long[][] expected = {{58, 64}, {139, 154}};
        assertMatrixEquals(expected, MatrixMath.multiply(A, B, 2, "static"));
        assertMatrixEquals(expected, MatrixMath.multiply(A, B, 2, "dynamic"));
    }

    @Test
    public void bothLoopOrdersGiveSameResult() {
        long[][] A = MatrixMath.generateRandom(17, 23, 5);
        long[][] B = MatrixMath.generateRandom(23, 11, 6);
        long[][] expected = MatrixMath.multiplySequential(A, B);
        for (MatrixMath.LoopOrder order : MatrixMath.LoopOrder.values()) {
            assertMatrixEquals(expected, MatrixMath.multiply(A, B, 3, MatrixMath.Mode.STATIC, 1, order, null));
        }
    }

    @Test
    public void oneByOne() {
        assertMatrixEquals(new long[][]{{35}}, MatrixMath.multiply(new long[][]{{5}}, new long[][]{{7}}, 1, "static"));
    }

    @Test
    public void oneRow() {
        long[][] A = {{1, 2, 3}};
        long[][] B = {{4}, {5}, {6}};
        for (MatrixMath.Mode mode : MODES) {
            assertMatrixEquals(new long[][]{{32}},
                    MatrixMath.multiply(A, B, 4, mode, 1, MatrixMath.LoopOrder.IKJ, null));
        }
    }

    @Test
    public void oneColumn() {
        long[][] A = {{1}, {2}, {3}};
        long[][] B = {{4, 5, 6}};
        long[][] expected = {{4, 5, 6}, {8, 10, 12}, {12, 15, 18}};
        for (MatrixMath.Mode mode : MODES) {
            assertMatrixEquals(expected, MatrixMath.multiply(A, B, 2, mode, 1, MatrixMath.LoopOrder.IKJ, null));
        }
    }

    @Test
    public void identity() {
        long[][] A = MatrixMath.generateRandom(6, 6, 123);
        long[][] E = MatrixMath.generateIdentity(6);
        assertMatrixEquals(A, MatrixMath.multiply(A, E, 4, "dynamic"));
        assertMatrixEquals(A, MatrixMath.multiply(E, A, 4, "static"));
    }

    @Test
    public void zero() {
        long[][] A = MatrixMath.generateRandom(5, 7, 123);
        long[][] Z = MatrixMath.generateZero(7, 4);
        assertMatrixEquals(MatrixMath.generateZero(5, 4), MatrixMath.multiply(A, Z, 3, "static"));
    }

    @Test
    public void incompatibleSizes() {
        long[][] A = {{1, 2}, {3, 4}};   
        long[][] B = {{1, 2, 3}};       
        assertThrows(IllegalArgumentException.class, () -> MatrixMath.multiply(A, B, 1, "static"));
    }

    @Test
    public void invalidArgumentsRejected() {
        long[][] A = {{1}};
        assertThrows(IllegalArgumentException.class, () -> MatrixMath.multiply(A, A, 0, "static")); 
        assertThrows(IllegalArgumentException.class, () -> MatrixMath.multiply(A, A, 1, "dinamic"));  
        assertThrows(IllegalArgumentException.class, () -> MatrixMath.multiply(new long[][]{{1, 2}, {3}}, A, 1, "static"));
        assertThrows(IllegalArgumentException.class, () -> MatrixMath.power(new long[][]{{1, 2}}, 2, 1)); 
        assertThrows(IllegalArgumentException.class, () -> MatrixMath.power(A, 0, 1));           
    }


    @Test
    public void multithreadedMatchesSequential() {
        long[][] A = MatrixMath.generateRandom(10, 12, 42);
        long[][] B = MatrixMath.generateRandom(12, 9, 43);
        long[][] expected = MatrixMath.multiplySequential(A, B);
        int[] threadCounts = {1, 2, 3, 7, 10, 15};
        for (int rep = 0; rep < REPEATS; rep++) {
            for (int T : threadCounts) {
                for (MatrixMath.Mode mode : MODES) {
                    long[][] r = MatrixMath.multiply(A, B, T, mode, 1, MatrixMath.LoopOrder.IKJ, null);
                    assertTrue(MatrixMath.equals(expected, r), "T=" + T + " " + mode + " повтор " + rep);
                }
            }
        }
    }

    @Test
    public void dynamicChunkSizes() {
        long[][] A = MatrixMath.generateRandom(37, 20, 1);
        long[][] B = MatrixMath.generateRandom(20, 15, 2);
        long[][] expected = MatrixMath.multiplySequential(A, B);
        for (int chunk : new int[]{1, 3, 8, 64}) {
            for (int rep = 0; rep < 20; rep++) {
                assertMatrixEquals(expected,
                        MatrixMath.multiply(A, B, 6, MatrixMath.Mode.DYNAMIC, chunk, MatrixMath.LoopOrder.IKJ, null));
            }
        }
    }

    @Test
    public void staticBlocksCoverAllRowsExactlyOnce() {
        for (int n = 1; n <= 30; n++) {
            for (int T = 1; T <= 40; T++) {
                assertEquals(0, MatrixMath.blockStart(n, T, 0));
                assertEquals(n, MatrixMath.blockEnd(n, T, T - 1));
                int min = Integer.MAX_VALUE, max = 0;
                for (int t = 0; t < T; t++) {
                    int size = MatrixMath.blockEnd(n, T, t) - MatrixMath.blockStart(n, T, t);
                    assertTrue(size >= 0);
                    if (t > 0) assertEquals(MatrixMath.blockEnd(n, T, t - 1), MatrixMath.blockStart(n, T, t));
                    min = Math.min(min, size);
                    max = Math.max(max, size);
                }
                assertTrue(max - min <= 1, "n=" + n + " T=" + T);
            }
        }
    }

    @Test
    public void progressReachesExactly100Percent() {
        long[][] A = MatrixMath.generateRandom(200, 8, 1);
        long[][] B = MatrixMath.generateRandom(8, 8, 2);
        for (int rep = 0; rep < REPEATS; rep++) {
            for (MatrixMath.Mode mode : MODES) {
                AtomicInteger done = new AtomicInteger();
                MatrixMath.multiply(A, B, 8, mode, 1, MatrixMath.LoopOrder.IKJ, done);
                assertEquals(200, done.get());
                assertEquals(100, ProgressPrinter.percent(done.get(), 200));
            }
        }
        AtomicInteger tri = new AtomicInteger();
        MatrixMath.multiplyTriangular(MatrixMath.generateTriangular(50, 1), MatrixMath.generateRandom(50, 5, 2),
                7, MatrixMath.Mode.DYNAMIC, 4, tri);
        assertEquals(50, tri.get());
        AtomicInteger pw = new AtomicInteger();
        MatrixMath.power(MatrixMath.generateRandom(9, 9, 1), 5, 4, MatrixMath.PowerSync.BARRIER, pw);
        assertEquals((5 - 1) * 9, pw.get());
    }

    @Test
    public void progressPrinterEndsWith100() {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(buf, true);
        AtomicInteger done = new AtomicInteger();
        try (ProgressPrinter pp = new ProgressPrinter(done, 300, ps)) {
            MatrixMath.multiply(MatrixMath.generateRandom(300, 30, 1), MatrixMath.generateRandom(30, 30, 2),
                    4, MatrixMath.Mode.DYNAMIC, 1, MatrixMath.LoopOrder.IKJ, done);
        }
        String out = buf.toString().trim();
        assertTrue(out.endsWith("Прогресс: 100%"), "Вывод: " + out);
        assertEquals(37, ProgressPrinter.percent(37, 100));
        assertEquals(99, ProgressPrinter.percent(2_147_483_000L, 2_147_483_647L)); 
    }


    @Test
    public void triangularMatchesDenseReference() {
        int n = 23;
        long[][] tri = MatrixMath.generateTriangular(n, 99);
        long[][] B = MatrixMath.generateRandom(n, 7, 77);
        long[][] expected = MatrixMath.multiplySequential(MatrixMath.triangularToDense(tri), B);
        for (int rep = 0; rep < REPEATS; rep++) {
            for (int T : new int[]{1, 2, 3, 7, 30}) {
                for (MatrixMath.Mode mode : MODES) {
                    assertTrue(MatrixMath.equals(expected,
                                    MatrixMath.multiplyTriangular(tri, B, T, mode, 1, null)),
                            "T=" + T + " " + mode);
                }
            }
        }
    }

    @Test
    public void triangularKnownResult() {
        long[][] tri = {{1}, {2, 3}, {4, 5, 6}};
        long[][] B = {{1, 0}, {0, 1}, {1, 1}};
        long[][] expected = {{1, 0}, {2, 3}, {10, 11}};
        assertMatrixEquals(expected, MatrixMath.multiplyTriangular(tri, B, 2, "static"));
        assertMatrixEquals(expected, MatrixMath.multiplyTriangular(tri, B, 2, "dynamic"));
    }

    @Test
    public void triangularEachRowOnce() {
        int n = 64;
        AtomicInteger done = new AtomicInteger();
        MatrixMath.multiplyTriangular(MatrixMath.generateTriangular(n, 1), MatrixMath.generateRandom(n, 3, 2),
                5, MatrixMath.Mode.DYNAMIC, 1, done);
        assertEquals(n, done.get());
    }

    @Test
    public void triangularRejectsWrongShape() {
        long[][] notTri = {{1, 2}, {3, 4}};
        assertThrows(IllegalArgumentException.class,
                () -> MatrixMath.multiplyTriangular(notTri, new long[][]{{1}, {2}}, 1, "static"));
    }


    @Test
    public void powerOne() {
        long[][] A = MatrixMath.generateRandom(5, 5, 11);
        assertMatrixEquals(A, MatrixMath.power(A, 1, 4));
    }

    @Test
    public void powerIdentity() {
        long[][] E = MatrixMath.generateIdentity(6);
        assertMatrixEquals(E, MatrixMath.power(E, 10, 4));
    }

    @Test
    public void powerFibonacci() {
        long[][] F = {{1, 1}, {1, 0}};
        assertMatrixEquals(new long[][]{{8, 5}, {5, 3}}, MatrixMath.power(F, 5, 2));
    }

    @Test
    public void powerMatchesSequentialReference() {
        int n = 9;
        long[][] A = MatrixMath.generateRandom(n, n, 7);
        for (int p = 2; p <= 7; p++) {
            long[][] expected = A;
            for (int s = 1; s < p; s++) expected = MatrixMath.multiplyMod(expected, A);
            for (int T : new int[]{1, 2, 4, 9, 13}) {
                for (int rep = 0; rep < 20; rep++) {
                    assertTrue(MatrixMath.equals(expected, MatrixMath.power(A, p, T)), "p=" + p + " T=" + T);
                }
            }
            assertMatrixEquals(expected, MatrixMath.power(A, p, 3, MatrixMath.PowerSync.RESPAWN, null));
        }
    }

    @Test
    public void powerStaysInModRange() {
        long[][] A = MatrixMath.generateRandom(12, 12, 3);
        long[][] R = MatrixMath.power(A, 30, 4);
        long[][] expected = A;
        for (int s = 1; s < 30; s++) expected = MatrixMath.multiplyMod(expected, A);
        assertMatrixEquals(expected, R);
        for (long[] row : R) for (long v : row) assertTrue(v >= 0 && v < MatrixMath.MOD);
    }

    @Test
    public void ioRoundTripDenseAndTriangular() throws IOException {
        long[][] A = MatrixMath.generateRandom(13, 7, 1);
        A[0][0] = Long.MIN_VALUE; A[1][1] = Long.MAX_VALUE; A[2][2] = -1;
        long[][] tri = MatrixMath.generateTriangular(9, 2);
        long[][] B = MatrixMath.generateRandom(7, 5, 3);
        for (long[][] a : new long[][][]{A, tri}) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            MatrixIO.write(new DataOutputStream(bytes), new MatrixIO.Payload(a == tri, 12345L, a, B));
            assertEquals(MatrixIO.messageSize(a, B), bytes.size());
            MatrixIO.Payload p = MatrixIO.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));
            assertEquals(a == tri, p.triangular);
            assertEquals(12345L, p.genNanos);
            assertMatrixEquals(a, p.A);
            assertMatrixEquals(B, p.B);
        }
    }

    @Test
    public void ioTruncatedStreamFails() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        long[][] A = MatrixMath.generateRandom(4, 4, 1);
        MatrixIO.write(new DataOutputStream(bytes), new MatrixIO.Payload(false, 0, A, A));
        byte[] all = bytes.toByteArray();
        for (int cut : new int[]{3, 20, all.length / 2, all.length - 1}) {
            byte[] part = java.util.Arrays.copyOf(all, cut);
            assertThrows(IOException.class,
                    () -> MatrixIO.read(new DataInputStream(new ByteArrayInputStream(part))), "cut=" + cut);
        }
    }

    @Test
    public void ioGarbageRejected() {
        byte[] text = "Error: Could not find or load main class".getBytes();
        assertThrows(IOException.class, () -> MatrixIO.read(new DataInputStream(new ByteArrayInputStream(text))));
    }

    @Test
    public void realProcessTransfer() throws Exception {
        MatrixIO.Received r = MatrixIO.fetchFromGenerator(30, 20, 10, false);
        assertMatrixEquals(MatrixMath.generateRandom(30, 20, 42), r.payload.A);
        assertMatrixEquals(MatrixMath.generateRandom(20, 10, 43), r.payload.B);

        MatrixIO.Received t = MatrixIO.fetchFromGenerator(15, 15, 4, true);
        assertTrue(t.payload.triangular);
        assertMatrixEquals(MatrixMath.generateTriangular(15, 42), t.payload.A);
    }


    @Test
    public void equalsHandlesJaggedArrays() {
        long[][] A = {{1}, {2, 3}, {4, 5, 6}};
        assertFalse(MatrixMath.equals(A, new long[][]{{1}, {2, 99}, {4, 5, 6}}));
        assertFalse(MatrixMath.equals(A, new long[][]{{1}, {2, 3}, {4, 5}}));
        assertTrue(MatrixMath.equals(A, new long[][]{{1}, {2, 3}, {4, 5, 6}}));
    }

    private static void assertMatrixEquals(long[][] expected, long[][] actual) {
        assertTrue(MatrixMath.equals(expected, actual),
                () -> "Ожидалось:\n" + MatrixMath.toString(expected) + "Получено:\n" + MatrixMath.toString(actual));
    }
}
