import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;

/**
 * Процесс-генератор (задание на «4»). Генерирует матрицы и пишет их в stdout
 * в двоичном формате MatrixIO. В stdout не выводится НИЧЕГО, кроме данных;
 * все сообщения — только в stderr.
 *
 *   java -cp out MatrixGenerator <n> <m> <k> [triangular]
 */
public class MatrixGenerator {
    public static void main(String[] args) {
        String usage = "Использование: java MatrixGenerator <n> <m> <k> [triangular]";
        if (args.length < 3 || args.length > 4) {
            System.err.println(usage);
            System.exit(1);
        }
        try {
            int n = Cli.positiveInt(args[0], "n");
            int m = Cli.positiveInt(args[1], "m");
            int k = Cli.positiveInt(args[2], "k");
            boolean triangular = false;
            if (args.length == 4) {
                if (!args[3].equals("triangular")) throw new IllegalArgumentException("неизвестный флаг " + args[3]);
                triangular = true;
            }
            if (triangular && m != n) {
                throw new IllegalArgumentException("для треугольной A нужно m == n");
            }

            // Сначала генерируем всё, потом передаём — так вычислитель может
            // отдельно измерить время генерации и время передачи.
            long t0 = System.nanoTime();
            long[][] A = triangular ? MatrixMath.generateTriangular(n, 42) : MatrixMath.generateRandom(n, m, 42);
            long[][] B = MatrixMath.generateRandom(m, k, 43);
            long genNanos = System.nanoTime() - t0;

            // FileOutputStream(FileDescriptor.out) — «сырой» stdout без PrintStream и перекодировки
            try (DataOutputStream out = new DataOutputStream(
                    new BufferedOutputStream(new FileOutputStream(FileDescriptor.out), 1 << 16))) {
                MatrixIO.write(out, new MatrixIO.Payload(triangular, genNanos, A, B));
            }
        } catch (Exception e) {
            System.err.println("Генератор: ошибка: " + e.getMessage());
            System.exit(1);
        }
    }
}
