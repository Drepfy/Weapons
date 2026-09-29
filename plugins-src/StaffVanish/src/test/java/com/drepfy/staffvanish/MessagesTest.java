package com.drepfy.staffvanish;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class MessagesTest {

    @Test
    void colorCodesBecomeMiniMessageTags() {
        assertEquals("<aqua><bold>ᴠᴀɴɪsʜ </bold><dark_gray>» ", Messages.translateColorCodes("&b&lᴠᴀɴɪsʜ &8» "));
        assertEquals("<#55ffff>Hi <underlined>there", Messages.translateColorCodes("&#55ffffHi &Nthere"));
    }

    @Test
    void aColorCodeEndsEarlierFormattingLikeInChat() {
        assertEquals("<bold>A</bold><aqua>B", Messages.translateColorCodes("&lA&bB"));
        assertEquals("<italic><bold>A</bold></italic><reset>B", Messages.translateColorCodes("&o&lA&rB"));
    }

    @Test
    void textWithoutColorCodesIsLeftAlone() {
        assertEquals("<yellow>Rock & Roll &z 100%", Messages.translateColorCodes("<yellow>Rock & Roll &z 100%"));
    }
}
