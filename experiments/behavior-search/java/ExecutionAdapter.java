package experiment.refunds;

/** Narrow experimental boundary: one operation and direct state observations. */
public interface ExecutionAdapter {
    String apply(String operation, int step);

    String observe();
}
