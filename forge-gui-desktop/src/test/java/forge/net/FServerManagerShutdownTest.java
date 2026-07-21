package forge.net;

import forge.gamemodes.net.server.FServerManager;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

public class FServerManagerShutdownTest {
    @BeforeClass
    public static void setUp() {
        TestUtils.ensureFModelInitialized();
    }

    @Test(timeOut = 30000,
            description = "Server shutdown is idempotent and permits a later restart")
    public void testRepeatedStopAndRestart() {
        FServerManager server = FServerManager.getInstance();
        if (server.isHosting()) {
            server.stopServer();
        }

        try {
            server.startServer(PortAllocator.allocatePort());
            Assert.assertTrue(server.isHosting());

            server.stopServer();
            server.stopServer();
            Assert.assertFalse(server.isHosting());

            server.startServer(PortAllocator.allocatePort());
            Assert.assertTrue(server.isHosting());
        } finally {
            if (server.isHosting()) {
                server.stopServer();
            }
        }
    }

    @Test(timeOut = 60000,
            description = "JVM shutdown hook does not collide with the server close callback")
    public void testJvmShutdownHookIsClean() throws Exception {
        Path outputFile = Files.createTempFile("forge-server-shutdown-", ".log");
        try {
            Process process = new ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp", System.getProperty("java.class.path"),
                    ShutdownHookProcess.class.getName(),
                    Integer.toString(PortAllocator.allocatePort()))
                    .redirectErrorStream(true)
                    .redirectOutput(outputFile.toFile())
                    .start();

            boolean exited = process.waitFor(30, TimeUnit.SECONDS);
            if (!exited) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
            Assert.assertTrue(exited, "Shutdown-hook subprocess did not exit");
            String output = Files.readString(outputFile, StandardCharsets.UTF_8);
            Assert.assertEquals(process.exitValue(), 0, output);
            Assert.assertFalse(output.contains("Shutdown in progress"), output);
            Assert.assertFalse(output.contains("IllegalStateException"), output);
        } finally {
            Files.deleteIfExists(outputFile);
        }
    }

    public static final class ShutdownHookProcess {
        private ShutdownHookProcess() {
        }

        public static void main(String[] args) {
            TestUtils.ensureFModelInitialized();
            FServerManager server = FServerManager.getInstance();
            server.startServer(Integer.parseInt(args[0]));
            if (!server.isHosting()) {
                throw new IllegalStateException("Server did not start");
            }
            System.exit(0);
        }
    }
}
