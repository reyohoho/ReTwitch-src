package tv.retwitch.mod;

/** Run with assertions enabled on the mod test classpath. */
public final class BottomTabsSelfCheck {
    public static void main(String[] args) {
        assert BottomTabs.valid(BottomTabs.DEFAULT);
        assert !BottomTabs.valid("01233");
        assert !BottomTabs.valid("54321");
        assert !BottomTabs.valid(null);
        String order = BottomTabs.move(BottomTabs.DEFAULT, 0, 3);
        assert "12304".equals(order);
        assert BottomTabs.DEFAULT.equals(BottomTabs.move(order, 3, 0));
        for (int from = 0; from < 5; from++) {
            for (int to = 0; to < 5; to++) {
                assert BottomTabs.valid(BottomTabs.move(order, from, to));
            }
        }
    }
}
