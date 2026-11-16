final class Cli {
    private Cli() {}

    static int positiveInt(String s, String name) {
        int v;
        try {
            v = Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " должно быть целым числом, получено '" + s + "'");
        }
        if (v < 1) throw new IllegalArgumentException(name + " должно быть ≥ 1, получено " + v);
        return v;
    }

    static void fail(String message, String usage) {
        System.err.println("Ошибка: " + message);
        System.err.println(usage);
        System.exit(2);
    }

    static void warmUpMultiply(MatrixMath.LoopOrder order) {
        long[][] a = MatrixMath.generateRandom(160, 160, 1);
        long[][] b = MatrixMath.generateRandom(160, 160, 2);
        long[][] tri = MatrixMath.generateTriangular(160, 3);
        long sink = 0;
        for (int r = 0; r < 15; r++) {
            sink += MatrixMath.checksum(MatrixMath.multiply(a, b, 2, MatrixMath.Mode.STATIC, 1, order, null));
            sink += MatrixMath.checksum(MatrixMath.multiply(a, b, 2, MatrixMath.Mode.DYNAMIC, 1, order, null));
            sink += MatrixMath.checksum(MatrixMath.multiplyTriangular(tri, b, 2, MatrixMath.Mode.DYNAMIC, 1, null));
        }
        if (sink == 42) System.out.print("");
    }

    static void warmUpPower() {
        long[][] a = MatrixMath.generateRandom(120, 120, 1);
        long sink = 0;
        for (int r = 0; r < 10; r++) {
            sink += MatrixMath.checksumMod(MatrixMath.power(a, 4, 2));
        }
        if (sink == 42) System.out.print("");
    }

    static double sec(long nanos) {
        return nanos / 1e9;
    }
}

