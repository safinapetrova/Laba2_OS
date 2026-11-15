import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Все вычислительные алгоритмы лабораторной работы в одном месте.
 * Программы (MatrixMul, MatrixMulIPC, MatrixMulPower, Benchmark) и тесты
 * вызывают именно этот код, поэтому тесты проверяют то, что замеряется.
 *
 * Хранение: long[][] — массив строк. Для нижнетреугольной матрицы строка i
 * имеет длину i + 1 (элементы выше диагонали не хранятся).
 */
public final class MatrixMath {

    /** Модуль для возведения в степень (простое число, (MOD-1)^2 + MOD < Long.MAX_VALUE). */
    public static final long MOD = 1_000_000_007L;

    private MatrixMath() {}

    // ======================================================================
    //  Параметры
    // ======================================================================

    /** Способ раздачи строк потокам. */
    public enum Mode {
        /** T непрерывных блоков почти одинакового размера (разница не больше 1 строки). */
        STATIC,
        /** Потоки берут следующую порцию строк из общего атомарного счётчика. */
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

    /** Порядок циклов при вычислении строки результата. */
    public enum LoopOrder {
        /** i → t → j: внутренний цикл идёт подряд по строке B и строке C (кэш-дружелюбно). */
        IKJ,
        /** i → j → t: «школьный» порядок, B читается по столбцу (промахи кэша). */
        IJK;

        public static LoopOrder parse(String s) {
            switch (s.toLowerCase()) {
                case "ikj": case "itj": return IKJ;
                case "ijk": case "ijt": return IJK;
                default: throw new IllegalArgumentException(
                        "Неизвестный порядок циклов: '" + s + "' (ожидается ikj или ijk)");
            }
        }
    }

    /** Режим синхронизации шагов при возведении в степень. */
    public enum PowerSync {
        /** Потоки создаются один раз, шаги разделяются барьером (основной вариант). */
        BARRIER,
        /** Барьер убран — для эксперимента п. 4.1.6. Результат НЕКОРРЕКТЕН. */
        NO_BARRIER,
        /** Потоки создаются заново на каждом шаге; join() играет роль барьера. */
        RESPAWN;

        public static PowerSync parse(String s) {
            switch (s.toLowerCase()) {
                case "barrier":   return BARRIER;
                case "nobarrier": case "no_barrier": case "no-barrier": return NO_BARRIER;
                case "respawn":   return RESPAWN;
                default: throw new IllegalArgumentException(
                        "Неизвестный режим: '" + s + "' (ожидается barrier, nobarrier или respawn)");
            }
        }
    }

    // ======================================================================
    //  Умножение A (n×m) · B (m×k)
    // ======================================================================

    /** Короткая форма для тестов: порядок i→t→j, без счётчика прогресса. */
    public static long[][] multiply(long[][] A, long[][] B, int T, String mode) {
        return multiply(A, B, T, Mode.parse(mode), 1, LoopOrder.IKJ, null);
    }

    /**
     * Многопоточное умножение.
     *
     * @param chunk    размер порции для DYNAMIC (для STATIC игнорируется)
     * @param progress общий счётчик посчитанных строк (может быть null)
     */
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

    /** Короткая форма для тестов. */
    public static long[][] multiplyTriangular(long[][] A, long[][] B, int T, String mode) {
        return multiplyTriangular(A, B, T, Mode.parse(mode), 1, null);
    }

    /**
     * Нижнетреугольная A (n×n, строка i хранит i+1 элементов) · B (n×k).
     * C[i][j] = Σ_{t=0..i} A[i][t]·B[t][j].
     */
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
        // computeRow берёт длину строки A[row] — для треугольной она равна row+1,
        // поэтому код ядра общий с обычным умножением.
        runParallel(n, T, mode, chunk, row -> computeRow(A[row], B, C[row], LoopOrder.IKJ), progress);
        return C;
    }

    /**
     * Ядро: считает одну строку результата c = a · B, используя первые a.length строк B.
     * Каждый поток пишет только в свою строку c, поэтому синхронизация для C не нужна.
     */
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

    /** Эталон для тестов: однопоточное «школьное» умножение, без потоков вообще. */
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

    // ======================================================================
    //  Распределение строк между потоками
    // ======================================================================

    /** Работа над одной строкой. */
    @FunctionalInterface
    interface RowTask {
        void compute(int row);
    }

    /** Начало блока потока t при статическом делении n строк на T потоков. */
    public static int blockStart(int n, int T, int t) {
        int base = n / T;
        int rem = n % T;
        // первые rem потоков получают на одну строку больше
        return t * base + Math.min(t, rem);
    }

    /** Конец (не включительно) блока потока t. */
    public static int blockEnd(int n, int T, int t) {
        return blockStart(n, T, t + 1);
    }

    /**
     * Создаёт T потоков, раздаёт им строки 0..rows-1, ждёт завершения всех (join).
     * Исключение из любого рабочего потока пробрасывается в вызывающий поток.
     */
    static void runParallel(int rows, int T, Mode mode, int chunk, RowTask task, AtomicInteger progress) {
        if (T < 1) throw new IllegalArgumentException("Число потоков должно быть ≥ 1, получено " + T);
        if (mode == null) throw new IllegalArgumentException("Не задан способ распределения");
        if (mode == Mode.DYNAMIC && chunk < 1) {
            throw new IllegalArgumentException("Размер порции должен быть ≥ 1, получено " + chunk);
        }
        AtomicReference<Throwable> error = new AtomicReference<>();
        AtomicInteger nextRow = new AtomicInteger(0); // общий счётчик для DYNAMIC
        Thread[] workers = new Thread[T];

        for (int t = 0; t < T; t++) {
            final int id = t;
            Runnable body;
            if (mode == Mode.STATIC) {
                body = () -> {
                    int from = blockStart(rows, T, id);
                    int to = blockEnd(rows, T, id);   // при T > rows блок может быть пустым
                    for (int i = from; i < to; i++) {
                        task.compute(i);
                        if (progress != null) progress.incrementAndGet();
                    }
                };
            } else {
                body = () -> {
                    while (true) {
                        // getAndAdd атомарен: две нити никогда не получат одну и ту же порцию
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

    // ======================================================================
    //  Возведение в степень
    // ======================================================================

    /** Короткая форма для тестов: с барьером, без прогресса. */
    public static long[][] power(long[][] A, int p, int T) {
        return power(A, p, T, PowerSync.BARRIER, null);
    }

    /**
     * A^p по модулю MOD: p − 1 последовательных умножений.
     *
     * Используется формула R_{s+1} = A · R_s (результат тот же, что у R_s · A,
     * т.к. степени A перестановочны). Строка i нового результата
     *     next[i][j] = Σ_k A[i][k] · cur[k][j]
     * зависит от ВСЕХ строк cur — в том числе посчитанных другими потоками.
     * Поэтому начинать шаг s+1 можно, только когда все потоки закончили шаг s —
     * это и обеспечивает барьер.
     */
    public static long[][] power(long[][] A, int p, int T, PowerSync sync, AtomicInteger progress) {
        int n = rows(A, "A");
        if (n == 0) throw new IllegalArgumentException("Матрица A пустая");
        if (requireRectangular(A, "A") != n) {
            throw new IllegalArgumentException("Возводить в степень можно только квадратную матрицу");
        }
        if (p < 1) throw new IllegalArgumentException("Степень p должна быть ≥ 1, получено " + p);
        if (T < 1) throw new IllegalArgumentException("Число потоков должно быть ≥ 1, получено " + T);

        long[][] base = new long[n][n];          // A, приведённая по модулю (только чтение)
        long[][] buf0 = new long[n][n];          // R_1 = A
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
                // join() внутри runParallel — естественная точка синхронизации между шагами
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
                // Локальные ссылки: у каждого потока своя пара, меняются одинаково у всех.
                long[][] cur = buf0;   // только чтение на этом шаге
                long[][] next = buf1;  // только запись (и только свои строки)
                for (int s = 0; s < steps; s++) {
                    for (int i = from; i < to; i++) {
                        powerRow(base, cur, next[i], i);
                        if (progress != null) progress.incrementAndGet();
                    }
                    if (useBarrier) {
                        // Все потоки дописали next и больше не читают cur.
                        // Поток с пустым блоком (T > n) тоже обязан дойти сюда,
                        // иначе остальные будут ждать вечно.
                        awaitBarrier(barrier);
                    }
                    // Менять буферы безопасно только ПОСЛЕ барьера:
                    // next полностью готов, а в cur уже никто не читает.
                    long[][] tmp = cur; cur = next; next = tmp;
                }
            };
            workers[t] = new Thread(() -> {
                try {
                    body.run();
                } catch (Throwable e) {
                    error.compareAndSet(null, e);
                    // «Ломаем» барьер, чтобы остальные потоки не ждали упавший вечно
                    barrier.reset();
                }
            }, "power-" + t);
            workers[t].start();
        }
        joinAll(workers);
        rethrow(error);
        // после steps обменов результат лежит в buf1, если steps нечётно, иначе в buf0
        return (steps % 2 == 1) ? buf1 : buf0;
    }

    /**
     * out = (строка i матрицы A) · cur  (mod MOD).
     * Строка out целиком перезаписывается, поэтому отдельный проход обнуления
     * и второй барьер не нужны.
     */
    static void powerRow(long[][] A, long[][] cur, long[] out, int i) {
        Arrays.fill(out, 0L);
        long[] a = A[i];
        int n = out.length;
        for (int k = 0; k < a.length; k++) {
            long av = a[k];
            if (av == 0) continue;
            long[] src = cur[k];
            for (int j = 0; j < n; j++) {
                // av < MOD, src[j] < MOD, out[j] < MOD → сумма < 2^63, переполнения нет.
                // Остаток берётся после каждого сложения, как требует задание.
                out[j] = (out[j] + av * src[j]) % MOD;
            }
        }
    }

    /** Эталон для тестов: однопоточное умножение по модулю. */
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

    // ======================================================================
    //  Генерация и вспомогательные функции
    // ======================================================================

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

    /** Треугольная (ступенчатая) → обычная квадратная с нулями над диагональю. */
    public static long[][] triangularToDense(long[][] tri) {
        int n = tri.length;
        long[][] d = new long[n][n];
        for (int i = 0; i < n; i++) System.arraycopy(tri[i], 0, d[i], 0, tri[i].length);
        return d;
    }

    /** Сумма всех элементов (работает и для ступенчатых массивов). */
    public static long checksum(long[][] mat) {
        long sum = 0;
        for (long[] row : mat)
            for (long v : row) sum += v;
        return sum;
    }

    /** Сумма всех элементов по модулю MOD. */
    public static long checksumMod(long[][] mat) {
        long sum = 0;
        for (long[] row : mat)
            for (long v : row) sum = (sum + Math.floorMod(v, MOD)) % MOD;
        return sum;
    }

    /** Поэлементное сравнение, корректно для ступенчатых массивов. */
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

    // ---------------------------------------------------------------------- проверки

    private static int rows(long[][] M, String name) {
        if (M == null) throw new IllegalArgumentException("Матрица " + name + " не задана (null)");
        return M.length;
    }

    /** Проверяет, что все строки одной длины, и возвращает число столбцов. */
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

    // ---------------------------------------------------------------------- потоки

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
                    interrupted = true; // дождёмся всё равно: результат нужен целиком
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
