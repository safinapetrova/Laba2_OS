import java.util.concurrent.atomic.AtomicInteger;

/**
 * Задание на «4»: процесс-вычислитель. Запускает дочерний процесс MatrixGenerator,
 * получает матрицы через канал (pipe), умножает их многопоточно (static/dynamic),
 * поддерживает нижнетреугольную A.
 *
 *   java -cp out MatrixMulIPC 2000 2000 2000 8 dynamic
 *   java -cp out MatrixMulIPC 2000 2000 2000 8 static triangular
 *   java -cp out MatrixMulIPC 2000 2000 2000 8 dynamic triangular chunk=8
 */
public class MatrixMulIPC {
    static final String USAGE =
            "Использование: java MatrixMulIPC <n> <m> <k> <T> [static|dynamic] [triangular] [chunk=N]\n"
                    + "  n, m, k    - A имеет размер n×m, B — m×k (для triangular нужно m == n)\n"
                    + "  T          - число потоков (≥ 1)\n"
                    + "  static     - поровну непрерывными блоками (по умолчанию)\n"
                    + "  dynamic    - потоки берут строки из общего атомарного счётчика\n"
                    + "  triangular - A нижнетреугольная, строка i хранит i+1 элементов\n"
                    + "  chunk=N    - размер порции для dynamic (по умолчанию 1)";

    public static void main(String[] args) {
        if (args.length < 4) Cli.fail("неверное число аргументов", USAGE);
        int n = 0, m = 0, k = 0, T = 0, chunk = 1;
        MatrixMath.Mode mode = MatrixMath.Mode.STATIC;
        boolean triangular = false;
        try {
            n = Cli.positiveInt(args[0], "n");
            m = Cli.positiveInt(args[1], "m");
            k = Cli.positiveInt(args[2], "k");
            T = Cli.positiveInt(args[3], "T");
            for (int i = 4; i < args.length; i++) {
                String a = args[i].toLowerCase();
                if (a.equals("triangular")) triangular = true;
                else if (a.startsWith("chunk=")) chunk = Cli.positiveInt(a.substring(6), "chunk");
                else mode = MatrixMath.Mode.parse(a);   // бросит исключение на опечатку
            }
            if (triangular && m != n) {
                throw new IllegalArgumentException("для треугольной матрицы A нужно m == n (получено n=" + n + ", m=" + m + ")");
            }
        } catch (IllegalArgumentException e) {
            Cli.fail(e.getMessage(), USAGE);
        }

        System.out.printf("A(%dx%d)%s * B(%dx%d), потоков: %d, распределение: %s%s%n",
                n, m, triangular ? " нижнетреугольная" : "", m, k, T,
                mode.name().toLowerCase(), mode == MatrixMath.Mode.DYNAMIC ? " (порция " + chunk + ")" : "");

        MatrixIO.Received rec;
        try {
            rec = MatrixIO.fetchFromGenerator(n, m, k, triangular);
        } catch (Exception e) {
            System.err.println("Не удалось получить матрицы от генератора: " + e.getMessage());
            System.exit(1);
            return;
        }
        long[][] A = rec.payload.A;
        long[][] B = rec.payload.B;

        System.out.printf("Запуск генератора + генерация: %.3f сек (из них генерация: %.3f сек)%n",
                Cli.sec(rec.startupAndGenNanos), Cli.sec(rec.payload.genNanos));
        System.out.printf("Передача через канал: %.3f сек, %.1f МБ (%.0f МБ/с)%n",
                Cli.sec(rec.transferNanos), rec.bytes / 1e6, rec.bytes / 1e6 / Math.max(1e-9, Cli.sec(rec.transferNanos)));

        Cli.warmUpMultiply(MatrixMath.LoopOrder.IKJ);

        AtomicInteger done = new AtomicInteger();
        long[][] C;
        long start, end;
        try (ProgressPrinter pp = new ProgressPrinter(done, n)) {
            start = System.nanoTime();
            C = triangular
                    ? MatrixMath.multiplyTriangular(A, B, T, mode, chunk, done)
                    : MatrixMath.multiply(A, B, T, mode, chunk, MatrixMath.LoopOrder.IKJ, done);
            end = System.nanoTime();
        }

        if (n <= 10 && k <= 10) {
            System.out.println("Матрица C:");
            System.out.print(MatrixMath.toString(C));
        }
        System.out.printf("Время умножения: %.4f сек%n", Cli.sec(end - start));
        System.out.printf("Контрольная сумма: %d%n", MatrixMath.checksum(C));
    }
}
