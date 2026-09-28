package com.drepfy.staffvanish;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.drepfy.staffvanish.selector.SelectorItem;
import io.papermc.paper.entity.TeleportFlag;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.inventory.ChestInventoryMock;
import org.mockbukkit.mockbukkit.inventory.InventoryMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

public abstract class VanishTestBase {

    protected ServerMock server;
    protected WorldMock world;
    protected StaffVanishPlugin plugin;
    protected VanishModule module;
    protected VanishManager vanish;

    @BeforeEach
    protected void startServer() {
        server = MockBukkit.mock(new RunningServerMock());
        world = server.addSimpleWorld("world");
        plugin = MockBukkit.load(StaffVanishPlugin.class);
        module = plugin.vanish();
        assertNotNull(module);
        vanish = module.manager();
    }

    @AfterEach
    protected void stopServer() {
        MockBukkit.unmock();
    }

    /**
     * A staff member with every vanish permission. The permissions are attached before joining, like a permissions
     * plugin would, and the player is also made an operator, since MockBukkit drops attachments on disconnect but
     * keeps operators (who get the permissions by default) across reconnects.
     */
    protected TestPlayer staff(String name) {
        TestPlayer player = new TestPlayer(server, name);
        player.addAttachment(plugin, "staffvanish.*", true);
        server.addPlayer(player);
        player.setOp(true);
        return player;
    }

    /** Takes away every permission given by {@link #staff}. */
    protected void demote(TestPlayer player) {
        // MockBukkit clears the calculated permissions, but not the attachments, on disconnect.
        player.recalculatePermissions();
        Set<PermissionAttachment> attachments = new HashSet<>();
        for (PermissionAttachmentInfo info : player.getEffectivePermissions()) {
            if (info.getAttachment() != null) {
                attachments.add(info.getAttachment());
            }
        }
        attachments.forEach(PermissionAttachment::remove);
        player.setOp(false);
    }

    protected TestPlayer regular(String name) {
        TestPlayer player = new TestPlayer(server, name);
        server.addPlayer(player);
        return player;
    }

    /** A vanished staff member holding the staff selector. */
    protected TestPlayer vanishedHolding(String name) {
        TestPlayer player = staff(name);
        assertTrue(vanish.vanish(player, player));
        player.getInventory().setHeldItemSlot(module.settings().selector().slot());
        assertTrue(selectors().isSelector(player.getInventory().getItemInMainHand()));
        return player;
    }

    protected SelectorItem selectors() {
        return module.selectorItem();
    }

    protected boolean hasSelector(Player player) {
        return Arrays.stream(player.getInventory().getContents()).anyMatch(selectors()::isSelector);
    }

    /** Drains the player's chat messages as plain text. */
    protected static List<String> messages(PlayerMock player) {
        List<String> messages = new ArrayList<>();
        Component message;
        while ((message = player.nextComponentMessage()) != null) {
            messages.add(PlainTextComponentSerializer.plainText().serialize(message));
        }
        return messages;
    }

    /** Fills in parts of the server that MockBukkit doesn't implement. */
    @SuppressWarnings("unchecked") // inherited from ServerMock#getBanList
    private static final class RunningServerMock extends ServerMock {
        @Override
        public boolean isStopping() {
            return false;
        }

        @Override
        public InventoryMock createInventory(@Nullable InventoryHolder owner, int size) {
            return new HolderInventoryMock(owner, size);
        }

        @Override
        public InventoryMock createInventory(@Nullable InventoryHolder owner, int size, Component title) {
            return new HolderInventoryMock(owner, size);
        }
    }

    /** A chest inventory that supports Paper's snapshot-free {@code getHolder(boolean)}. */
    private static final class HolderInventoryMock extends ChestInventoryMock {
        HolderInventoryMock(@Nullable InventoryHolder holder, int size) {
            super(holder, size);
        }

        @Override
        public @Nullable InventoryHolder getHolder(boolean useSnapshot) {
            return getHolder();
        }
    }

    /** MockBukkit doesn't implement asynchronous teleports; this one teleports straight away. */
    protected static final class TestPlayer extends PlayerMock {

        TestPlayer(ServerMock server, String name) {
            super(server, name);
        }

        @Override
        public CompletableFuture<Boolean> teleportAsync(Location location, PlayerTeleportEvent.TeleportCause cause,
                TeleportFlag... flags) {
            return CompletableFuture.completedFuture(teleport(location, cause));
        }
    }
}
