package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Fire OS cleanup, CleanYourTV's tools/disable.sh run from the stick itself: backup, pm disable-user for the chosen
 * categories of assets/debloat-fire-os7.txt, a restore record, and the Home redirect for this launcher (HomeKeyService
 * with stock_home_redirect). Every command goes through LocalAdb, the stick's own adbd.
 */
public class CleanupActivity extends Activity {

    private static final String PREFS = "cleanup";
    /** alarm_manager_constants before this app changed it ("" for unset), so turning the launcher off puts it back. */
    private static final String KEY_ALARM_ORIGINAL = "alarm_original";
    private static final String RECORD_NAME = "ripristino.txt";

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<TextView> categoryRows = new ArrayList<>();
    private List<DebloatList.Category> categories = new ArrayList<>();
    private boolean[] chosen = new boolean[0];
    private SharedPreferences prefs;
    private LocalAdb adb;
    private volatile boolean busy;

    private TextView status;
    private TextView log;
    private TextView launcherRow;

    private interface Step {
        void run() throws IOException;
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_cleanup);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        status = findViewById(R.id.cleanupStatus);
        log = findViewById(R.id.cleanupLog);
        launcherRow = findViewById(R.id.cleanupLauncher);

        try (InputStreamReader in = new InputStreamReader(getAssets().open(DebloatList.ASSET), StandardCharsets.UTF_8)) {
            categories = DebloatList.parse(in);
        } catch (IOException e) {
            append("Lista non leggibile: " + e.getMessage());
        }
        chosen = new boolean[categories.size()];
        LinearLayout box = findViewById(R.id.cleanupCategories);
        LayoutInflater inflater = getLayoutInflater();
        for (int i = 0; i < categories.size(); i++) {
            final int index = i;
            chosen[i] = prefs.getBoolean("category_" + categories.get(i).id, categories.get(i).defaultOn);
            TextView row = (TextView) inflater.inflate(R.layout.cleanup_category_row, box, false);
            row.setOnClickListener(v -> {
                chosen[index] = !chosen[index];
                prefs.edit().putBoolean("category_" + categories.get(index).id, chosen[index]).apply();
                refreshCategories();
            });
            box.addView(row);
            categoryRows.add(row);
        }
        refreshCategories();

        findViewById(R.id.cleanupConnect).setOnClickListener(v -> run(this::connect));
        findViewById(R.id.cleanupBackup).setOnClickListener(v -> run(this::backup));
        findViewById(R.id.cleanupApply).setOnClickListener(v -> run(this::apply));
        launcherRow.setOnClickListener(v -> {
            boolean on = redirectOn();
            run(on ? this::launcherOff : this::launcherOn);
        });
        findViewById(R.id.cleanupExtras).setOnClickListener(v -> run(this::extras));
        findViewById(R.id.cleanupPrivacy).setOnClickListener(v -> openSettings());
        findViewById(R.id.cleanupOpenLauncher).setOnClickListener(v ->
                startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        findViewById(R.id.cleanupRestore).setOnClickListener(v -> new AlertDialog.Builder(this)
                .setMessage(R.string.cleanup_restore_confirm)
                .setPositiveButton(android.R.string.ok, (d, w) -> run(this::restore))
                .setNegativeButton(android.R.string.cancel, null)
                .show());
        findViewById(R.id.cleanupAdbOff).setOnClickListener(v -> new AlertDialog.Builder(this)
                .setMessage(R.string.cleanup_adb_off_confirm)
                .setPositiveButton(android.R.string.ok, (d, w) -> run(this::adbOff))
                .setNegativeButton(android.R.string.cancel, null)
                .show());
        refreshLauncherRow();
        findViewById(R.id.cleanupConnect).requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshLauncherRow();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        worker.execute(() -> {
            if (adb != null) adb.close();
        });
        worker.shutdown();
    }

    private void refreshCategories() {
        for (int i = 0; i < categoryRows.size(); i++) {
            DebloatList.Category category = categories.get(i);
            categoryRows.get(i).setText(getString(R.string.cleanup_category_row,
                    chosen[i] ? "☑" : "☐", category.title, category.entries.size()));
        }
    }

    private boolean redirectOn() {
        String services = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return services != null && services.contains(serviceComponent());
    }

    private void refreshLauncherRow() {
        launcherRow.setText(redirectOn() ? R.string.cleanup_launcher_off : R.string.cleanup_launcher_on);
    }

    private String serviceComponent() {
        return getPackageName() + "/" + HomeKeyService.class.getName();
    }

    // ---- Background steps -------------------------------------------------------------------------------------------

    private void run(Step step) {
        if (busy) {
            Toast.makeText(this, R.string.cleanup_busy, Toast.LENGTH_SHORT).show();
            return;
        }
        busy = true;
        worker.execute(() -> {
            try {
                step.run();
            } catch (IOException | RuntimeException e) {
                append("Errore: " + e.getMessage());
            } finally {
                busy = false;
                main.post(this::refreshLauncherRow);
            }
        });
    }

    /** The connection, opened on first use: every step but Privacy needs it. */
    private LocalAdb adb() throws IOException {
        if (adb != null) {
            try {
                if (adb.shell("echo ok").output.trim().equals("ok")) return adb;
            } catch (IOException stale) {
                adb.close();
            }
            adb = null;
        }
        main.post(() -> status.setText(R.string.cleanup_status_connecting));
        try {
            adb = LocalAdb.connect(this);
        } catch (IOException e) {
            main.post(() -> status.setText(R.string.cleanup_status_idle));
            String ip = LocalAdb.wifiAddress(this);
            append("Connessione non riuscita:\n" + e.getMessage()
                    + "\n\nControlla che Debug ADB sia ON e di aver scelto Consenti sulla TV, poi riprova."
                    + "\nSe continua a non funzionare, usa un PC o un telefono sulla stessa rete:"
                    + "\n  adb connect " + (ip.isEmpty() ? "<IP della Fire TV>" : ip) + ":5555"
                    + "\n  tools/cleanyourfire.sh " + (ip.isEmpty() ? "<IP>" : ip)
                    + "\nComandi completi: github.com/riccamariofrancesco-cell/cleanyourfire, docs/COMANDI.md");
            throw new IOException("ADB non raggiungibile");
        }
        String model = sh("getprop ro.product.model").trim();
        String build = sh("getprop ro.build.version.name").trim();
        String host = adb.host;
        main.post(() -> status.setText(getString(R.string.cleanup_status_connected, host, model, build)));
        return adb;
    }

    private String sh(String command) throws IOException {
        return adb.shell(command).output;
    }

    private void connect() throws IOException {
        adb();
        append("Connesso. Fai il backup (2), scegli le categorie (3) e applica (4).");
    }

    private void backup() throws IOException {
        LocalAdb adb = adb();
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
        String base = FireCommands.SDCARD_DIR + "/cleanyourfire-backup-" + stamp;
        adb.shell("mkdir -p " + FireCommands.SDCARD_DIR);
        adb.shell("pm list packages > " + FireCommands.quote(base + "-tutte.txt"));
        adb.shell("pm list packages -d > " + FireCommands.quote(base + "-disattivate.txt"));
        adb.shell("getprop > " + FireCommands.quote(base + "-getprop.txt"));
        adb.shell("settings list global > " + FireCommands.quote(base + "-global.txt"));
        adb.shell("settings list secure > " + FireCommands.quote(base + "-secure.txt"));
        String check = adb.shell("ls -l " + FireCommands.quote(base + "-tutte.txt")).output.trim();
        prefs.edit().putBoolean("backup_done", true).apply();
        append("Backup salvato in " + FireCommands.SDCARD_DIR + "/ (cleanyourfire-backup-" + stamp + "-*.txt)\n" + check);
    }

    private void apply() throws IOException {
        LocalAdb adb = adb();
        if (!prefs.getBoolean("backup_done", false)) {
            append("Prima il backup:");
            backup();
        }
        Set<String> installed = FireCommands.packages(sh("pm list packages"));
        Set<String> disabled = FireCommands.packages(sh("pm list packages -d"));
        Set<String> record = readRecord();
        int done = 0, already = 0, missing = 0, refused = 0, failed = 0;
        for (int i = 0; i < categories.size(); i++) {
            if (!chosen[i]) continue;
            DebloatList.Category category = categories.get(i);
            append("— " + category.title);
            for (DebloatList.Entry entry : category.entries) {
                if (!installed.contains(entry.pkg)) {
                    missing++;
                    continue;
                }
                if (disabled.contains(entry.pkg)) {
                    already++;
                    continue;
                }
                String out = adb.shell(FireCommands.disable(entry.pkg)).output;
                switch (FireCommands.disableOutcome(out)) {
                    case DISABLED:
                        done++;
                        record.add(entry.pkg);
                        append("  off  " + entry.pkg);
                        break;
                    case PROTECTED:
                        refused++;
                        append("  protetto  " + entry.pkg);
                        break;
                    default:
                        failed++;
                        append("  errore  " + entry.pkg + ": " + out.trim());
                }
            }
            writeRecord(record);
        }
        append("Fatto: " + done + " disattivate, " + already + " già spente, " + missing + " non presenti, "
                + refused + " protette, " + failed + " errori. Registro di ripristino: "
                + FireCommands.SDCARD_DIR + "/" + FireCommands.RECORD_FILE
                + "\nSe dopo un riavvio qualche extra di Alexa torna attivo, premi di nuovo Applica.");
    }

    private void launcherOn() throws IOException {
        LocalAdb adb = adb();
        String self = getPackageName();
        report(adb.shell(FireCommands.grant(self, "android.permission.WRITE_SECURE_SETTINGS")), "permesso impostazioni");
        report(adb.shell(FireCommands.grant(self, "android.permission.ACCESS_FINE_LOCATION")), "nome rete Wi-Fi");
        String alarm = settingValue(sh(FireCommands.settingsGet("global", "alarm_manager_constants")));
        if (!prefs.contains(KEY_ALARM_ORIGINAL)) prefs.edit().putString(KEY_ALARM_ORIGINAL, alarm).commit();
        report(adb.shell(FireCommands.settingsPut("global", "alarm_manager_constants",
                FireCommands.withMinFuturity(alarm))), "allarmi immediati (min_futurity=0)");
        String services = settingValue(sh(FireCommands.settingsGet("secure", "enabled_accessibility_services")));
        report(adb.shell(FireCommands.settingsPut("secure", "enabled_accessibility_services",
                FireCommands.withService(services, serviceComponent()))), "servizio Home");
        report(adb.shell(FireCommands.settingsPut("secure", "accessibility_enabled", "1")), "accessibilità");
        append("Launcher pulito attivo: il tasto Home ora porta qui. La Home Amazon può comparire per un attimo.");
        main.post(() -> startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
    }

    private void launcherOff() throws IOException {
        LocalAdb adb = adb();
        String services = settingValue(sh(FireCommands.settingsGet("secure", "enabled_accessibility_services")));
        String rest = FireCommands.withoutService(services, serviceComponent());
        report(adb.shell(FireCommands.settingsPut("secure", "enabled_accessibility_services", rest)), "servizio Home");
        if (rest.isEmpty()) report(adb.shell(FireCommands.settingsPut("secure", "accessibility_enabled", "0")), "accessibilità");
        if (prefs.contains(KEY_ALARM_ORIGINAL)) {
            report(adb.shell(FireCommands.settingsPut("global", "alarm_manager_constants",
                    prefs.getString(KEY_ALARM_ORIGINAL, ""))), "costanti allarmi originali");
            prefs.edit().remove(KEY_ALARM_ORIGINAL).commit();
        }
        append("Launcher pulito disattivato: il tasto Home torna alla Home Amazon.");
    }

    private void extras() throws IOException {
        LocalAdb adb = adb();
        for (String key : new String[]{"window_animation_scale", "transition_animation_scale", "animator_duration_scale"}) {
            report(adb.shell(FireCommands.settingsPut("global", key, "0.5")), key + " = 0.5");
        }
        report(adb.shell("pm trim-caches 999G"), "cache svuotata");
        append(sh("df -h /data").trim());
    }

    private void restore() throws IOException {
        LocalAdb adb = adb();
        Set<String> record = readRecord();
        int done = 0;
        for (String pkg : record) {
            LocalAdb.Result result = adb.shell(FireCommands.enable(pkg));
            if (result.output.contains("new state: enabled")) done++;
            else append("  errore  " + pkg + ": " + result.output.trim());
        }
        writeRecord(new LinkedHashSet<>());
        append("Riattivate " + done + " app su " + record.size() + ".");
        if (redirectOn()) launcherOff();
    }

    private void adbOff() throws IOException {
        LocalAdb adb = adb();
        try {
            adb.shell(FireCommands.settingsPut("global", "adb_enabled", "0"));
        } catch (IOException expected) {
            // adbd stops while answering, so the connection may drop before the reply.
        }
        adb.close();
        this.adb = null;
        main.post(() -> status.setText(R.string.cleanup_status_idle));
        append("Debug ADB spento. Per riapplicare o ripristinare, riaccendilo dalle Opzioni sviluppatore.");
    }

    // ---- Restore record ---------------------------------------------------------------------------------------------

    /** This app's record joined with the copy on /sdcard (which survives a reinstall of the app). */
    private Set<String> readRecord() throws IOException {
        Set<String> record = new LinkedHashSet<>();
        File local = new File(getFilesDir(), RECORD_NAME);
        if (local.isFile()) {
            record.addAll(FireCommands.parseRecord(new String(Files.readAllBytes(local.toPath()), StandardCharsets.UTF_8)));
        }
        if (adb != null) {
            record.addAll(FireCommands.parseRecord(
                    sh("cat " + FireCommands.quote(FireCommands.SDCARD_DIR + "/" + FireCommands.RECORD_FILE))));
        }
        return record;
    }

    private void writeRecord(Set<String> record) throws IOException {
        try (FileOutputStream out = new FileOutputStream(new File(getFilesDir(), RECORD_NAME))) {
            out.write(FireCommands.record(record).getBytes(StandardCharsets.UTF_8));
        }
        if (adb != null) {
            for (String command : FireCommands.writeFile(FireCommands.SDCARD_DIR + "/" + FireCommands.RECORD_FILE, record)) {
                adb.shell(command);
            }
        }
    }

    // ---- Helpers ----------------------------------------------------------------------------------------------------

    /** `settings get` prints "null" for an unset key. */
    private static String settingValue(String output) {
        String value = output.trim();
        return "null".equals(value) ? "" : value;
    }

    private void report(LocalAdb.Result result, String what) {
        String out = result.output.trim();
        append((result.exitCode == 0 ? "  ok  " : "  errore  ") + what + (out.isEmpty() ? "" : ": " + out));
    }

    private void openSettings() {
        Toast.makeText(this, R.string.cleanup_privacy_help, Toast.LENGTH_LONG).show();
        append(getString(R.string.cleanup_privacy_help));
        try {
            startActivity(SystemSettings.intent(this, Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    private void append(String line) {
        main.post(() -> log.append(line + "\n"));
    }
}
