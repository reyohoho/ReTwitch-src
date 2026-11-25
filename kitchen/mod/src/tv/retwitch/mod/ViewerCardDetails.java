package tv.retwitch.mod;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.graphics.Color;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.HorizontalScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import tv.retwitch.mod.emotes.CardCosmetics;

/** Public profile data for a blank native sheet. Twitch can challenge account-specific fields before rendering ViewerCardInfo. */
public final class ViewerCardDetails {
    private static final String TAG = "RTViewerCard";
    private static final int TARGET_TAG = 0x7e700d79;
    private static final String FIELDS = "id login displayName description createdAt chatColor profileImageURL(width:150)";
    private static final String BADGE_FIELDS = "{setID version title imageURL(size:DOUBLE)}";
    private static final String BADGES = " displayBadges(channelID:$room)" + BADGE_FIELDS;
    static final String EARNED_QUERY = "query($viewer:String!,$channel:String!){channelViewer(userLogin:$viewer,channelLogin:$channel){id earnedBadges"
            + BADGE_FIELDS + "}}";
    private final Object fragment;
    private final View root;
    private final TextView title;
    private final String getter, fetch, byId, login, sourceRoom;
    final String room;
    String userId, historyName;
    private LinearLayout details;
    private TextView description, status;
    private LinearLayout badges;
    private boolean verified;
    private Button retry, gallery;
    private int request;
    private final Object publicLabel = new Object();

    private ViewerCardDetails(Object fragment, View root, TextView title, Bundle args, String getter, String fetch) {
        this.fragment = fragment;
        this.root = root;
        this.title = title;
        this.getter = getter;
        this.fetch = fetch;
        room = validId(args.getString("channelId"));
        sourceRoom = validId(args.getString("sourceChannelId"));
        String selected = validId(args.getString("targetId"));
        String[] seen = UserHistory.tappedSender(room, selected, args.getString("messageId"));
        byId = selected.isEmpty() && seen != null ? validId(seen[0]) : selected;
        userId = byId;
        String name = args.getString("targetUserName", "").trim();
        login = name.matches("[a-zA-Z0-9_]{1,25}") ? name : "";
        historyName = seen != null ? seen[1] : name;
    }

    private static String validId(String s) { return s != null && s.matches("[1-9][0-9]{0,19}") ? s : ""; }

    static boolean matches(String requestedId, String requestedLogin, String id, String account) {
        if (validId(id).isEmpty() || account == null || !account.matches("[A-Za-z0-9_]{1,25}")) return false;
        return requestedId.isEmpty() ? account.equalsIgnoreCase(requestedLogin) : requestedId.equals(id);
    }

    static ViewerCardDetails of(TextView title) {
        Object value = title.getTag(TARGET_TAG);
        return value instanceof ViewerCardDetails ? (ViewerCardDetails) value : null;
    }

    private boolean hasNativeName() {
        CharSequence text = title.getText();
        return text.length() != 0 && (!(text instanceof Spanned) || ((Spanned) text).getSpanStart(publicLabel) < 0);
    }

    /** An empty ID suppresses cosmetics until this sheet has a native identity or a verified public result. */
    public static String targetId(TextView title) {
        ViewerCardDetails card = of(title);
        if (card == null) return null;
        if (!card.verified && !card.hasNativeName()) return "";
        return card.userId.isEmpty() ? null : card.userId;
    }

    public static LinearLayout publicBadgeRow(TextView title) {
        ViewerCardDetails card = of(title);
        return card != null && card.verified && !card.hasNativeName() ? card.badges : null;
    }

    static String profileQuery(boolean byLogin, boolean withRoom) {
        String target = byLogin ? "$s:String!" : "$s:ID!";
        return "query(" + target + (withRoom ? ",$room:ID!" : "") + "){user(" + (byLogin ? "login" : "id")
                + ":$s){" + FIELDS + (withRoom ? BADGES : "") + "}"
                + (withRoom ? " channelUser:user(id:$room){id login}" : "") + "}";
    }

    /** The navigation bundle belongs to this fragment, including when two viewer cards are opened successively. */
    public static void bind(Object fragment, View root, String getter, String fetch) {
        try {
            Bundle args = (Bundle) fragment.getClass().getMethod("getArguments").invoke(fragment);
            TextView title = root.findViewById(UiHider.id(root.getContext(), "target_username"));
            if (args == null || title == null || of(title) != null) return;
            ViewerCardDetails card = new ViewerCardDetails(fragment, root, title, args, getter, fetch);
            title.setTag(TARGET_TAG, card);
            if (!card.historyName.isEmpty()) {
                title.setHintTextColor(title.getTextColors());
                title.setHint(card.historyName);
            }
            if (DebugServer.enabled()) Log.i(TAG, "open targetId=" + !card.byId.isEmpty() + " login="
                    + !card.login.isEmpty() + " room=" + !card.room.isEmpty());
            root.postDelayed(card::loadIfEmpty, 2500);
        } catch (Throwable t) {
            Log.w(TAG, "could not bind viewer sheet", t);
            CrashReporter.report(TAG, "could not bind viewer sheet", t);
        }
    }

    private boolean empty() {
        return root.isAttachedToWindow() && of(title) == this && !hasNativeName();
    }

    private void loadIfEmpty() {
        if (!empty()) return;
        try {
            if (details == null) {
                View message = root.findViewById(UiHider.id(root.getContext(), "viewer_card_chat_message"));
                if (message == null || !(message.getParent() instanceof LinearLayout)) return;
                LinearLayout list = (LinearLayout) message.getParent();
                Context c = root.getContext();
                details = new LinearLayout(c);
                details.setOrientation(LinearLayout.VERTICAL);
                int pad = Math.round(16 * c.getResources().getDisplayMetrics().density);
                details.setPadding(pad, pad / 2, pad, 0);
                description = new TextView(c);
                description.setTextColor(title.getCurrentTextColor());
                details.addView(description);
                HorizontalScrollView scroller = new HorizontalScrollView(c);
                scroller.setHorizontalScrollBarEnabled(false);
                badges = new LinearLayout(c);
                badges.setGravity(android.view.Gravity.CENTER_VERTICAL);
                scroller.addView(badges);
                details.addView(scroller);
                gallery = new Button(c);
                gallery.setVisibility(View.GONE);
                details.addView(gallery);
                status = new TextView(c);
                status.setTextColor(title.getCurrentTextColor());
                details.addView(status);
                retry = new Button(c);
                retry.setText(Strings.pick("Повторить загрузку карточки", "Retry loading viewer card"));
                retry.setOnClickListener(v -> retry());
                details.addView(retry);
                list.addView(details, list.indexOfChild(message) + 1);
                root.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
                    if (hasNativeName() && details.getVisibility() != View.GONE) details.setVisibility(View.GONE);
                });
            }
            retry.setVisibility(View.GONE);
            status.setText(Strings.pick("Загрузка профиля зрителя...", "Loading viewer profile..."));
            status.setVisibility(View.VISIBLE);
            if (byId.isEmpty() && login.isEmpty()) {
                status.setText(Strings.pick("Не удалось определить зрителя для этой карточки",
                        "Could not identify the viewer for this card"));
                return;
            }
            final int current = ++request;
            new Thread(() -> {
                Handler main = new Handler(Looper.getMainLooper());
                try {
                    JSONObject variables = new JSONObject().put("s", byId.isEmpty() ? login : byId);
                    if (!room.isEmpty()) variables.put("room", room);
                    JSONObject data = VodDownload.gql(profileQuery(byId.isEmpty(), !room.isEmpty()), variables);
                    JSONObject user = data.optJSONObject("user");
                    if (user == null || !matches(byId, login, user.optString("id"), user.optString("login"))) {
                        throw new java.io.IOException("Viewer identity mismatch");
                    }
                    main.post(() -> { if (current == request) display(user); });
                    if (room.isEmpty()) return;
                    JSONArray earned = null;
                    try {
                        JSONObject channel = data.optJSONObject("channelUser");
                        if (channel != null && matches(room, "", channel.optString("id"), channel.optString("login"))) {
                            JSONObject args = new JSONObject().put("viewer", user.getString("login")).put("channel", channel.getString("login"));
                            JSONObject response = VodDownload.gqlResponse(VodDownload.HTTP, EARNED_QUERY, args);
                            JSONArray errors = response.optJSONArray("errors");
                            if (errors == null || errors.length() == 0) {
                                JSONObject viewer = response.getJSONObject("data").optJSONObject("channelViewer");
                                earned = otherBadges(user.getString("id"), room, user.optJSONArray("displayBadges"), viewer);
                            }
                        }
                    } catch (Exception e) {
                        if (DebugServer.enabled()) Log.i(TAG, "earned badges error=" + e.getClass().getSimpleName());
                    }
                    final JSONArray result = earned;
                    main.post(() -> { if (current == request) displayEarned(result, user.optJSONArray("displayBadges") != null); });
                } catch (Exception e) {
                    if (DebugServer.enabled()) Log.i(TAG, "public profile error=" + e.getClass().getSimpleName());
                    main.post(() -> { if (current == request) display(null); });
                }
            }, "RTViewerProfile").start();
        } catch (RuntimeException e) {
            Log.w(TAG, "could not display profile status", e);
            CrashReporter.report(TAG, "could not display profile status", e);
        }
    }

    /** The web card's ChannelViewer ID is viewerID:channelID. Both lists identify a badge by set and version. */
    static JSONArray otherBadges(String target, String room, JSONArray display, JSONObject viewer) {
        if (validId(target).isEmpty() || validId(room).isEmpty() || viewer == null ||
                !(target + ":" + room).equals(viewer.optString("id"))) return null;
        if (!viewer.has("earnedBadges")) return null;
        if (viewer.isNull("earnedBadges")) return new JSONArray();
        JSONArray earned = viewer.optJSONArray("earnedBadges");
        if (earned == null) return null;
        HashSet<String> seen = new HashSet<>();
        JSONArray extra = new JSONArray();
        for (int pass = 0; pass < 2; pass++) {
            JSONArray group = pass == 0 ? display : earned;
            if (group == null) continue;
            for (int i = 0; i < group.length(); i++) {
                JSONObject badge = group.optJSONObject(i);
                if (badge == null || badge.isNull("setID") || badge.isNull("version") || badge.isNull("title")
                        || badge.optString("title").isEmpty()) continue;
                if (seen.add(badge.optString("setID") + "/" + badge.optString("version")) && pass == 1) extra.put(badge);
            }
        }
        return extra;
    }

    private void displayEarned(JSONArray extra, boolean hasDisplayBadges) {
        if (!empty()) return;
        if (extra == null) {
            status.setText(Strings.pick("Не удалось загрузить все значки зрителя", "Could not load all of this viewer's badges"));
            status.setVisibility(View.VISIBLE);
            retry.setText(Strings.pick("Повторить загрузку карточки", "Retry loading viewer card"));
            retry.setVisibility(View.VISIBLE);
            return;
        }
        gallery.setVisibility(extra.length() == 0 ? View.GONE : View.VISIBLE);
        if (extra.length() > 0) {
            String caption = hasDisplayBadges ? Strings.pickf("Другие полученные значки (%d)", "Other earned badges (%d)", extra.length())
                    : Strings.pickf("Полученные значки (%d)", "Earned badges (%d)", extra.length());
            gallery.setText(caption);
            gallery.setOnClickListener(v -> CardCosmetics.showEarnedBadges(root.getContext(), caption, extra));
        }
    }

    private void display(JSONObject user) {
        if (!empty()) return;
        if (DebugServer.enabled()) Log.i(TAG, "public profile available=" + (user != null));
        if (user == null) {
            status.setText(Strings.pick("Не удалось загрузить профиль зрителя", "Could not load the viewer profile"));
            status.setVisibility(View.VISIBLE);
            retry.setText(Strings.pick("Повторить загрузку карточки", "Retry loading viewer card"));
        } else {
            userId = validId(user.optString("id"));
            String account = user.optString("login");
            String display = user.isNull("displayName") ? "" : user.optString("displayName");
            if (display.isEmpty()) display = account;
            historyName = display;
            SpannableString label = new SpannableString(display.equalsIgnoreCase(account) ? display : display + " (" + account + ")");
            label.setSpan(publicLabel, 0, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            String color = user.optString("chatColor");
            if (color.matches("#[a-fA-F0-9]{6}")) label.setSpan(new ForegroundColorSpan(Color.parseColor(color)),
                    0, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            verified = true;
            title.setText(label);
            String bio = user.isNull("description") ? "" : user.optString("description");
            String created = createdDate(user.optString("createdAt"));
            String info = created.isEmpty() ? bio : (bio.isEmpty() ? "" : bio + "\n")
                    + Strings.pickf("Аккаунт создан %s", "Account created %s", created);
            description.setText(info);
            description.setVisibility(info.isEmpty() ? View.GONE : View.VISIBLE);
            JSONArray assigned = user.optJSONArray("displayBadges");
            gallery.setVisibility(View.GONE);
            CardCosmetics.showTwitchBadges(badges, assigned);
            CardCosmetics.apply(root.getContext(), root);
            boolean missingBadges = !room.isEmpty() && assigned == null;
            if (missingBadges) status.setText(Strings.pick("Не удалось загрузить значки зрителя", "Could not load this viewer's badges"));
            if (room.isEmpty()) status.setText(Strings.pick("Не удалось определить канал для значков", "Could not identify the channel for these badges"));
            status.setVisibility(missingBadges || room.isEmpty() ? View.VISIBLE : View.GONE);
            // Following, subscription details and actions can require the native viewer's permissions.
            retry.setText(missingBadges ? Strings.pick("Повторить загрузку карточки", "Retry loading viewer card")
                    : Strings.pick("Загрузить данные канала", "Load channel details"));
            View avatar = root.findViewById(UiHider.id(root.getContext(), "user_avatar"));
            String url = user.optString("profileImageURL", "");
            if (avatar != null && url.startsWith("https://")) {
                try { avatar.getClass().getMethod("setAvatarUrl", String.class).invoke(avatar, url); }
                catch (ReflectiveOperationException e) { Log.w(TAG, "could not set viewer avatar", e); }
            }
        }
        retry.setVisibility(user == null || !room.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private String createdDate(String value) {
        try {
            return DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
                    .withLocale(root.getResources().getConfiguration().getLocales().get(0))
                    .withZone(ZoneId.systemDefault()).format(Instant.parse(value));
        } catch (java.time.DateTimeException e) {
            return "";
        }
    }

    private void retry() {
        if (!empty() || retry.getVisibility() != View.VISIBLE) return;
        request++;
        retry.setVisibility(View.GONE);
        status.setVisibility(View.VISIBLE);
        status.setText(Strings.pick("Загрузка профиля зрителя...", "Loading viewer profile..."));
        if (!room.isEmpty() && !userId.isEmpty()) {
            try {
                Object vm = fragment.getClass().getMethod(getter).invoke(fragment);
                vm.getClass().getMethod(fetch, String.class, String.class, String.class)
                        .invoke(vm, userId, room, sourceRoom.isEmpty() ? null : sourceRoom);
            } catch (ReflectiveOperationException e) {
                Log.w(TAG, "native viewer query retry unavailable", e);
            }
        }
        root.postDelayed(this::loadIfEmpty, 2000);
    }

}
