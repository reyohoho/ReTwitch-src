package tv.retwitch.mod;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Both release mirrors serve the APK identified by the size and SHA-256 from latest.json. */
final class UpdateApk {
    enum Source {
        YANDEX, GITHUB;
        Source other() { return this == YANDEX ? GITHUB : YANDEX; }
    }
    interface Mirror { HttpURLConnection open(Source source, long offset) throws IOException; }
    interface Progress { void at(Source source, long bytes); }

    private static final int CONNECTIONS = 8;

    static boolean fetch(File destination, long size, String hash, Source preferred, AtomicBoolean cancelled,
            Mirror mirror, Progress progress) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[64 * 1024];
        Source source = preferred;
        long total = 0;
        IOException failure = null;
        try (FileOutputStream out = new FileOutputStream(destination)) {
            for (int attempt = 0; attempt < CONNECTIONS && !cancelled.get(); attempt++) {
                long from = total;
                int shown = -1;
                long lastShown = 0;
                progress.at(source, total);
                HttpURLConnection connection = null;
                try {
                    connection = mirror.open(source, total);
                    try (InputStream in = connection.getInputStream()) {
                        int code = connection.getResponseCode();
                        if (code == HttpURLConnection.HTTP_PARTIAL) {
                            String range = connection.getHeaderField("Content-Range");
                            if (range == null || !range.startsWith("bytes " + total + "-")) throw new IOException("Wrong APK range");
                        } else if (code == HttpURLConnection.HTTP_OK) {
                            // Some S3 proxies send the entire file even when Range is set.
                            for (long left = total; left > 0; ) {
                                if (cancelled.get()) return false;
                                int n = in.read(buffer, 0, (int) Math.min(left, buffer.length));
                                if (n < 0) throw new IOException("Short APK response");
                                left -= n;
                            }
                        } else throw new IOException("APK HTTP " + code);
                        for (int n; (n = in.read(buffer)) >= 0; ) {
                            if (cancelled.get()) return false;
                            if (n > size - total) throw new IOException("APK exceeds published size");
                            out.write(buffer, 0, n);
                            digest.update(buffer, 0, n);
                            total += n;
                            int percent = (int) (total * 100 / size);
                            long now = System.nanoTime();
                            if (shown != percent || now - lastShown >= 500_000_000L) {
                                progress.at(source, total);
                                shown = percent;
                                lastShown = now;
                            }
                        }
                    }
                    if (total < size) throw new IOException("Incomplete APK");
                    if (matches(digest.digest(), hash)) return !cancelled.get();
                    throw new IOException("APK checksum mismatch");
                } catch (IOException e) {
                    failure = e;
                    if (total == size) {
                        // If the whole bad file came from one mirror, switch. If it was resumed,
                        // give the last mirror a chance to supply a clean copy from the start.
                        if (from == 0) source = source.other();
                        out.getChannel().truncate(0);
                        digest.reset();
                        total = 0;
                    } else {
                        // A failed file write can leave a partial buffer on disk.
                        out.getChannel().truncate(total);
                        source = source.other();
                    }
                } finally {
                    if (connection != null) connection.disconnect();
                }
            }
        }
        if (cancelled.get()) return false;
        throw new IOException("APK mirrors failed", failure);
    }

    private static boolean matches(byte[] bytes, String expected) {
        if (expected.length() != bytes.length * 2) return false;
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 255;
            if (Character.forDigit(value >>> 4, 16) != expected.charAt(i * 2)
                    || Character.forDigit(value & 15, 16) != expected.charAt(i * 2 + 1)) return false;
        }
        return true;
    }
}
