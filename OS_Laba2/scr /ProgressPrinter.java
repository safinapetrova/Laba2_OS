import java.io.PrintStream;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Отдельный поток, который раз в 200 мс читает общий счётчик готовых строк
 * и печатает процент, только если он изменился.
 *
 * Рабочие потоки увеличивают счётчик через AtomicInteger.incrementAndGet() —
 * это атомарная операция (CAS / lock xadd). Без синхронизации обычное
 * counter++ = «прочитать, прибавить, записать», и два потока могут прочитать
 * одно и то же значение: одно из увеличений потеряется, и в конце прогресс
 * покажет меньше 100%.
 *
 * Использование:
 * <pre>
 *   try (ProgressPrinter pp = new ProgressPrinter(counter, total)) { ...работа... }
 * </pre>
 * close() останавливает поток, ДОЖИДАЕТСЯ его (join), и только потом печатает
 * итоговое значение — так «99%» не может появиться после «100%».
 */
public final class ProgressPrinter implements AutoCloseable {
    private static final long INTERVAL_MS = 200;

    private final AtomicInteger counter;
    private final long total;
    private final PrintStream out;
    private final Thread thread;

    public ProgressPrinter(AtomicInteger counter, long total) {
        this(counter, total, System.out);
    }

    public ProgressPrinter(AtomicInteger counter, long total, PrintStream out) {
        if (total <= 0) throw new IllegalArgumentException("total должен быть > 0");
        this.counter = counter;
        this.total = total;
        this.out = out;
        this.thread = new Thread(this::loop, "progress");
        this.thread.setDaemon(true);
        this.thread.start();
    }

    /** Процент выполненной работы (целый, округление вниз). long — чтобы не было переполнения done*100. */
    public static int percent(long done, long total) {
        return (int) (done * 100 / total);
    }

    private void loop() {
        int last = -1;
        while (!Thread.currentThread().isInterrupted()) {
            int pct = percent(counter.get(), total);
            if (pct != last) {
                out.print("\rПрогресс: " + pct + "%");
                out.flush();
                last = pct;
            }
            try {
                Thread.sleep(INTERVAL_MS);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    @Override
    public void close() {
        thread.interrupt();
        try {
            thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // Итог печатается из реального значения счётчика, а не константой «100%»
        out.println("\rПрогресс: " + percent(counter.get(), total) + "%");
    }
}
