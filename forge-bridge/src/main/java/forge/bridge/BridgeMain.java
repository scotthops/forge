package forge.bridge;

import forge.gui.GuiBase;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

public final class BridgeMain {
    private BridgeMain() {
    }

    public static void main(String[] args) {
        int exitCode = 1;
        PrintStream protocolOutput = System.out;
        System.setOut(System.err);
        try {
            Arguments arguments = Arguments.parse(args);

            // This must be the first use of Forge application classes. ForgeConstants
            // reads GuiBase.getInterface().getAssetsDir() during class initialization.
            BridgeGuiBase guiBase = new BridgeGuiBase(arguments.assetsDir());
            GuiBase.setInterface(guiBase);
            try (JsonLineTransport transport = new JsonLineTransport(System.in, protocolOutput)) {
                FModel.initialize(null, preferences -> {
                    preferences.setPref(FPref.UI_LANGUAGE, "en-US");
                    preferences.setPref(FPref.DECKGEN_CARDBASED, false);
                    preferences.setPref(FPref.UI_SHOW_ACTIONABLE_HIGHLIGHTS, true);
                    return null;
                });

                try (BridgeSession session = new BridgeSession(
                        arguments.host(), arguments.port(), arguments.username(), arguments.exitAfterProof(),
                        guiBase, transport)) {
                    session.modelInitialized();
                    exitCode = session.run();
                }
            } finally {
                guiBase.close();
            }
        } catch (IllegalArgumentException e) {
            System.err.println("BRIDGE_ERROR " + e.getMessage());
            printUsage();
            exitCode = 2;
        } catch (Exception e) {
            System.err.println("BRIDGE_ERROR type=" + e.getClass().getSimpleName()
                    + " message=" + safe(e.getMessage()));
            e.printStackTrace(System.err);
        }
        System.exit(exitCode);
    }

    private static void printUsage() {
        System.err.println("Usage: java -jar forge-bridge-*-jar-with-dependencies.jar "
                + "[--host HOST] [--port PORT] [--username NAME] [--assets-dir DIR] [--exit-after-proof]");
    }

    private static String safe(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ');
    }

    private record Arguments(String host, int port, String username, String assetsDir, boolean exitAfterProof) {
        private static Arguments parse(String[] args) {
            String host = "localhost";
            int port = 36743;
            String username = "Forge Bridge";
            String assetsDir = null;
            boolean exitAfterProof = false;

            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--host" -> host = requireValue(args, ++i, "--host");
                    case "--port" -> port = Integer.parseInt(requireValue(args, ++i, "--port"));
                    case "--username" -> username = requireValue(args, ++i, "--username");
                    case "--assets-dir" -> assetsDir = requireValue(args, ++i, "--assets-dir");
                    case "--exit-after-proof" -> exitAfterProof = true;
                    default -> throw new IllegalArgumentException("Unknown argument: " + args[i]);
                }
            }
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("Port must be between 1 and 65535");
            }
            return new Arguments(host, port, username, normalizeAssetsDir(assetsDir), exitAfterProof);
        }

        private static String requireValue(String[] args, int index, String option) {
            if (index >= args.length) {
                throw new IllegalArgumentException("Missing value for " + option);
            }
            return args[index];
        }

        private static String normalizeAssetsDir(String configured) {
            Path path;
            if (configured != null) {
                path = Path.of(configured);
            } else if (Files.isDirectory(Path.of("forge-gui", "res"))) {
                path = Path.of("forge-gui");
            } else if (Files.isDirectory(Path.of("..", "forge-gui", "res"))) {
                path = Path.of("..", "forge-gui");
            } else {
                path = Path.of("");
            }
            String value = path.toAbsolutePath().normalize().toString();
            return value.endsWith(java.io.File.separator) ? value : value + java.io.File.separator;
        }
    }
}
