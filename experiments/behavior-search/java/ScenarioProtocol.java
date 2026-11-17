package experiment.refunds;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.function.LongFunction;

/** Shared bounded transport; operation semantics belong to adapters. */
public final class ScenarioProtocol {
    private static final int MAX_OPERATIONS = 16;
    private static final int MAX_LINE = 1024;

    private ScenarioProtocol() {}

    public static void run(LongFunction<ExecutionAdapter> reset) throws IOException {
        try (var input =
                new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = readBoundedLine(input)) != null) {
                try {
                    System.out.println(execute(line, reset));
                } catch (RuntimeException executionFailure) {
                    System.out.println("execution_error\t" + executionFailure.getClass().getName());
                }
            }
        }
    }

    private static String readBoundedLine(BufferedReader input) throws IOException {
        var line = new StringBuilder();
        int value;
        while ((value = input.read()) != -1 && value != '\n') {
            if (line.length() == MAX_LINE) {
                throw new IOException("Protocol input limit exceeded");
            }
            line.append((char) value);
        }
        return value == -1 && line.isEmpty() ? null : line.toString();
    }

    private static String execute(String line, LongFunction<ExecutionAdapter> reset) {
        String[] fields = line.split("\t", -1);
        if (fields.length < 2 || fields.length > MAX_OPERATIONS + 1) {
            throw new IllegalArgumentException("Invalid operation count");
        }
        var adapter = reset.apply(Long.parseLong(fields[0]));
        var result = new StringBuilder("ok\t").append(adapter.observe());
        for (int index = 1; index < fields.length; index++) {
            String status = adapter.apply(fields[index], index);
            result.append('\t').append(status).append(',').append(adapter.observe());
        }
        return result.toString();
    }
}
