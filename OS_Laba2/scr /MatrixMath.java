import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class MatrixMath {

    public static final long MOD = 1_000_000_007L;

    private MatrixMath() {}

    public enum Mode {
        STATIC,
        DYNAMIC;

        public static Mode parse(String s) {
            if (s == null) throw new IllegalArgumentException("Не задан способ распределения");
            switch (s.toLowerCase()) {
                case "static":  return STATIC;
                case "dynamic": return DYNAMIC;
                default: throw new IllegalArgumentException(
                        "Неизвестный способ распределения: '" + s + "' (ожидается static или dynamic)");
            }
        }
    }

    public enum LoopOrder {
        IKJ,
        IJK;

        public static LoopOrder parse(String s) {
            if (s == null) throw new IllegalArgumentException("Не задан порядок циклов");
            switch (s.toLowerCase()) {
                case "ikj": case "itj": return IKJ;
                case "ijk": case "ijt": return IJK;
                default: throw new IllegalArgumentException(
                        "Неизвестный порядок циклов: '" + s + "' (ожидается ikj или ijk)");
            }
        }
    }

    public enum PowerSync {
        BARRIER,
        NO_BARRIER,
        RESPAWN;

        public static PowerSync parse(String s) {
            if (s == null) throw new IllegalArgumentException("Не задан режим синхронизации");
            switch (s.toLowerCase()) {
                case "barrier":   return BARRIER;
                case "nobarrier": case "no_barrier": case "no-barrier": return NO_BARRIER;
                case "respawn":   return RESPAWN;
                default: throw new IllegalArgumentException(
                        "Неизвестный режим: '" + s + "' (ожидается barrier, nobarrier или respawn)");
            }
        }
    }

    public static long[][] multiply(long[][] A, long[][] B, int T, String mode) {
        return multiply(A, B, T, Mode.parse(mode), 1, LoopOrder.IKJ, null);
    }

    public static long[][] multiply(long[][] A, long[][] B, int T, Mode mode, int chunk,
                                    LoopOrder order, AtomicInteger progress) {
        int n = rows(A, "A");
        int m = requireRectangular(A, "A");
        int bRows = rows(B, "B");
        int k = requireRectangular(B, "B");
        if (n == 0) throw new IllegalArgumentException("Матрица A пустая");
        if (m != bRows) {
            throw new IllegalArgumentException(
                    "Несовместимые размеры: A " + n + "×" + m + ", B " + bRows + "×" + k
                            + " (число столбцов A должно равняться числу строк B)");
        }
        long[][] C = new long[n][k];
        runParallel(n, T, mode, chunk, row -> computeRow(A[row], B, C[row], order), progress);
        return C;
    }

    public static long[][] multiplyTriangular(long[][] A, long[][] B, int T, String mode) {
        return multiplyTriangular(A, B, T, Mode.parse(mode), 1, null);
    }

    public static long[][] multiplyTriangular(long[][] A, long[][] B, int T, Mode mode, int chunk,
                                              AtomicInteger progress) {
        int n = rows(A, "A");
        if (n == 0) throw new IllegalArgumentException("Матрица A пустая");
        for (int i = 0; i < n; i++) {
            if (A[i] == null || A[i].length != i + 1) {
                throw new IllegalArgumentException(
                        "A не нижнетреугольная: строка " + i + " должна содержать " + (i + 1) + " элементов");
            }
        }
        int bRows = rows(B, "B");
        int k = requireRectangular(B, "B");
        if (bRows != n) {
            throw new IllegalArgumentException(
                    "Несовместимые размеры: A " + n + "×" + n + ", B " + bRows + "×" + k);
        }
        long[][] C = new long[n][k];

        runParallel(n, T, mode, chunk, row -> computeRow(A[row], B, C[row], LoopOrder.IKJ), progress);
        return C;
    }

    static void computeRow(long[] a, long[][] B, long[] c, LoopOrder order) {
        int len = a.length;
        int k = c.length;
        if (order == LoopOrder.IKJ) {
            for (int t = 0; t < len; t++) {
                long av = a[t];
                long[] b = B[t];
                for (int j = 0; j < k; j++) {
                    c[j] += av * b[j];
                }
            }
        } else {
            for (int j = 0; j < k; j++) {
                long sum = 0;
                for (int t = 0; t < len; t++) {
                    sum += a[t] * B[t][j];
                }
                c[j] = sum;
            }
        }
    }

    public static long[][] multiplySequential(long[][] A, long[][] B) {
        int n = A.length, m = A[0].length, k = B[0].length;
        if (m != B.length) throw new IllegalArgumentException("Несовместимые размеры");
        long[][] C = new long[n][k];
        for (int i = 0; i < n; i++)
            for (int j = 0; j < k; j++) {
                long s = 0;
                for (int t = 0; t < m; t++) s += A[i][t] * B[t][j];
                C[i][j] = s;
            }
        return C;
    }

    @FunctionalInterface
    interface RowTask {
        void compute(int row);
    }

    public static int blockStart(int n, int T, int t) {
        int base = n / T;
        int rem = n % T;
        return t * base + Math.min(t, rem);
    }

    public static int blockEnd(int n, int T, int t) {
        return blockStart(n, T, t + 1);
    }


    static void runParallel(int rows, int T, Mode mode, int chunk, RowTask task, AtomicInteger progress) {
        if (T < 1) throw new IllegalArgumentException("Число потоков должно быть ≥ 1, получено " + T);
        if (mode == null) throw new IllegalArgumentException("Не задан способ распределения");
        if (mode == Mode.DYNAMIC && chunk < 1) {
            throw new IllegalArgumentException("Размер порции должен быть ≥ 1, получено " + chunk);
        }
        AtomicReference<Throwable> error = new AtomicReference<>();
        AtomicInteger nextRow = new AtomicInteger(0);
        Thread[] workers = new Thread[T];

        for (int t = 0; t < T; t++) {
            final int id = t;
            Runnable body;
            if (mode == Mode.STATIC) {
                body = () -> {
                    int from = blockStart(rows, T, id);
                    int to = blockEnd(rows, T, id);
                    for (int i = from; i < to; i++) {
                        task.compute(i);
                        if (progress != null) progress.incrementAndGet();
                    }
                };
            } else {
                body = () -> {
                    while (true) {
                        int from = nextRow.getAndAdd(chunk);
                        if (from >= rows) break;
                        int to = Math.min(from + chunk, rows);
                        for (int i = from; i < to; i++) {
                            task.compute(i);
                            if (progress != null) progress.incrementAndGet();
                        }
                    }
                };
            }
            workers[t] = new Thread(guard(body, error), "worker-" + t);
            workers[t].start();
        }
        joinAll(workers);
        rethrow(error);
    }

    public static long[][] power(long[][] A, int p, int T) {
        return power(A, p, T, PowerSync.BARRIER, null);
    }

    public static long[][] power(long[][] A, int p, int T, PowerSync sync, AtomicInteger progress) {
        int n = rows(A, "A");
        if (n == 0) throw new IllegalArgumentException("Матрица A пустая");
        if (requireRectangular(A, "A") != n) {
            throw new IllegalArgumentException("Возводить в степень можно только квадратную матрицу");
        }
        if (p < 1) throw new IllegalArgumentException("Степень p должна быть ≥ 1, получено " + p);
        if (T < 1) throw new IllegalArgumentException("Число потоков должно быть ≥ 1, получено " + T);

        long[][] base = new long[n][n];      
        long[][] buf0 = new long[n][n];    
        long[][] buf1 = new long[n][n];
        for (int i = 0; i < n; i++)
            for (int j = 0; j < n; j++) {
                base[i][j] = Math.floorMod(A[i][j], MOD);
                buf0[i][j] = base[i][j];
            }
        if (p == 1) return buf0;

        int steps = p - 1;
        if (sync == PowerSync.RESPAWN) {
            long[][] cur = buf0, next = buf1;
            for (int s = 0; s < steps; s++) {
                final long[][] c = cur, nx = next;
                runParallel(n, T, Mode.STATIC, 1, i -> powerRow(base, c, nx[i], i), progress);
                long[][] tmp = cur; cur = next; next = tmp;
            }
            return cur;
        }

        final boolean useBarrier = (sync == PowerSync.BARRIER);
        CyclicBarrier barrier = new CyclicBarrier(T);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread[] workers = new Thread[T];

        for (int t = 0; t < T; t++) {
            final int from = blockStart(n, T, t);
            final int to = blockEnd(n, T, t);
            Runnable body = () -> {
                long[][] cur = buf0;  
                long[][] next = buf1; 
                for (int s = 0; s < steps; s++) {
                    for (int i = from; i < to; i++) {
                        powerRow(base, cur, next[i], i);
                        if (progress != null) progress.incrementAndGet();
                    }
                    if (useBarrier) {
                        awaitBarrier(barrier);
                    }
                    long[][] tmp = cur; cur = next; next = tmp;
                }
            };
            workers[t] = new Thread(() -> {
                try {
                    body.run();
                } catch (Throwable e) {
                    error.compareAndSet(null, e);
                    barrier.reset();
                }
            }, "power-" + t);
            workers[t].start();
        }
        joinAll(workers);
        rethrow(error);
        return (steps % 2 == 1) ? buf1 : buf0;
    }

    static void powerRow(long[][] A, long[][] cur, long[] out, int i) {
        Arrays.fill(out, 0L);
        long[] a = A[i];
        int n = out.length;
        for (int k = 0; k < a.length; k++) {
            long av = a[k];
            if (av == 0) continue;
            long[] src = cur[k];
            for (int j = 0; j < n; j++) {
                out[j] = (out[j] + av * src[j]) % MOD;
            }
        }
    }

    public static long[][] multiplyMod(long[][] A, long[][] B) {
        int n = A.length, m = B.length, k = B[0].length;
        long[][] C = new long[n][k];
        for (int i = 0; i < n; i++)
            for (int j = 0; j < k; j++) {
                long s = 0;
                for (int t = 0; t < m; t++) {
                    s = (s + Math.floorMod(A[i][t], MOD) * Math.floorMod(B[t][j], MOD)) % MOD;
                }
                C[i][j] = s;
            }
        return C;
    }
    
    public static long[][] generateRandom(int n, int m, long seed) {
        Random r = new Random(seed);
        long[][] mat = new long[n][m];
        for (int i = 0; i < n; i++)
            for (int j = 0; j < m; j++)
                mat[i][j] = r.nextInt(10);
        return mat;
    }

    public static long[][] generateTriangular(int n, long seed) {
        Random r = new Random(seed);
        long[][] mat = new long[n][];
        for (int i = 0; i < n; i++) {
            mat[i] = new long[i + 1];
            for (int j = 0; j <= i; j++) mat[i][j] = r.nextInt(10);
        }
        return mat;
    }

    public static long[][] generateIdentity(int n) {
        long[][] mat = new long[n][n];
        for (int i = 0; i < n; i++) mat[i][i] = 1;
        return mat;
    }

    public static long[][] generateZero(int n, int m) {
        return new long[n][m];
    }

    public static long[][] triangularToDense(long[][] tri) {
        int n = tri.length;
        long[][] d = new long[n][n];
        for (int i = 0; i < n; i++) System.arraycopy(tri[i], 0, d[i], 0, tri[i].length);
        return d;
    }

    public static long checksum(long[][] mat) {
        long sum = 0;
        for (long[] row : mat)
            for (long v : row) sum += v;
        return sum;
    }

    public static long checksumMod(long[][] mat) {
        long sum = 0;
        for (long[] row : mat)
            for (long v : row) sum = (sum + Math.floorMod(v, MOD)) % MOD;
        return sum;
    }

    public static boolean equals(long[][] a, long[][] b) {
        if (a == b) return true;
        if (a == null || b == null || a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) {
            if (!Arrays.equals(a[i], b[i])) return false;
        }
        return true;
    }

    public static String toString(long[][] mat) {
        StringBuilder sb = new StringBuilder();
        for (long[] row : mat) sb.append(Arrays.toString(row)).append('\n');
        return sb.toString();
    }

    private static int rows(long[][] M, String name) {
        if (M == null) throw new IllegalArgumentException("Матрица " + name + " не задана (null)");
        return M.length;
    }

    private static int requireRectangular(long[][] M, String name) {
        if (M.length == 0) return 0;
        if (M[0] == null) throw new IllegalArgumentException("Строка 0 матрицы " + name + " равна null");
        int cols = M[0].length;
        for (int i = 1; i < M.length; i++) {
            if (M[i] == null || M[i].length != cols) {
                throw new IllegalArgumentException(
                        "Матрица " + name + " не прямоугольная: строка " + i + " имеет другую длину");
            }
        }
        return cols;
    }

    private static Runnable guard(Runnable body, AtomicReference<Throwable> error) {
        return () -> {
            try {
                body.run();
            } catch (Throwable e) {
                error.compareAndSet(null, e);
            }
        };
    }

    private static void awaitBarrier(CyclicBarrier barrier) {
        try {
            barrier.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Поток прерван на барьере", e);
        } catch (BrokenBarrierException e) {
            throw new IllegalStateException("Барьер сломан: другой поток завершился с ошибкой", e);
        }
    }

    static void joinAll(Thread[] workers) {
        for (Thread w : workers) {
            boolean interrupted = false;
            while (true) {
                try {
                    w.join();
                    break;
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static void rethrow(AtomicReference<Throwable> error) {
        Throwable e = error.get();
        if (e == null) return;
        if (e instanceof RuntimeException) throw (RuntimeException) e;
        if (e instanceof Error) throw (Error) e;
        throw new IllegalStateException(e);
    }
}
