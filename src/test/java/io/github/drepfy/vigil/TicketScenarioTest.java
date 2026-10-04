package io.github.drepfy.vigil;

import io.github.drepfy.vigil.ticket.Ticket;
import io.github.drepfy.vigil.ticket.TicketCategory;
import io.github.drepfy.vigil.ticket.TicketMessage;
import io.github.drepfy.vigil.util.Clock;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** /ticket, /tickets and /report on a simulated server. */
class TicketScenarioTest {

    private final AtomicLong clock = new AtomicLong(1_000_000);
    private ServerMock server;
    private VigilPlugin plugin;
    private WorldMock world;

    @BeforeEach
    void setUp() {
        Clock.setTestSource(clock::get);
        server = MockBukkit.mock();
        plugin = MockBukkit.load(VigilPlugin.class);
        world = server.addSimpleWorld("test");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
        Clock.setTestSource(null);
    }

    private PlayerMock join(String name, boolean staff) {
        PlayerMock player = server.addPlayer(name);
        player.setOp(staff);
        player.teleport(new Location(world, 10.5, 64, -3.5));
        server.getScheduler().performTicks(60);
        messages(player);
        return player;
    }

    private List<String> messages(PlayerMock player) {
        List<String> result = new ArrayList<>();
        String message;
        while ((message = player.nextMessage()) != null) {
            result.add(ChatColor.stripColor(message));
        }
        return result;
    }

    private static boolean has(List<String> lines, String text) {
        return lines.stream().anyMatch(line -> line.contains(text));
    }

    /** Past the 2 second message cooldown. */
    private void later() {
        clock.addAndGet(3000);
    }

    @Test
    void playersAndStaffTalkThroughATicket() {
        PlayerMock staff = join("Admin", true);
        PlayerMock steve = join("Steve", false);

        steve.performCommand("ticket My house was griefed");
        List<String> toSteve = messages(steve);
        assertTrue(has(toSteve, "Your ticket #1 has been opened"), toSteve.toString());
        List<String> toStaff = messages(staff);
        assertTrue(has(toStaff, "[Ticket #1] Steve opened a ticket (Support): My house was griefed"), toStaff.toString());

        later();
        steve.performCommand("ticket It was near spawn");
        assertTrue(has(messages(steve), "Your message was added to ticket #1"));
        assertTrue(has(messages(staff), "[Ticket #1] Steve: It was near spawn"));

        staff.performCommand("tickets");
        List<String> list = messages(staff);
        assertTrue(has(list, "Open tickets (1)"), list.toString());
        assertTrue(has(list, "#1 Support - Steve - 2 msg"), list.toString());

        staff.performCommand("tickets reply 1 On my way, &cdon't worry");
        assertTrue(has(messages(steve), "[Ticket #1] Admin: On my way, &cdon't worry"), "text is shown as typed");
        staff.performCommand("tickets claim 1");
        assertTrue(has(messages(steve), "Ticket #1 is now handled by Admin"));

        staff.performCommand("tickets view 1");
        List<String> view = messages(staff);
        assertTrue(has(view, "Ticket #1"), view.toString());
        assertTrue(has(view, "Opened by Steve"), view.toString());
        assertTrue(has(view, "Handled by Admin"), view.toString());
        assertTrue(has(view, "Steve: My house was griefed"), view.toString());
        assertTrue(has(view, "Admin: On my way"), view.toString());

        later();
        steve.performCommand("ticket close");
        assertTrue(has(messages(steve), "Ticket #1 has been closed"));
        Ticket ticket = plugin.tickets().get(1);
        assertFalse(ticket.isOpen());
        assertEquals("Steve", ticket.closedBy());
        staff.performCommand("tickets");
        assertTrue(has(messages(staff), "There are no open tickets"));
    }

    @Test
    void staffReplyWhilePlayerIsOfflineIsShownOnTheirNextJoin() {
        PlayerMock staff = join("Admin", true);
        PlayerMock alex = join("Alex", false);
        alex.performCommand("ticket I lost my items");
        alex.disconnect();
        staff.performCommand("tickets reply 1 We restored them");
        Ticket ticket = plugin.tickets().get(1);
        assertTrue(ticket.unread());

        alex.reconnect();
        server.getScheduler().performTicks(60);
        List<String> lines = messages(alex);
        assertTrue(has(lines, "You have a new reply on ticket #1"), lines.toString());
        alex.performCommand("ticket view");
        List<String> view = messages(alex);
        assertTrue(has(view, "Admin: We restored them"), view.toString());
        assertFalse(ticket.unread(), "read now");

        // A ticket closed while they were away is announced too.
        alex.disconnect();
        staff.performCommand("tickets close 1 Items restored");
        alex.reconnect();
        server.getScheduler().performTicks(60);
        List<String> closed = messages(alex);
        assertTrue(has(closed, "Your ticket #1 was closed by Admin. (Items restored)"), closed.toString());
        assertFalse(ticket.unread());
    }

    @Test
    void staffAreToldAboutOpenTicketsWhenTheyJoin() {
        PlayerMock steve = join("Steve", false);
        steve.performCommand("ticket Help please");
        PlayerMock staff = join("Admin", true);
        // join() already waited for the notice; check what it said.
        staff.performCommand("tickets");
        assertTrue(has(messages(staff), "#1 Support"));
        PlayerMock other = server.addPlayer("Mod");
        other.setOp(true);
        server.getScheduler().performTicks(60);
        assertTrue(has(messages(other), "Open tickets waiting for staff: 1"));
    }

    @Test
    void reportsBecomeTicketsWithTheirPlace() {
        PlayerMock staff = join("Admin", true);
        PlayerMock steve = join("Steve", false);
        join("Cheater", false);

        steve.performCommand("report Cheater flying over the base");
        assertTrue(has(messages(steve), "Your report about Cheater has been sent to staff as ticket #1"));
        assertTrue(has(messages(staff), "[Ticket #1] Steve opened a ticket (Report a player: Cheater): flying over the base"));
        Ticket report = plugin.tickets().get(1);
        assertEquals(TicketCategory.REPORT, report.category());
        assertEquals("Cheater", report.about());
        assertTrue(report.location().startsWith("test;10.5;64;-3.5"), report.location());

        steve.performCommand("report Steve test");
        assertTrue(has(messages(steve), "You cannot report yourself"));
        steve.performCommand("report Nobody123 test");
        assertTrue(has(messages(steve), "Player not found"));
        steve.performCommand("report Cheater");
        assertTrue(has(messages(steve), "/report <player> <reason>"));
        steve.performCommand("report Cheater again");
        assertTrue(has(messages(steve), "Please wait a moment"), "one report a minute");

        staff.teleport(new Location(world, 0, 64, 0));
        staff.performCommand("tickets tp 1");
        assertEquals(10.5, staff.getLocation().getX(), 1e-9);
        assertEquals(-3.5, staff.getLocation().getZ(), 1e-9);
    }

    @Test
    void playersWithSeveralTicketsChooseOne() {
        PlayerMock steve = join("Steve", false);
        join("Griefer", false);
        steve.performCommand("ticket First problem");
        clock.addAndGet(61_000);
        steve.performCommand("report Griefer broke my door");
        later();
        steve.performCommand("ticket Which one?");
        assertTrue(has(messages(steve), "You have more than one open ticket"));
        steve.performCommand("ticket reply 2 It was the iron door");
        assertEquals("It was the iron door", plugin.tickets().get(2).messages().get(1).text());
        later();
        // A number that is not one of their tickets is part of the message.
        steve.performCommand("ticket reply 5 people saw it");
        assertTrue(has(messages(steve), "You have more than one open ticket"));
        steve.performCommand("ticket list");
        List<String> list = messages(steve);
        assertTrue(has(list, "#1 Support"), list.toString());
        assertTrue(has(list, "#2 Report a player"), list.toString());
        later();
        steve.performCommand("ticket close 2");
        assertFalse(plugin.tickets().get(2).isOpen());
        later();
        steve.performCommand("ticket reply 2 people saw it");
        assertEquals("2 people saw it", plugin.tickets().get(1).messages().get(1).text(),
                "with one ticket left, the number is just text");
    }

    @Test
    void playersCannotTouchOtherPlayersTicketsOrUseStaffCommands() {
        PlayerMock steve = join("Steve", false);
        PlayerMock alex = join("Alex", false);
        steve.performCommand("ticket Secret problem");
        alex.performCommand("ticket view 1");
        assertTrue(has(messages(alex), "Ticket not found: 1"));
        alex.performCommand("ticket close 1");
        assertTrue(plugin.tickets().get(1).isOpen());
        alex.performCommand("tickets");
        alex.performCommand("tickets close 1");
        assertTrue(plugin.tickets().get(1).isOpen(), "no permission");
    }

    @Test
    void ticketsCanBeTurnedOff() throws Exception {
        PlayerMock steve = join("Steve", false);
        java.io.File file = new java.io.File(plugin.getDataFolder(), "config.yml");
        String yaml = java.nio.file.Files.readString(file.toPath());
        java.nio.file.Files.writeString(file.toPath(), yaml.replace("tickets:\n  enabled: true", "tickets:\n  enabled: false"));
        plugin.reload();
        steve.performCommand("ticket hello");
        assertTrue(has(messages(steve), "Tickets are turned off on this server"));
        assertNull(plugin.tickets().get(1));
    }

    @Test
    void ticketsFromDiscordShowUpInGame() {
        PlayerMock staff = join("Admin", true);
        Ticket ticket = plugin.tickets().open(TicketCategory.APPEAL, null, "bob", "111111111111111111", "Bob", "Bob",
                null, "I was not cheating", TicketMessage.Origin.FORM);
        assertTrue(has(messages(staff), "[Ticket #1] bob (Discord) opened a ticket (Ban appeal: Bob): I was not cheating"));
        staff.performCommand("tickets view 1");
        List<String> view = messages(staff);
        assertTrue(has(view, "Minecraft name: Bob"), view.toString());
        plugin.tickets().reply(ticket, "bob", false, TicketMessage.Origin.DISCORD, "Please check again");
        assertTrue(has(messages(staff), "[Ticket #1] bob (Discord): Please check again"));
        staff.performCommand("tickets tp 1");
        assertTrue(has(messages(staff), "has no location"));
        assertNotNull(plugin.tickets().get(1));
    }
}
