package io.github.drepfy.vigil.ticket;

import io.github.drepfy.vigil.storage.IoExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TicketServiceTest {

    private static final Logger LOGGER = Logger.getLogger("test");
    private static final long MONTH = 30L * 24 * 3600 * 1000;

    private final IoExecutor io = new IoExecutor(LOGGER);
    private final Set<UUID> online = new HashSet<>();

    @AfterEach
    void tearDown() {
        io.shutdown(2000);
    }

    /** Saving happens on the IO thread; wait for it before reading the file. */
    private void awaitSaved(TicketService service) throws InterruptedException {
        service.saveNow();
        long end = System.currentTimeMillis() + 5000;
        while (!io.isIdle() && System.currentTimeMillis() < end) {
            Thread.sleep(10);
        }
    }

    private TicketService service(Path file) {
        return new TicketService(LOGGER, io, file, online::contains, MONTH);
    }

    @Test
    void conversationFromOpenToClose(@TempDir Path dir) {
        TicketService service = service(dir.resolve("tickets.yml"));
        List<String> events = new ArrayList<>();
        service.addObserver(new TicketService.Observer() {
            @Override
            public void opened(Ticket ticket) {
                events.add("opened " + ticket.id());
            }

            @Override
            public void message(Ticket ticket, TicketMessage message) {
                events.add("message " + message.author());
            }

            @Override
            public void claimed(Ticket ticket) {
                events.add("claimed " + ticket.claimedBy());
            }

            @Override
            public void closed(Ticket ticket) {
                events.add("closed " + ticket.closedBy());
            }
        });
        UUID steve = UUID.randomUUID();
        online.add(steve);
        Ticket ticket = service.open(TicketCategory.SUPPORT, steve, "Steve", null, null, null, "world;1;64;2;0;0",
                "My house was griefed", TicketMessage.Origin.GAME);
        assertEquals(1, ticket.id());
        assertEquals("Steve", ticket.minecraftName());
        assertTrue(ticket.openedInGame());
        assertTrue(service.reply(ticket, "Admin", true, TicketMessage.Origin.GAME, "On my way"));
        assertFalse(ticket.unread(), "Steve is online and saw it");
        assertTrue(service.claim(ticket, "Admin"));
        assertEquals(List.of(ticket), service.openOf(steve));
        assertTrue(service.close(ticket, "Admin", "Fixed"));
        assertFalse(service.close(ticket, "Admin", "Again"), "only once");
        assertFalse(service.reply(ticket, "Steve", false, TicketMessage.Origin.GAME, "thanks"), "closed tickets take no messages");
        assertEquals(List.of("opened 1", "message Admin", "claimed Admin", "closed Admin"), events);
        assertEquals(List.of(), service.open());
    }

    @Test
    void offlinePlayersHaveUnreadReplies(@TempDir Path dir) {
        TicketService service = service(dir.resolve("tickets.yml"));
        UUID alex = UUID.randomUUID();
        Ticket ticket = service.open(TicketCategory.SUPPORT, alex, "Alex", null, null, null, null, "Help",
                TicketMessage.Origin.GAME);
        service.reply(ticket, "Alex", false, TicketMessage.Origin.GAME, "Anyone?");
        assertFalse(ticket.unread(), "their own messages do not count");
        service.reply(ticket, "Mod", true, TicketMessage.Origin.DISCORD, "Hi");
        assertTrue(ticket.unread());
        assertEquals(List.of(ticket), service.unreadOf(alex));
        service.markRead(ticket);
        assertEquals(List.of(), service.unreadOf(alex));
    }

    @Test
    void discordLookupsFollowTheTicket(@TempDir Path dir) {
        TicketService service = service(dir.resolve("tickets.yml"));
        Ticket ticket = service.open(TicketCategory.APPEAL, null, "bob", "111111111111111111", "Bob", "Bob", null,
                "I did not cheat", TicketMessage.Origin.FORM);
        assertFalse(ticket.openedInGame());
        assertEquals("Bob", ticket.minecraftName());
        assertEquals(ticket.id(), service.openTicketOfDiscordUser("111111111111111111"));
        assertNull(service.ticketForChannel("222222222222222222"));
        service.setChannel(ticket, "222222222222222222");
        assertEquals(ticket.id(), service.ticketForChannel("222222222222222222"));
        assertEquals("222222222222222222", service.channelOfOpenTicket(ticket.id()));
        service.close(ticket, "Mod (Discord)", "Accepted");
        assertNull(service.ticketForChannel("222222222222222222"), "closed tickets are not looked up");
        assertNull(service.openTicketOfDiscordUser("111111111111111111"));
        assertNull(service.channelOfOpenTicket(ticket.id()));
    }

    @Test
    void ticketsSurviveRestarts(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("tickets.yml");
        TicketService service = service(file);
        UUID steve = UUID.randomUUID();
        Ticket ticket = service.open(TicketCategory.REPORT, steve, "Steve", null, null, "Cheater", "world;1;64;2;0;0",
                "He is flying: \"look\" {id} &c red", TicketMessage.Origin.GAME);
        service.setChannel(ticket, "333333333333333333");
        service.reply(ticket, "Mod", true, TicketMessage.Origin.DISCORD, "Watching him");
        Ticket discord = service.open(TicketCategory.SUPPORT, null, "bob", "444444444444444444", null, null, null,
                "Hello", TicketMessage.Origin.FORM);
        awaitSaved(service);

        TicketService loaded = service(file);
        Ticket again = loaded.get(ticket.id());
        assertNotNull(again);
        assertEquals(TicketCategory.REPORT, again.category());
        assertEquals("Cheater", again.about());
        assertEquals("world;1;64;2;0;0", again.location());
        assertEquals(2, again.messages().size());
        assertEquals("He is flying: \"look\" {id} &c red", again.messages().get(0).text(), "text is kept as typed");
        assertEquals(TicketMessage.Origin.DISCORD, again.messages().get(1).origin());
        assertTrue(again.messages().get(1).staff());
        assertTrue(again.unread());
        assertEquals(ticket.id(), loaded.ticketForChannel("333333333333333333"));
        assertEquals(discord.id(), loaded.openTicketOfDiscordUser("444444444444444444"));
        Ticket next = loaded.open(TicketCategory.SUPPORT, steve, "Steve", null, null, null, null, "Another",
                TicketMessage.Origin.GAME);
        assertEquals(3, next.id(), "numbers continue");
    }

    @Test
    void oldClosedTicketsAreDeleted(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("tickets.yml");
        TicketService service = service(file);
        Ticket old = service.open(TicketCategory.SUPPORT, UUID.randomUUID(), "Old", null, null, null, null, "x",
                TicketMessage.Origin.GAME);
        service.close(old, "Mod", "Done");
        Ticket open = service.open(TicketCategory.SUPPORT, UUID.randomUUID(), "Open", null, null, null, null, "y",
                TicketMessage.Origin.GAME);
        awaitSaved(service);
        // Pretend the first ticket was closed two months ago.
        String yaml = Files.readString(file).replaceFirst("closed: \\d+", "closed: "
                + (System.currentTimeMillis() - 2 * MONTH));
        Files.writeString(file, yaml);

        TicketService loaded = service(file);
        assertNull(loaded.get(old.id()), "closed long ago");
        assertNotNull(loaded.get(open.id()), "open tickets are always kept");
        TicketService keepAll = new TicketService(LOGGER, io, file, online::contains, -1L);
        assertNotNull(keepAll.get(old.id()), "keep-closed: perm keeps everything");
    }

    @Test
    void longTextIsCutAndFullTicketsTakeNoMore(@TempDir Path dir) {
        TicketService service = service(dir.resolve("tickets.yml"));
        Ticket ticket = service.open(TicketCategory.SUPPORT, UUID.randomUUID(), "Spammer", null, null, null, null,
                "x".repeat(5000), TicketMessage.Origin.GAME);
        assertEquals(TicketService.MAX_MESSAGE_LENGTH, ticket.messages().get(0).text().length());
        for (int i = 1; i < TicketService.MAX_MESSAGES; i++) {
            assertTrue(service.reply(ticket, "Spammer", false, TicketMessage.Origin.GAME, "m" + i));
        }
        assertFalse(service.reply(ticket, "Spammer", false, TicketMessage.Origin.GAME, "one more"));
    }

    @Test
    void aBrokenObserverDoesNotStopTheOthers(@TempDir Path dir) {
        TicketService service = service(dir.resolve("tickets.yml"));
        List<Integer> seen = new ArrayList<>();
        service.addObserver(new TicketService.Observer() {
            @Override
            public void opened(Ticket ticket) {
                throw new IllegalStateException("boom");
            }
        });
        service.addObserver(new TicketService.Observer() {
            @Override
            public void opened(Ticket ticket) {
                seen.add(ticket.id());
            }
        });
        service.open(TicketCategory.SUPPORT, UUID.randomUUID(), "A", null, null, null, null, "x",
                TicketMessage.Origin.GAME);
        assertEquals(List.of(1), seen);
    }

    @Test
    void unreadableFilesAreMovedAside(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("tickets.yml");
        Files.writeString(file, "tickets: [unclosed");
        TicketService service = service(file);
        assertEquals(List.of(), service.open());
        assertFalse(Files.exists(file), "moved aside, not deleted");
        try (var files = Files.list(dir)) {
            assertTrue(files.anyMatch(path -> path.getFileName().toString().startsWith("tickets.yml.corrupt-")));
        }
    }
}
