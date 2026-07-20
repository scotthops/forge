package forge.bridge;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

final class JsonLineTransport implements AutoCloseable {
    private static final String STOP = new String("STOP");

    private final Gson gson = new Gson();
    private final BufferedReader input;
    private final PrintStream output;
    private final BlockingQueue<String> outputLines = new LinkedBlockingQueue<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Thread outputThread;
    private Thread inputThread;

    JsonLineTransport(InputStream input, PrintStream output) {
        this.input = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        this.output = output;
        outputThread = new Thread(this::writeOutput, "ForgeBridge-JsonOutput");
        outputThread.setDaemon(true);
        outputThread.start();
    }

    void startInput(Consumer<JsonObject> messageHandler, Consumer<String> errorHandler, Runnable eofHandler) {
        if (inputThread != null) {
            throw new IllegalStateException("JSON input reader already started");
        }
        inputThread = new Thread(() -> readInput(messageHandler, errorHandler, eofHandler),
                "ForgeBridge-JsonInput");
        inputThread.setDaemon(true);
        inputThread.start();
    }

    void send(Object message) {
        if (!closed.get()) {
            outputLines.offer(gson.toJson(message));
        }
    }

    private void readInput(Consumer<JsonObject> messageHandler, Consumer<String> errorHandler,
            Runnable eofHandler) {
        try {
            String line;
            while (!closed.get() && (line = input.readLine()) != null) {
                try {
                    if (!JsonParser.parseString(line).isJsonObject()) {
                        errorHandler.accept("Each input line must be a JSON object");
                        continue;
                    }
                    messageHandler.accept(JsonParser.parseString(line).getAsJsonObject());
                } catch (RuntimeException e) {
                    errorHandler.accept("Invalid JSON input: " + safe(e.getMessage()));
                }
            }
        } catch (IOException e) {
            if (!closed.get()) {
                errorHandler.accept("JSON input failed: " + safe(e.getMessage()));
            }
        } finally {
            eofHandler.run();
        }
    }

    private void writeOutput() {
        try {
            while (true) {
                String line = outputLines.take();
                if (line == STOP) {
                    return;
                }
                output.println(line);
                output.flush();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ');
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        outputLines.offer(STOP);
        try {
            outputThread.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
