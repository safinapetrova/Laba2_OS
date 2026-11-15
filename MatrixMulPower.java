import java.util.concurrent.atomic.AtomicInteger;

/**
 * Задание на «5»: A^p по модулю 1 000 000 007 в T потоков.
 * Потоки создаются один раз, шаги разделяются барьером (CyclicBarrier).
 *
 *   java -cp out MatrixMulPower 1000 10 8              — с барьером
 *   java -cp out MatrixMulPower 1000 10 8 nobarrier    — эксперимент п. 4.1.6
 *   java -cp out MatrixMulPower 1000 10 8 respawn      — потоки заново на каждом шаге
 */
public class MatrixMulPower {
    static final String USAGE =
            "Использование: java MatrixMulPower <n> <p> <T> [barrier|nobarrier|respawn]\n"
                    + "  n - размер квадратной матрицы\n"
                    + "  p - степень (A^p), p ≥ 1\n"
                    + "  T - число потоков (≥ 1)\n"
                    + "  barrier   - потоки создаются один раз, синхронизация барьером (по умолчанию)\n"
                    + "  nobarrier - барьер убран (результат некорректен — для отчёта)\n"
                    + "  respawn   - новые потоки на каждом шаге (для сравнения времени)";

    public static void main(String[] args) {
        if (args.length < 3 || args.length > 4) Cli.fail("неверное число аргументов", USAGE);
        int n = 0, p = 0, T = 0;
        MatrixMath.PowerSync sync = MatrixMath.PowerSync.BARRIER;
        try {
            n = Cli.positiveInt(args[0], "n");
            p = Cli.positiveInt(args[1], "p");
            T = Cli.positiveInt(args[2], "T");
            if (args.length == 4) sync = MatrixMath.PowerSync.parse(args[3]);
        } catch (IllegalArgumentException e) {
            Cli.fail(e.getMessage(), USAGE);
        }

        System.out.printf("Возведение матрицы A(%dx%d) в степень %d, потоков: %d, режим: %s%n",
                n, n, p, T, sync.name().toLowerCase());
        System.out.printf("Вычисления по модулю %d%n", MatrixMath.MOD);

        long[][] A = MatrixMath.generateRandom(n, n, 42);
        Cli.warmUpPower();

        AtomicInteger done = new AtomicInteger();
        long total = (long) (p - 1) * n;
        long[][] R;
        long start, end;
        if (total == 0) {                      // p == 1: A^1 = A, умножений нет
            start = System.nanoTime();
            R = MatrixMath.power(A, 1, T, sync, done);
            end = System.nanoTime();
            System.out.println("Прогресс: 100%");
        } else {
            try (ProgressPrinter pp = new ProgressPrinter(done, total)) {
                start = System.nanoTime();
                R = MatrixMath.power(A, p, T, sync, done);
                end = System.nanoTime();
            }
        }

        if (n <= 10) {
            System.out.println("Матрица A^" + p + " (mod " + MatrixMath.MOD + "):");
            System.out.print(MatrixMath.toString(R));
        }
        System.out.printf("Время возведения в степень: %.4f сек%n", Cli.sec(end - start));
        System.out.printf("Контрольная сумма (mod %d): %d%n", MatrixMath.MOD, MatrixMath.checksumMod(R));
    }
}

