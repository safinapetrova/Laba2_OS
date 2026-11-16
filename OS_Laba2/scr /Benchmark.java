import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public class Benchmark {
    private static int runs = 5;
    private static File outDir = new File("results");
    private static int[] threads;
    private static long sink;   // не даём JIT выбросить результат

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Использование: java Benchmark <mul|order|tri|ipc|pow> <параметры> [threads=..] [runs=5] [out=results]");
            System.exit(2);
        }
        int logical = Runtime.getRuntime().availableProcessors();
        threads = defaultThreads(logical);
        List<String> pos = new ArrayList<>();
        for (String a : args) {
            if (a.startsWith("threads=")) threads = parseInts(a.substring(8));
            else if (a.startsWith("runs=")) runs = Integer.parseInt(a.substring(5));
            else if (a.startsWith("out=")) outDir = new File(a.substring(4));
            else pos.add(a);
        }
        if (!outDir.exists() && !outDir.mkdirs()) throw new IOException("Не удалось создать " + outDir);
        System.out.printf("Логических процессоров (по данным JVM): %d; потоки: %s; замеров на точку: %d%n",
                logical, Arrays.toString(threads), runs);

        Cli.warmUpMultiply(MatrixMath.LoopOrder.IKJ);
        Cli.warmUpMultiply(MatrixMath.LoopOrder.IJK);

        switch (pos.get(0)) {
            case "mul":   benchMul(parseInts(pos.get(1))); break;
            case "order": benchOrder(Integer.parseInt(pos.get(1))); break;
            case "tri":   benchTri(parseInts(pos.get(1))); break;
            case "ipc":   benchIpc(parseInts(pos.get(1)), logical); break;
            case "pow":   benchPow(Integer.parseInt(pos.get(1)), Integer.parseInt(pos.get(2))); break;
            default: throw new IllegalArgumentException("Неизвестный эксперимент: " + pos.get(0));
        }
        if (sink == 42) System.out.println();
    }

    static int[] defaultThreads(int L) {
        List<Integer> t = new ArrayList<>();
        for (int i = 1; i <= L; i++) t.add(i);
        int step = Math.max(1, L / 4);
        for (int i = L + step; i <= 2 * L; i += step) t.add(i);
        if (!t.contains(2 * L)) t.add(2 * L);
        t.add(4 * L);
        t.add(8 * L);
        return t.stream().mapToInt(Integer::intValue).toArray();
    }

    private interface Job {
        long[][] run();
    }

    private static void benchMul(int[] sizes) throws IOException {
        try (PrintWriter csv = csv("mul.csv", "size,threads," + runHeader() + ",mean_s,speedup,efficiency")) {
            for (int n : sizes) {
                long[][] A = MatrixMath.generateRandom(n, n, 42);
                long[][] B = MatrixMath.generateRandom(n, n, 43);
                double t1 = 0;
                for (int T : threads) {
                    double[] r = measure(() -> MatrixMath.multiply(A, B, T, MatrixMath.Mode.STATIC, 1,
                            MatrixMath.LoopOrder.IKJ, null));
                    double mean = trimmedMean(r);
                    if (T == 1) t1 = mean;
                    double s = t1 > 0 ? t1 / mean : Double.NaN;
                    row(csv, n + "," + T + "," + join(r) + "," + f(mean) + "," + f(s) + "," + f(s / T));
                    System.out.printf("mul n=%d T=%d: %.4f с, S=%.2f, E=%.2f%n", n, T, mean, s, s / T);
                }
            }
        }
    }

    private static void benchOrder(int n) throws IOException {
        long[][] A = MatrixMath.generateRandom(n, n, 42);
        long[][] B = MatrixMath.generateRandom(n, n, 43);
        try (PrintWriter csv = csv("order.csv", "size,order," + runHeader() + ",mean_s")) {
            for (MatrixMath.LoopOrder o : new MatrixMath.LoopOrder[]{MatrixMath.LoopOrder.IJK, MatrixMath.LoopOrder.IKJ}) {
                double[] r = measure(() -> MatrixMath.multiply(A, B, 1, MatrixMath.Mode.STATIC, 1, o, null));
                String name = o == MatrixMath.LoopOrder.IKJ ? "i-t-j" : "i-j-t";
                row(csv, n + "," + name + "," + join(r) + "," + f(trimmedMean(r)));
                System.out.printf("order n=%d %s: %.4f с%n", n, name, trimmedMean(r));
            }
        }
    }

    private static void benchTri(int[] sizes) throws IOException {
        String[] modes = {"static", "dynamic", "dynamic8", "dynamic64"};
        try (PrintWriter csv = csv("tri.csv", "size,mode,threads," + runHeader() + ",mean_s")) {
            for (int n : sizes) {
                long[][] A = MatrixMath.generateTriangular(n, 42);
                long[][] B = MatrixMath.generateRandom(n, n, 43);
                for (String m : modes) {
                    MatrixMath.Mode mode = m.equals("static") ? MatrixMath.Mode.STATIC : MatrixMath.Mode.DYNAMIC;
                    int chunk = m.equals("dynamic8") ? 8 : m.equals("dynamic64") ? 64 : 1;
                    for (int T : threads) {
                        double[] r = measure(() -> MatrixMath.multiplyTriangular(A, B, T, mode, chunk, null));
                        row(csv, n + "," + m + "," + T + "," + join(r) + "," + f(trimmedMean(r)));
                        System.out.printf("tri n=%d %s T=%d: %.4f с%n", n, m, T, trimmedMean(r));
                    }
                }
            }
        }
    }

    private static void benchIpc(int[] sizes, int T) throws Exception {
        try (PrintWriter csv = csv("ipc.csv",
                "size,threads,bytes,startup_gen_s,gen_s,transfer_s,multiply_s")) {
            for (int n : sizes) {
                double[] startup = new double[runs], gen = new double[runs], transfer = new double[runs], mul = new double[runs];
                long bytes = 0;
                for (int i = 0; i < runs; i++) {
                    MatrixIO.Received rec = MatrixIO.fetchFromGenerator(n, n, n, false);
                    bytes = rec.bytes;
                    startup[i] = Cli.sec(rec.startupAndGenNanos);
                    gen[i] = Cli.sec(rec.payload.genNanos);
                    transfer[i] = Cli.sec(rec.transferNanos);
                    long[][] A = rec.payload.A, B = rec.payload.B;
                    System.gc();
                    long s = System.nanoTime();
                    long[][] C = MatrixMath.multiply(A, B, T, MatrixMath.Mode.STATIC, 1, MatrixMath.LoopOrder.IKJ, null);
                    mul[i] = Cli.sec(System.nanoTime() - s);
                    sink += C[0][0];
                }
                row(csv, n + "," + T + "," + bytes + "," + f(trimmedMean(startup)) + "," + f(trimmedMean(gen)) + ","
                        + f(trimmedMean(transfer)) + "," + f(trimmedMean(mul)));
                System.out.printf("ipc n=%d: передача %.4f с (%.1f МБ), умножение %.4f с%n",
                        n, trimmedMean(transfer), bytes / 1e6, trimmedMean(mul));
            }
        }
    }

    private static void benchPow(int n, int p) throws IOException {
        Cli.warmUpPower();
        long[][] A = MatrixMath.generateRandom(n, n, 42);
        try (PrintWriter csv = csv("pow.csv", "size,p,mode,threads," + runHeader() + ",mean_s,speedup,efficiency")) {
            for (MatrixMath.PowerSync sync : new MatrixMath.PowerSync[]{MatrixMath.PowerSync.BARRIER, MatrixMath.PowerSync.RESPAWN}) {
                double t1 = 0;
                for (int T : threads) {
                    double[] r = measure(() -> MatrixMath.power(A, p, T, sync, null));
                    double mean = trimmedMean(r);
                    if (T == 1) t1 = mean;
                    double s = t1 > 0 ? t1 / mean : Double.NaN;
                    String m = sync.name().toLowerCase();
                    row(csv, n + "," + p + "," + m + "," + T + "," + join(r) + "," + f(mean) + "," + f(s) + "," + f(s / T));
                    System.out.printf("pow n=%d p=%d %s T=%d: %.4f с, S=%.2f%n", n, p, m, T, mean, s);
                }
            }
        }
    }

    private static double[] measure(Job job) {
        double[] r = new double[runs];
        for (int i = 0; i < runs; i++) {
            System.gc();                        
            long s = System.nanoTime();
            long[][] C = job.run();
            r[i] = Cli.sec(System.nanoTime() - s);
            sink += C[C.length - 1][0];
        }
        return r;
    }

    static double trimmedMean(double[] r) {
        double[] s = r.clone();
        Arrays.sort(s);
        int from = s.length >= 3 ? 1 : 0, to = s.length >= 3 ? s.length - 1 : s.length;
        double sum = 0;
        for (int i = from; i < to; i++) sum += s[i];
        return sum / (to - from);
    }

    private static PrintWriter csv(String name, String header) throws IOException {
        File f = new File(outDir, name);
        PrintWriter w = new PrintWriter(f, StandardCharsets.UTF_8.name());
        w.println(header);
        System.out.println("Запись в " + f.getPath());
        return w;
    }

    private static void row(PrintWriter w, String line) {
        w.println(line);
        w.flush();  
    }

    private static String runHeader() {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= runs; i++) sb.append(i > 1 ? "," : "").append("run").append(i);
        return sb.toString();
    }

    private static String join(double[] r) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < r.length; i++) sb.append(i > 0 ? "," : "").append(f(r[i]));
        return sb.toString();
    }

    private static String f(double v) {
        return String.format(Locale.ROOT, "%.6f", v);
    }

    private static int[] parseInts(String s) {
        return Arrays.stream(s.split(",")).map(String::trim).mapToInt(Integer::parseInt).toArray();
    }
}

