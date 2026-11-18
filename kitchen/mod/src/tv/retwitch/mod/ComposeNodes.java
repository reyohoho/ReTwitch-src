package tv.retwitch.mod;

import android.graphics.Matrix;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.LocaleList;
import android.view.View;
import android.view.ViewStructure;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillValue;

import java.util.ArrayList;
import java.util.List;

/** Compose semantics nodes of an AndroidComposeView through its accessibility provider: the debug server, mod clicks. */
final class ComposeNodes {

    private static final String COMPOSE_VIEW = "androidx.compose.ui.platform.AndroidComposeView";

    private ComposeNodes() {
    }

    /**
     * Compose semantics under an AndroidComposeView (its class name survives R8), pre-order: {node info, depth below
     * the host, semantics id}. Children of a virtual node have no public getter, so View.onProvideVirtualStructure
     * walks the accessibility provider for us into {@link Node}; AndroidComposeView only overrides the autofill one.
     */
    static List<Object[]> of(View host) {
        List<Object[]> out = new ArrayList<>();
        if (!COMPOSE_VIEW.equals(host.getClass().getName())) {
            return out;
        }
        AccessibilityNodeProvider provider = host.getAccessibilityNodeProvider();
        if (provider == null) {
            return out;
        }
        Node root = new Node();
        host.onProvideVirtualStructure(root);
        for (Node self : root.children) {
            if (self != null) {
                for (Node child : self.children) {
                    addVirtual(provider, child, 1, out);
                }
            }
        }
        return out;
    }

    private static void addVirtual(AccessibilityNodeProvider provider, Node n, int depth, List<Object[]> out) {
        if (n == null) {
            return;
        }
        AccessibilityNodeInfo info = provider.createAccessibilityNodeInfo(n.id);
        if (info != null) {
            out.add(new Object[]{info, depth, n.id});
        }
        for (Node child : n.children) {
            addVirtual(provider, child, depth + 1, out);
        }
    }

    /**
     * Clicks the node whose content description is {@code desc} (or, null, the first clickable one) the way TalkBack
     * would: the node's own click action, no touch. Main thread. @return whether a node took it.
     */
    static boolean click(View host, CharSequence desc) {
        AccessibilityNodeProvider provider = host.getAccessibilityNodeProvider();
        for (Object[] c : of(host)) {
            AccessibilityNodeInfo info = (AccessibilityNodeInfo) c[0];
            if (info.isClickable() && (desc == null || desc.toString().contentEquals(
                    info.getContentDescription() != null ? info.getContentDescription() : ""))) {
                return provider.performAction((Integer) c[2], AccessibilityNodeInfo.ACTION_CLICK, null);
            }
        }
        return false;
    }

    /** Keeps only the shape View.populateVirtualStructure writes: each node's virtual id and its children. */
    private static final class Node extends ViewStructure {
        int id = AccessibilityNodeProvider.HOST_VIEW_ID;
        final List<Node> children = new ArrayList<>();

        @Override public void setId(int id, String packageName, String typeName, String entryName) { this.id = id; }
        @Override public void setChildCount(int num) {
            while (children.size() < num) {
                children.add(null);
            }
        }
        @Override public int addChildCount(int num) {
            int old = children.size();
            setChildCount(old + num);
            return old;
        }
        @Override public int getChildCount() { return children.size(); }
        @Override public ViewStructure newChild(int index) {
            setChildCount(index + 1);
            Node child = new Node();
            children.set(index, child);
            return child;
        }
        @Override public ViewStructure asyncNewChild(int index) { return newChild(index); }
        /** Hidden abstract method (not in the SDK stubs) that View.populateVirtualStructure calls. */
        public Rect getTempRect() { return tempRect; }
        private final Rect tempRect = new Rect();
        @Override public void asyncCommit() { }
        @Override public AutofillId getAutofillId() { return null; }
        @Override public Bundle getExtras() { return new Bundle(); }
        @Override public CharSequence getHint() { return null; }
        @Override public CharSequence getText() { return null; }
        @Override public int getTextSelectionEnd() { return -1; }
        @Override public int getTextSelectionStart() { return -1; }
        @Override public boolean hasExtras() { return false; }
        @Override public ViewStructure.HtmlInfo.Builder newHtmlInfoBuilder(String tagName) { return null; }
        @Override public void setAccessibilityFocused(boolean state) { }
        @Override public void setActivated(boolean state) { }
        @Override public void setAlpha(float alpha) { }
        @Override public void setAutofillHints(String[] hint) { }
        @Override public void setAutofillId(AutofillId id) { }
        @Override public void setAutofillId(AutofillId parentId, int virtualId) { }
        @Override public void setAutofillOptions(CharSequence[] options) { }
        @Override public void setAutofillType(int type) { }
        @Override public void setAutofillValue(AutofillValue value) { }
        @Override public void setCheckable(boolean state) { }
        @Override public void setChecked(boolean state) { }
        @Override public void setClassName(String className) { }
        @Override public void setClickable(boolean state) { }
        @Override public void setContentDescription(CharSequence contentDescription) { }
        @Override public void setContextClickable(boolean state) { }
        @Override public void setDataIsSensitive(boolean sensitive) { }
        @Override public void setDimens(int left, int top, int scrollX, int scrollY, int width, int height) { }
        @Override public void setElevation(float elevation) { }
        @Override public void setEnabled(boolean state) { }
        @Override public void setFocusable(boolean state) { }
        @Override public void setFocused(boolean state) { }
        @Override public void setHint(CharSequence hint) { }
        @Override public void setHtmlInfo(ViewStructure.HtmlInfo htmlInfo) { }
        @Override public void setInputType(int inputType) { }
        @Override public void setLocaleList(LocaleList localeList) { }
        @Override public void setLongClickable(boolean state) { }
        @Override public void setOpaque(boolean opaque) { }
        @Override public void setSelected(boolean state) { }
        @Override public void setText(CharSequence text) { }
        @Override public void setText(CharSequence text, int selectionStart, int selectionEnd) { }
        @Override public void setTextLines(int[] charOffsets, int[] baselines) { }
        @Override public void setTextStyle(float size, int fgColor, int bgColor, int style) { }
        @Override public void setTransformation(Matrix matrix) { }
        @Override public void setVisibility(int visibility) { }
        @Override public void setWebDomain(String domain) { }
    }

}
