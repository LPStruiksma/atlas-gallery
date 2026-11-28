package nl.atlas.shareproof.publisher;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Build;
import android.os.Process;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Set;
import org.json.JSONObject;

public final class FixtureActivity extends Activity {
    private static final String RECIPIENT = "nl.atlas.shareproof.recipient";
    private static boolean id(String s) { return s != null && s.matches("[a-f0-9]{32}"); }

    // Only the two fixed synthetic outputs can be committed; no caller-supplied path or bytes.
    private void atomic(File dest, byte[] bytes) throws Exception {
        File tmp = File.createTempFile("proof-", ".tmp", getFilesDir());
        try {
            try (FileOutputStream stream = new FileOutputStream(tmp)) {
                stream.write(bytes); stream.getFD().sync();
            }
            if (dest.exists()) throw new IllegalStateException("duplicate output");
            Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.ATOMIC_MOVE);
        } finally { tmp.delete(); }
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent command = getIntent();
        String nonce = command.getStringExtra("caseNonce");
        String invocation = command.getStringExtra("invocationId");
        String phase = command.getStringExtra("phase");
        String caller = getCallingPackage();
        boolean valid = id(nonce) && id(invocation) && command.getExtras() != null
                && command.getExtras().keySet().equals(new java.util.HashSet<>(java.util.Arrays.asList("caseNonce", "invocationId", "phase")))
                && command.getData() == null && command.getClipData() == null
                && (command.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)) == 0;
        if (!valid || !RECIPIENT.equals(caller)
                || getPackageManager().checkSignatures(getPackageName(), caller)
                        != PackageManager.SIGNATURE_MATCH) {
            setResult(RESULT_CANCELED); finish(); return;
        }
        try {
            int count = getPreferences(MODE_PRIVATE).getInt("grant-" + nonce, 0);
            File output = new File(getFilesDir(), nonce + "-" + invocation + ".json");
            if (output.exists()) throw new IllegalStateException("duplicate output");
            Intent response = new Intent();
            if ("grant".equals(phase)) {
                if (count != 0) throw new IllegalStateException("duplicate grant");
                atomic(new File(getFilesDir(), "synthetic-payload"), FixtureProvider.PAYLOAD);
                if (!getPreferences(MODE_PRIVATE).edit().putInt("grant-" + nonce, 1).commit())
                    throw new IllegalStateException("grant count commit");
                count = 1;
                response.setAction(Intent.ACTION_SEND);
                response.setClipData(ClipData.newRawUri("synthetic", FixtureProvider.PROBE));
                response.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else if (!"status".equals(phase) || count != 1) {
                throw new IllegalStateException("invalid phase");
            }
            JSONObject record = new JSONObject();
            record.put("schemaVersion", 1).put("caseNonce", nonce).put("invocationId", invocation)
                    .put("phase", phase).put("api", Build.VERSION.SDK_INT)
                    .put("publisherUid", Process.myUid()).put("publisherPid", Process.myPid())
                    .put("verifiedCallerPackage", caller).put("outcome", "ok")
                    .put("grantIssuedCount", count);
            atomic(output, record.toString().getBytes(StandardCharsets.UTF_8));
            setResult(RESULT_OK, response);
        } catch (Exception failure) {
            setResult(RESULT_CANCELED);
        } finally { finish(); }
    }
}
