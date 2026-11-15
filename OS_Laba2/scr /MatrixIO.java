import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Двоичный протокол передачи матриц между процессом-генератором и вычислителем
 * через анонимный канал (pipe): stdout генератора → stdin-поток вычислителя.
 *
 * Формат (big-endian, как у DataOutputStream):
 * <pre>
 *   int     MAGIC        0x4D41544D ("MATM") — защита от чтения «мусора»
 *   int     VERSION
 *   boolean triangular
 *   long    genNanos     сколько генератор считал случайные числа
 *   matrix  A
 *   matrix  B
 *   int     END_MAGIC    0x454E4421 ("END!") — признак, что передано всё
 *
 *   matrix := int rows, затем для каждой строки: int len, len × long
 * </pre>
 * Длина каждой строки передаётся явно, поэтому один и тот же формат подходит
 * и для прямоугольной, и для треугольной (ступенчатой) матрицы.
 */
public final class MatrixIO {
    public static final int MAGIC = 0x4D41544D;
    public static final int END_MAGIC = 0x454E4421;
    public static final int VERSION = 1;

    /** Защита от гигантских аллокаций при повреждённом потоке. */
    private static final int MAX_DIM = 1 << 20;

    private MatrixIO() {}

    /** Содержимое одного сообщения. */
    public static final class Payload {
        public final boolean triangular;
        public final long genNanos;
        public final long[][] A;
        public final long[][] B;

        public Payload(boolean triangular, long genNanos, long[][] A, long[][] B) {
            this.triangular = triangular;
            this.genNanos = genNanos;
            this.A = A;
            this.B = B;
        }
    }

    /** Результат получения матриц от процесса-генератора, с замерами времени. */
    public static final class Received {
        public final Payload payload;
        /** От запуска процесса до получения заголовка: старт JVM генератора + генерация. */
        public final long startupAndGenNanos;
        /** От получения заголовка до последнего байта: чистая передача через канал + разбор. */
        public final long transferNanos;
        public final long bytes;

        Received(Payload payload, long startupAndGenNanos, long transferNanos, long bytes) {
            this.payload = payload;
            this.startupAndGenNanos = startupAndGenNanos;
            this.transferNanos = transferNanos;
            this.bytes = bytes;
        }
    }

    // ------------------------------------------------------------------ запись

    public static void write(DataOutputStream out, Payload p) throws IOException {
        out.writeInt(MAGIC);
        out.writeInt(VERSION);
        out.writeBoolean(p.triangular);
        out.writeLong(p.genNanos);
        writeMatrix(out, p.A);
        writeMatrix(out, p.B);
        out.writeInt(END_MAGIC);
        out.flush();
    }

    static void writeMatrix(DataOutputStream out, long[][] M) throws IOException {
        out.writeInt(M.length);
        byte[] bytes = new byte[0];
        for (long[] row : M) {
            out.writeInt(row.length);
            int size = row.length * Long.BYTES;
            if (bytes.length < size) bytes = new byte[size];
            // пакетное преобразование строки в байты вместо поэлементного writeLong
            ByteBuffer.wrap(bytes, 0, size).asLongBuffer().put(row);
            out.write(bytes, 0, size);
        }
    }

    /** Сколько байт займёт сообщение (для отчёта). */
    public static long messageSize(long[][] A, long[][] B) {
        return 4 + 4 + 1 + 8 + matrixSize(A) + matrixSize(B) + 4;
    }

    private static long matrixSize(long[][] M) {
        long s = 4;
        for (long[] row : M) s += 4 + (long) row.length * Long.BYTES;
        return s;
    }

    // ------------------------------------------------------------------ чтение

    public static Payload read(DataInputStream in) throws IOException {
        return readBody(in, readHeader(in));
    }

    private static final class Header {
        final boolean triangular;
        final long genNanos;

        Header(boolean triangular, long genNanos) {
            this.triangular = triangular;
            this.genNanos = genNanos;
        }
    }

    private static Header readHeader(DataInputStream in) throws IOException {
        int magic = in.readInt();
        if (magic != MAGIC) {
            throw new IOException(String.format(
                    "Неверная сигнатура потока: 0x%08X (ожидалось 0x%08X) — это не данные генератора", magic, MAGIC));
        }
        int version = in.readInt();
        if (version != VERSION) throw new IOException("Неподдерживаемая версия протокола: " + version);
        boolean triangular = in.readBoolean();
        long genNanos = in.readLong();
        return new Header(triangular, genNanos);
    }

    private static Payload readBody(DataInputStream in, Header h) throws IOException {
        long[][] A = readMatrix(in, "A");
        long[][] B = readMatrix(in, "B");
        int end;
        try {
            end = in.readInt();
        } catch (EOFException e) {
            throw new IOException("Поток оборвался до маркера конца — матрицы переданы не полностью", e);
        }
        if (end != END_MAGIC) throw new IOException("Нет маркера конца сообщения — данные повреждены");
        return new Payload(h.triangular, h.genNanos, A, B);
    }

    static long[][] readMatrix(DataInputStream in, String name) throws IOException {
        int rows = in.readInt();
        checkDim(rows, name + ": число строк");
        long[][] M = new long[rows][];
        byte[] bytes = new byte[0];
        for (int i = 0; i < rows; i++) {
            int len = in.readInt();
            checkDim(len, name + ": длина строки " + i);
            int size = len * Long.BYTES;
            if (bytes.length < size) bytes = new byte[size];
            // readFully блокируется, пока не придут ВСЕ size байт (или бросает EOFException)
            in.readFully(bytes, 0, size);
            M[i] = new long[len];
            ByteBuffer.wrap(bytes, 0, size).asLongBuffer().get(M[i]);
        }
        return M;
    }

    private static void checkDim(int v, String what) throws IOException {
        if (v < 0 || v > MAX_DIM) throw new IOException("Недопустимое значение (" + what + "): " + v);
    }

    // ------------------------------------------------------------------ запуск генератора

    /** Путь к java той же JVM, в которой работает вычислитель (а не первой из PATH). */
    static String javaExecutable() {
        return System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
    }

    /**
     * Каталог (или jar), откуда загружен класс MatrixGenerator. Надёжнее, чем
     * java.class.path: работает и при запуске из JUnit-лаунчера.
     */
    static String classPathOfGenerator() {
        try {
            return new File(MatrixGenerator.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                    .getAbsolutePath();
        } catch (Exception e) {
            return System.getProperty("java.class.path");
        }
    }

    /**
     * Запускает дочерний процесс MatrixGenerator и читает из его stdout матрицы.
     * Чтение идёт ДО waitFor(): если сначала ждать завершения, генератор
     * заблокируется на переполненном канале (буфер pipe ограничен) — взаимная блокировка.
     */
    public static Received fetchFromGenerator(int n, int m, int k, boolean triangular)
            throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add(javaExecutable());
        cmd.add("-cp");
        cmd.add(classPathOfGenerator());
        cmd.add("MatrixGenerator");
        cmd.add(String.valueOf(n));
        cmd.add(String.valueOf(m));
        cmd.add(String.valueOf(k));
        if (triangular) cmd.add("triangular");

        ProcessBuilder pb = new ProcessBuilder(cmd);
        // stderr генератора — в консоль, а НЕ в канал с двоичными данными
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);

        long t0 = System.nanoTime();
        Process proc = pb.start();
        Payload payload;
        long tHeader, tEnd;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(proc.getInputStream(), 1 << 16))) {
            Header h = readHeader(in);      // генератор пишет заголовок только после генерации
            tHeader = System.nanoTime();
            payload = readBody(in, h);
            tEnd = System.nanoTime();
        } catch (IOException e) {
            proc.destroy();
            throw e;
        }
        int code = proc.waitFor();
        if (code != 0) throw new IOException("Генератор завершился с кодом " + code);
        return new Received(payload, tHeader - t0, tEnd - tHeader, messageSize(payload.A, payload.B));
    }
}
