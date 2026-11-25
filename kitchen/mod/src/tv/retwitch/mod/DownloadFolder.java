package tv.retwitch.mod;

import android.app.Activity;
import android.app.Fragment;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.util.Log;
import android.widget.Toast;

import java.io.IOException;
import java.lang.ref.WeakReference;

/** Persistent SAF destinations are device-local. Recording metadata keeps its original document URI. */
public final class DownloadFolder extends Fragment {
    static final String PREFS = "retwitch_folder_grants";
    private static final String TAG = "RTFolder";
    private static final int ACCESS = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
    private static final int REQUEST_TREE = 1;
    private WeakReference<SettingsScreen> caller;

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static Uri tree(Context c, String kind) {
        String value = prefs(c).getString(kind, "");
        return value.isEmpty() ? null : Uri.parse(value);
    }

    static String label(Context c, String kind) {
        Uri tree = tree(c, kind);
        return tree == null ? Strings.pickf("Загрузки/%s", "Downloads/%s", RecordSettings.folder(kind))
                : DocumentsContract.getTreeDocumentId(tree);
    }

    static void reset(Context c, String kind) {
        // Old recordings can still need this tree grant to play, resume or delete their document.
        prefs(c).edit().remove(kind).apply();
    }

    static Uri create(Context c, Uri tree, String name, String mime) throws IOException {
        Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
        Uri file = DocumentsContract.createDocument(c.getContentResolver(), parent, mime, name);
        if (file == null) throw new IOException("Folder provider refused " + name);
        return file;
    }

    static void pick(SettingsScreen screen, String kind) {
        Context c = screen.context;
        Activity activity = SettingsBackup.activity(c);
        if (activity == null) return;
        DownloadFolder fragment = new DownloadFolder();
        try {
            Bundle args = new Bundle();
            args.putString("kind", kind);
            fragment.setArguments(args);
            fragment.caller = new WeakReference<>(screen);
            activity.getFragmentManager().beginTransaction().add(fragment, TAG).commitNow();
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                    .addFlags(ACCESS | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
                    .putExtra(Intent.EXTRA_LOCAL_ONLY, true);
            Uri current = tree(c, kind);
            if (current != null) intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, current);
            fragment.startActivityForResult(intent, REQUEST_TREE);
        } catch (Throwable t) {
            if (fragment.isAdded()) activity.getFragmentManager().beginTransaction().remove(fragment).commitAllowingStateLoss();
            Log.w(TAG, "folder picker failed", t);
            CrashReporter.report(TAG, "folder picker failed", t);
            Toast.makeText(c, Strings.backupNoPicker(), Toast.LENGTH_LONG).show();
        }
    }

    @Override public void onActivityResult(int request, int result, Intent data) {
        if (request != REQUEST_TREE) return;
        Activity activity = getActivity();
        if (activity == null) return;
        try {
            if (result == Activity.RESULT_OK && data != null && data.getData() != null) {
                Uri uri = data.getData();
                if ((data.getFlags() & ACCESS) != ACCESS || !DocumentsContract.isTreeUri(uri)) {
                    throw new SecurityException("The selected folder did not grant read/write access");
                }
                activity.getContentResolver().takePersistableUriPermission(uri, ACCESS);
                String kind = getArguments().getString("kind");
                prefs(activity).edit().putString(kind, uri.toString()).apply();
                SettingsScreen screen = caller != null ? caller.get() : null;
                if (screen != null) screen.refreshAfterPicker(activity);
                Toast.makeText(activity, label(activity, kind), Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable t) {
            Log.w(TAG, "cannot use selected folder", t);
            CrashReporter.report(TAG, "cannot use selected folder", t);
            Toast.makeText(activity, Strings.pick("Нет доступа к выбранной папке", "Cannot access the selected folder"), Toast.LENGTH_LONG).show();
        } finally {
            if (isAdded()) getFragmentManager().beginTransaction().remove(this).commitAllowingStateLoss();
        }
    }
}
