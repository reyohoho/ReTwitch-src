package tv.retwitch.mod;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Run with -ea and the Material jar. Uses no Android runtime methods. */
public final class PaletteTonesSelfCheck {
    public static void main(String[] args) throws Exception {
        assert PaletteTones.parse("#000000") == 0xff000000;
        assert PaletteTones.parse("#fEfFad") == 0xfffeffad;
        for (String invalid : new String[]{null, "", "123456", "#abc", "#ff00gg", "#00112233"})
            assert PaletteTones.parse(invalid) == 0;
        PaletteTones green = new PaletteTones(0xff00ff00);
        assert green.framework(android.R.color.system_accent1_600) == green.tone(2, 40);
        assert green.framework(android.R.color.system_neutral1_0) == 0xffffffff;
        assert green.framework(android.R.color.system_neutral2_1000) == 0xff000000;
        assert green.tone(2, 40) != new PaletteTones(0xffff0000).tone(2, 40);
        byte[] record = {8, 0, 0, 0x1c, 40, 1, 0, 0}; // primary, tone 40
        ByteBuffer file = ByteBuffer.allocate(20).putInt(8).put(record).putInt(1).putInt(4);
        byte[] filled = green.readTable(new DataInputStream(new ByteArrayInputStream(file.array())));
        assert ByteBuffer.wrap(filled).order(ByteOrder.LITTLE_ENDIAN).getInt(4) == green.tone(2, 40);
        file.putInt(16, 8);
        try {
            green.readTable(new DataInputStream(new ByteArrayInputStream(file.array())));
            throw new AssertionError("Invalid resource offset");
        } catch (IOException expected) { }
    }
}
