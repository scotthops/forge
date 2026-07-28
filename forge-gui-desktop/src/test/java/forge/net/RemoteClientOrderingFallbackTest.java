package forge.net;

import forge.gamemodes.net.server.RemoteClient;
import forge.gamemodes.net.server.RemoteClientGuiGame;
import forge.gui.GuiBase;
import forge.gui.interfaces.IGuiGame;
import io.netty.channel.embedded.EmbeddedChannel;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.List;

public class RemoteClientOrderingFallbackTest {
    @BeforeClass
    public void initializeHeadlessGui() {
        GuiBase.setInterface(new HeadlessGuiDesktop());
    }

    @Test
    public void testPausedOrDisconnectedRemoteOrderNeverReturnsNull() {
        RemoteClient client = new RemoteClient(new EmbeddedChannel());
        client.setUsername("ordering fallback test");
        RemoteClientGuiGame gui = new RemoteClientGuiGame(client);
        gui.pause();

        IGuiGame.OrderResult<String> result = gui.order(
                "Order simultaneous triggers", "Resolve first",
                0, 0, List.of("first", "second"), List.of(),
                null, false, false);

        Assert.assertNotNull(result);
        Assert.assertEquals(result.ordered(), List.of("first", "second"));
        Assert.assertFalse(result.rememberDecision());
    }
}
