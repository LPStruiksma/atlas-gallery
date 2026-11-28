package nl.atlas.shareproof.recipient;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.json.JSONObject;

public final class RecipientActivity extends Activity {
    private static final String PUBLISHER = "nl.atlas.shareproof.publisher";
    private static final Uri EXPECTED = Uri.parse("content://nl.atlas.shareproof.publisher.proof/probe");
    private static final String COMMAND = "nl.atlas.shareproof.recipient.COMMAND";
    private static final int REQUEST = 17;
    private static final int GRANT_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION
            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION;
    private final String instance = UUID.randomUUID().toString();
    private final Set<String> seen = new HashSet<>();
    private String nonce, invocation, phase;
    private Uri retained;
    private boolean awaiting;
    private BroadcastReceiver receiver;

    private static boolean id(String s) { return s != null && s.matches("[a-f0-9]{32}"); }
    private boolean command(Intent command, boolean initial) {
        String n = command.getStringExtra("caseNonce");
        String i = command.getStringExtra("invocationId");
        String p = command.getStringExtra("phase");
        if (!id(n) || !id(i) || command.getExtras() == null
                || !command.getExtras().keySet().equals(new java.util.HashSet<>(java.util.Arrays.asList("caseNonce", "invocationId", "phase")))
                || command.getData() != null || command.getClipData() != null
                || (command.getFlags() & GRANT_FLAGS) != 0 || awaiting) return false;
        if (initial ? !"grant".equals(p) : !n.equals(nonce)
                || !new java.util.HashSet<>(java.util.Arrays.asList("reread", "post-kill", "status")).contains(p)) return false;
        if (!seen.add(i)) return false;
        nonce = n; invocation = i; phase = p;
        return true;
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null || !command(getIntent(), true)) { finish(); return; }
        receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (!COMMAND.equals(intent.getAction()) || !command(intent, false) || retained == null) return;
                if ("status".equals(phase)) requestPublisher(); else read();
            }
        };
        IntentFilter filter = new IntentFilter(COMMAND);
        if (Build.VERSION.SDK_INT >= 33)
            getApplicationContext().registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        else getApplicationContext().registerReceiver(receiver, filter);
        requestPublisher();
    }

    private void requestPublisher() {
        awaiting = true;
        Intent request = new Intent().setComponent(new ComponentName(PUBLISHER, PUBLISHER + ".FixtureActivity"));
        request.putExtra("caseNonce", nonce).putExtra("invocationId", invocation).putExtra("phase", phase);
        startActivityForResult(request, REQUEST);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST || !awaiting) return;
        awaiting = false;
        if (resultCode != RESULT_OK || data == null) { failure("PublisherRejected"); return; }
        if ("grant".equals(phase)) {
            if (!Intent.ACTION_SEND.equals(data.getAction()) || data.getClipData() == null
                    || data.getClipData().getItemCount() != 1
                    || !EXPECTED.equals(data.getClipData().getItemAt(0).getUri())
                    || (data.getFlags() & GRANT_FLAGS) != Intent.FLAG_GRANT_READ_URI_PERMISSION) {
                failure("InvalidGrant"); return;
            }
            retained = data.getClipData().getItemAt(0).getUri();
            read();
        } else if ("status".equals(phase)) {
            if (data.getData() != null || data.getClipData() != null || (data.getFlags() & GRANT_FLAGS) != 0) {
                failure("UnexpectedGrant"); return;
            }
            publish(null, null, null, null, true);
        }
    }

    private void read() {
        String mime = null;
        Integer length = null;
        String hash = null;
        String error = null;
        try {
            if (!EXPECTED.equals(retained)) throw new SecurityException("wrong resource");
            mime = getContentResolver().getType(retained); // first resolver call of this invocation
            try (Cursor cursor = getContentResolver().query(retained,
                    new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
                if (cursor == null || !cursor.moveToFirst() || cursor.getCount() != 1
                        || !"synthetic.bin".equals(cursor.getString(0))
                        || cursor.getLong(1) != 21) throw new IllegalStateException("metadata");
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            int total = 0;
            try (InputStream stream = getContentResolver().openInputStream(retained)) {
                if (stream == null) throw new IllegalStateException("missing stream");
                byte[] buffer = new byte[128];
                int n;
                while ((n = stream.read(buffer)) != -1) {
                    total += n;
                    if (total > 128) throw new IllegalStateException("synthetic size bound");
                    digest.update(buffer, 0, n);
                }
            }
            length = total;
            StringBuilder sb = new StringBuilder();
            for (byte b : digest.digest()) sb.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            hash = sb.toString();
        } catch (Exception e) { error = e.getClass().getSimpleName(); }
        publish(mime, length, hash, error, true);
    }
    private void failure(String code) { publish(null, null, null, code, true); }
    private void publish(String mime, Integer length, String hash, String error, boolean released) {
        try {
            JSONObject record = new JSONObject();
            record.put("schemaVersion", 2).put("caseNonce", nonce).put("invocationId", invocation)
                    .put("phase", phase).put("api", Build.VERSION.SDK_INT)
                    .put("uid", Process.myUid()).put("pid", Process.myPid())
                    .put("taskId", getTaskId()).put("activityInstanceId", instance)
                    .put("mime", mime == null ? JSONObject.NULL : mime)
                    .put("length", length == null ? JSONObject.NULL : length)
                    .put("sha256", hash == null ? JSONObject.NULL : hash)
                    .put("exceptionClass", error == null ? JSONObject.NULL : error)
                    .put("released", released).put("ownerFinished", false);
            File target = new File(getFilesDir(), nonce + "-" + invocation + ".json");
            if (target.exists()) throw new IllegalStateException("duplicate result");
            File tmp = File.createTempFile("proof-", ".tmp", getFilesDir());
            try {
                try (FileOutputStream stream = new FileOutputStream(tmp)) {
                    stream.write(record.toString().getBytes(StandardCharsets.UTF_8)); stream.getFD().sync();
                }
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE);
            } finally { tmp.delete(); }
        } catch (Exception failure) { finish(); }
    }

    @Override protected void onDestroy() {
        if (receiver != null) getApplicationContext().unregisterReceiver(receiver);
        super.onDestroy();
    }
}
