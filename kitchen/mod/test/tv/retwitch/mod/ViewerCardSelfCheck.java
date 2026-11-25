package tv.retwitch.mod;

import java.util.HashMap;
import java.util.Map;

/** A public lookup and the local history must refer to the viewer in this fragment's navigation arguments. */
public final class ViewerCardSelfCheck {
    public static void main(String[] args) throws Exception {
        check(ViewerCardDetails.matches("42", "oldname", "42", "renamed"));
        check(!ViewerCardDetails.matches("42", "oldname", "43", "oldname"));
        check(ViewerCardDetails.matches("", "Chatter", "42", "chatter"));
        check(!ViewerCardDetails.matches("", "Chatter", "42", "other"));
        check(!ViewerCardDetails.matches("", "Chatter", "", "chatter"));
        check(!ViewerCardDetails.matches("", "", "42", ""));

        org.json.JSONObject active = new org.json.JSONObject().put("setID", "moderator").put("version", "1").put("title", "Moderator");
        org.json.JSONObject extra = new org.json.JSONObject().put("setID", "founder").put("version", "0").put("title", "Founder");
        org.json.JSONArray header = new org.json.JSONArray().put(active);
        org.json.JSONObject viewer = new org.json.JSONObject().put("id", "42:101")
                .put("earnedBadges", new org.json.JSONArray().put(active).put(extra).put(extra));
        org.json.JSONArray collection = ViewerCardDetails.otherBadges("42", "101", header, viewer);
        check(collection != null && collection.length() == 1 && collection.getJSONObject(0) == extra);
        check(ViewerCardDetails.otherBadges("42", "102", header, viewer) == null);
        check(ViewerCardDetails.otherBadges("43", "101", header, viewer) == null);
        viewer.put("earnedBadges", header);
        check(ViewerCardDetails.otherBadges("42", "101", header, viewer).length() == 0);
        viewer.put("earnedBadges", org.json.JSONObject.NULL);
        check(ViewerCardDetails.otherBadges("42", "101", header, viewer).length() == 0);
        viewer.remove("earnedBadges");
        check(ViewerCardDetails.otherBadges("42", "101", header, viewer) == null);

        Map<String, String> tags = new HashMap<>();
        tags.put("room-id", "101");
        tags.put("user-id", "42");
        tags.put("display-name", "Chatter");
        tags.put("id", "selected-message");
        UserHistory.note(tags, "test");
        check("Chatter".equals(UserHistory.tappedSender("101", "42", "selected-message")[1]));
        check("42".equals(UserHistory.tappedSender("101", "", "selected-message")[0]));
        check(UserHistory.tappedSender("102", "42", "selected-message") == null);
        check(UserHistory.tappedSender("101", "43", "selected-message") == null);
        check(UserHistory.tappedSender("101", "42", "other-message") == null);
        System.out.println("Viewer identity, channel-scoped badge collection and duplicate checks passed");
    }

    private static void check(boolean ok) {
        if (!ok) throw new AssertionError();
    }
}
