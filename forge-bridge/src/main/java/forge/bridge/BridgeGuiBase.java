package forge.bridge;

import forge.gamemodes.match.HostedMatch;
import forge.gui.download.GuiDownloadService;
import forge.gui.interfaces.IGuiBase;
import forge.gui.interfaces.IGuiGame;
import forge.item.PaperCard;
import forge.localinstance.skin.FSkinProp;
import forge.localinstance.skin.ISkinImage;
import forge.sound.IAudioClip;
import forge.sound.IAudioMusic;
import forge.util.FSerializableFunction;
import forge.util.ImageFetcher;
import org.jupnp.UpnpServiceConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public final class BridgeGuiBase implements IGuiBase, AutoCloseable {
    private final String assetsDir;
    private final AtomicReference<Thread> guiThread = new AtomicReference<>();
    private final ExecutorService guiExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ForgeBridge-Gui");
        thread.setDaemon(true);
        guiThread.set(thread);
        return thread;
    });

    public BridgeGuiBase(String assetsDir) {
        this.assetsDir = assetsDir;
    }

    @Override public boolean isRunningOnDesktop() { return false; }
    @Override public boolean isLibgdxPort() { return false; }
    @Override public String getCurrentVersion() { return "forge-bridge-spike-d"; }
    @Override public void invokeInEdtNow(Runnable runnable) { runnable.run(); }
    @Override public void invokeInEdtLater(Runnable runnable) { guiExecutor.execute(runnable); }

    @Override
    public void invokeInEdtAndWait(Runnable runnable) {
        if (isGuiThread()) {
            runnable.run();
            return;
        }
        Future<?> future = guiExecutor.submit(runnable);
        try {
            future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while dispatching Forge callback", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Forge callback failed", e.getCause());
        }
    }

    @Override
    public void runBackgroundTask(String message, Runnable task) {
        Thread thread = new Thread(task, "ForgeBridge-Background");
        thread.setDaemon(true);
        thread.start();
    }

    @Override public boolean isGuiThread() { return Thread.currentThread() == guiThread.get(); }
    @Override public String getAssetsDir() { return assetsDir; }
    @Override public ImageFetcher getImageFetcher() { return null; }
    @Override public ISkinImage getSkinIcon(FSkinProp skinProp) { return null; }
    @Override public ISkinImage getUnskinnedIcon(String path) { return null; }
    @Override public ISkinImage getCardArt(PaperCard card, boolean backFace) { return null; }
    @Override public ISkinImage createLayeredImage(PaperCard card, FSkinProp background, String overlayFilename, float opacity) { return null; }
    @Override public void clearImageCache() { }
    @Override public String encodeSymbols(String str, boolean formatReminderText) { return str; }
    @Override public int getAvatarCount() { return 1; }
    @Override public int getSleevesCount() { return 1; }
    @Override public float getScreenScale() { return 1f; }
    @Override public void preventSystemSleep(boolean preventSleep) { }
    @Override public void download(GuiDownloadService service, Consumer<Boolean> callback) { callback.accept(false); }
    @Override public void copyToClipboard(String text) { }
    @Override public void browseToUrl(String url) { }
    @Override public void showCardList(String title, String message, List<PaperCard> list) { }
    @Override public boolean showBoxedProduct(String title, String message, List<PaperCard> list) { return false; }
    @Override public void showBugReportDialog(String title, String text, boolean showExitAppBtn) { }
    @Override public void showImageDialog(ISkinImage image, String message, String title) { }
    @Override public int showOptionDialog(String message, String title, FSkinProp icon, List<String> options, int defaultOption) { return defaultOption; }
    @Override public String showInputDialog(String message, String title, FSkinProp icon, String initialInput, List<String> inputOptions, boolean isNumeric) { return initialInput; }
    @Override public String showFileDialog(String title, String defaultDir) { return null; }
    @Override public File getSaveFile(File defaultFile) { return null; }

    @Override
    public <T> List<T> order(String title, String top, int remainingObjectsMin, int remainingObjectsMax,
            List<T> sourceChoices, List<T> destChoices) {
        return sourceChoices == null ? Collections.emptyList() : new ArrayList<>(sourceChoices);
    }

    @Override
    public <T> List<T> getChoices(String message, int min, int max, Collection<T> choices,
            Collection<T> selected, FSerializableFunction<T, String> display) {
        return Collections.emptyList();
    }

    @Override public PaperCard chooseCard(String title, String message, List<PaperCard> list) { return null; }
    @Override public boolean isSupportedAudioFormat(File file) { return false; }
    @Override public IAudioClip createAudioClip(String filename) { return null; }
    @Override public IAudioMusic createAudioMusic(String filename) { return null; }
    @Override public void startAltSoundSystem(String filename, boolean isSynchronized) { }
    @Override public void showSpellShop() { }
    @Override public void showBazaar() { }
    @Override public IGuiGame getNewGuiGame() { return null; }
    @Override public HostedMatch hostMatch() { return null; }
    @Override public UpnpServiceConfiguration getUpnpPlatformService() { return null; }
    @Override public boolean hasNetGame() { return true; }

    @Override
    public void close() {
        guiExecutor.shutdownNow();
    }
}
