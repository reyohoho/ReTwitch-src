package tv.retwitch.mod;

import com.google.android.material.color.utilities.CorePalette;
import com.google.android.material.color.utilities.TonalPalette;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Material HCT ramps for one seed. Family order is the public Android 12 system colour resource order. */
final class PaletteTones {
    private static final int[] TONES = {100, 99, 95, 90, 80, 70, 60, 50, 40, 30, 20, 10, 0};
    private final TonalPalette[] ramps;

    PaletteTones(int seed) {
        CorePalette p = CorePalette.of(seed);
        ramps = new TonalPalette[]{p.n1, p.n2, p.a1, p.a2, p.a3};
    }

    static int parse(String value) {
        if (value == null || !value.matches("#[0-9a-fA-F]{6}")) return 0;
        return 0xff000000 | Integer.parseInt(value.substring(1), 16);
    }

    int framework(int id) {
        int index = id - android.R.color.system_neutral1_0;
        return tone(index / TONES.length, TONES[index % TONES.length]);
    }

    synchronized int tone(int family, int lightness) {
        return ramps[family].tone(lightness);
    }

    /** The patcher supplies offsets of its own eight-byte Res_value records in the fixed-colour template. */
    byte[] readTable(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length <= 0 || length > 1024 * 1024) throw new IOException("Invalid palette table size");
        byte[] table = new byte[length];
        in.readFully(table);
        ByteBuffer b = ByteBuffer.wrap(table).order(ByteOrder.LITTLE_ENDIAN);
        int count = in.readInt();
        if (count < 1 || count > length / 8) throw new IOException("Invalid palette entry count");
        for (int i = 0; i < count; i++) {
            int offset = in.readInt();
            if (offset < 4 || offset > length - 4 || b.get(offset - 1) != 0x1c)
                throw new IOException("Invalid palette entry offset");
            int key = b.getInt(offset);
            if (key < 0 || key / 128 >= ramps.length || key % 128 > 100)
                throw new IOException("Invalid palette tone");
            b.putInt(offset, tone(key / 128, key % 128));
        }
        return table;
    }
}
