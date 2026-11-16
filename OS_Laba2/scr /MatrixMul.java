import java.util.concurrent.atomic.AtomicInteger;

public class MatrixMul {
    static final String USAGE =
            "Использование: java MatrixMul <n> <m> <k> <T> [ikj|ijk]\n"
                    + "  n, m, k - A имеет размер n×m, B — m×k\n"
                    + "  T       - число потоков (≥ 1)\n"
                    + "  ikj     - порядок циклов i→t→j (по умолчанию), ijk — «школьный» i→j→t";

    public static void main(String[] args) {
        if (args.length < 4 || args.length > 5) Cli.fail("неверное число аргументов", USAGE);
        int n = 0, m = 0, k = 0, T = 0;
        MatrixMath.LoopOrder order = MatrixMath.LoopOrder.IKJ;
        try {
            n = Cli.positiveInt(args[0], "n");
            m = Cli.positiveInt(args[1], "m");
            k = Cli.positiveInt(args[2], "k");
            T = Cli.positiveInt(args[3], "T");
            if (args.length == 5) order = MatrixMath.LoopOrder.parse(args[4]);
        } catch (IllegalArgumentException e) {
            Cli.fail(e.getMessage(), USAGE);
        }

        System.out.printf("Умножение матриц: A(%dx%d) * B(%dx%d), потоков: %d, порядок циклов: %s%n",
                n, m, m, k, T, order == MatrixMath.LoopOrder.IKJ ? "i→t→j" : "i→j→t");

        long[][] A = MatrixMath.generateRandom(n, m, 42);
        long[][] B = MatrixMath.generateRandom(m, k, 43);

        Cli.warmUpMultiply(order);

        AtomicInteger done = new AtomicInteger();
        long[][] C;
        long start, end;
        try (ProgressPrinter pp = new ProgressPrinter(done, n)) {
            start = System.nanoTime();
            C = MatrixMath.multiply(A, B, T, MatrixMath.Mode.STATIC, 1, order, done);
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

