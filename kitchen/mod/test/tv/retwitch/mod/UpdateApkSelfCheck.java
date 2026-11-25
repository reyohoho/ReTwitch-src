package tv.retwitch.mod;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static tv.retwitch.mod.UpdateApk.Source.GITHUB;
import static tv.retwitch.mod.UpdateApk.Source.YANDEX;

/** javac -d /tmp/rt-tests kitchen/mod/{src/tv/retwitch/mod/UpdateApk,test/tv/retwitch/mod/UpdateApkSelfCheck}.java
 *  java -ea -cp /tmp/rt-tests tv.retwitch.mod.UpdateApkSelfCheck */
public final class UpdateApkSelfCheck {
    private static final byte[] APK = {1, 2, 3, 4, 5, 6};
    private static final byte[] CUT = {1, 2, 3};
    private static final byte[] TAIL = {4, 5, 6};
    private static final byte[] WRONG = {9, 9, 9, 9, 9, 9};

    private static HttpURLConnection response(int code, String range, byte[] data) {
        return new HttpURLConnection(null) {
            public void connect() {}
            public void disconnect() {}
            public boolean usingProxy() { return false; }
            public int getResponseCode() { return code; }
            public String getHeaderField(String name) { return "Content-Range".equals(name) ? range : null; }
            public InputStream getInputStream() { return new ByteArrayInputStream(data); }
        };
    }

    private static String sha() throws Exception {
        StringBuilder s = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(APK)) s.append(String.format("%02x", b));
        return s.toString();
    }

    private static void verify(File f, UpdateApk.Source first, String expected, HttpURLConnection... replies) throws Exception {
        List<String> requests = new ArrayList<>();
        boolean ok = UpdateApk.fetch(f, APK.length, sha(), first, new AtomicBoolean(), (mirror, offset) -> {
            HttpURLConnection answer = replies[requests.size()];
            requests.add(mirror + "@" + offset);
            return answer;
        }, (mirror, bytes) -> {});
        assert ok && Arrays.equals(APK, Files.readAllBytes(f.toPath()));
        assert expected.equals(String.join(",", requests)) : requests;
    }

    public static void main(String[] args) throws Exception {
        File f = File.createTempFile("retwitch-update-", ".apk");
        try {
            for (UpdateApk.Source first : UpdateApk.Source.values()) {
                String a = first.name(), b = first.other().name();
                verify(f, first, a+"@0", response(200, null, APK));
                verify(f, first, a+"@0,"+b+"@0", response(404, null, new byte[0]), response(200, null, APK));
                verify(f, first, a+"@0,"+b+"@3", response(200, null, CUT), response(206, "bytes 3-5/6", TAIL));
                verify(f, first, a+"@0,"+b+"@3", response(200, null, CUT), response(200, null, APK));
                verify(f, first, a+"@0,"+b+"@3,"+a+"@3", response(200, null, CUT),
                        response(206, "bytes 0-2/6", CUT), response(206, "bytes 3-5/6", TAIL));
                verify(f, first, a+"@0,"+b+"@0", response(200, null, WRONG), response(200, null, APK));
                verify(f, first, a+"@0,"+b+"@3,"+b+"@0", response(200, null, new byte[]{9,9,9}),
                        response(206, "bytes 3-5/6", TAIL), response(200, null, APK));
            }
            AtomicBoolean cancel = new AtomicBoolean();
            assert !UpdateApk.fetch(f, APK.length, sha(), GITHUB, cancel, (mirror, offset) -> {
                cancel.set(true); return response(200, null, APK);
            }, (mirror, bytes) -> {});
            try {
                UpdateApk.fetch(f, APK.length, sha(), YANDEX, new AtomicBoolean(),
                        (mirror, offset) -> response(200, null, WRONG), (mirror, bytes) -> {});
                throw new AssertionError("Corrupt APK accepted");
            } catch (IOException expected) {
                assert expected.getCause().getMessage().contains("checksum");
            }
            System.out.println("APK mirrors: both preferences, fallback, byte ranges, checksum and cancellation OK");
        } finally {
            Files.deleteIfExists(f.toPath());
        }
    }
}
