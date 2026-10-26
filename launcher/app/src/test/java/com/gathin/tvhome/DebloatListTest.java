package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class DebloatListTest {

    /** Packages the Fire OS list must never disable: Home, settings, input, setup, voice, Prime Video, casting,
     *  and the ones Fire OS protects (pm refuses them, so listing them only adds errors). */
    private static final Set<String> KEEP = new HashSet<>(Arrays.asList(
            "com.amazon.tv.launcher", "com.amazon.firehomestarter", "com.amazon.tv.settings.v2",
            "com.amazon.tv.settings", "com.amazon.venezia", "com.amazon.tv.ime", "com.amazon.tv.oobe",
            "com.amazon.vizzini", "com.amazon.aria", "com.amazon.cardinal", "com.amazon.tcomm",
            "com.amazon.firebat", "com.amazon.tv.support", "com.amazon.cast.sink", "com.amazon.whisperplay",
            "com.amazon.dialservice", "com.amazon.prism.android.service", "com.amazon.logan",
            "com.amazon.device.software.ota", "com.amazon.tv.forcedotaupdater.v2",
            "com.amazon.minerva.client.api", "com.amazon.device.logmanager", "com.amazon.device.crashmanager",
            "com.amazon.diode", "com.fireos.usagestats.proxy",
            "it.cleanyourfire.home", "com.gathin.tvhome"));

    private static List<DebloatList.Category> shipped() throws IOException {
        File asset = new File("src/main/assets/" + DebloatList.ASSET);
        try (InputStreamReader in = new InputStreamReader(new FileInputStream(asset), StandardCharsets.UTF_8)) {
            return DebloatList.parse(in);
        }
    }

    @Test
    public void shippedListHasTheFourCategoriesAllOn() throws IOException {
        List<DebloatList.Category> categories = shipped();
        assertEquals(4, categories.size());
        assertEquals("telemetry", categories.get(0).id);
        assertEquals("ads", categories.get(1).id);
        assertEquals("apps", categories.get(2).id);
        assertEquals("alexa", categories.get(3).id);
        for (DebloatList.Category category : categories) {
            assertTrue(category.id, category.defaultOn);
            assertFalse(category.id, category.entries.isEmpty());
        }
    }

    @Test
    public void shippedListNeverTouchesKeptOrProtectedPackages() throws IOException {
        Set<String> seen = new HashSet<>();
        for (DebloatList.Category category : shipped()) {
            for (DebloatList.Entry entry : category.entries) {
                assertFalse("kept package listed: " + entry.pkg, KEEP.contains(entry.pkg));
                assertFalse("launcher or settings listed: " + entry.pkg,
                        entry.pkg.startsWith("com.amazon.tv.settings") || entry.pkg.endsWith(".launcher"));
                assertTrue("listed twice: " + entry.pkg, seen.add(entry.pkg));
            }
        }
    }

    /** docs/COMANDI.md copies the list into shell loops for phone ADB apps; it must not fall behind. */
    @Test
    public void manualCommandsCoverTheWholeList() throws IOException {
        String doc = new String(java.nio.file.Files.readAllBytes(new File("../../docs/COMANDI.md").toPath()),
                StandardCharsets.UTF_8);
        for (DebloatList.Category category : shipped()) {
            for (DebloatList.Entry entry : category.entries) {
                assertTrue("missing from docs/COMANDI.md: " + entry.pkg, doc.contains(" " + entry.pkg + " ")
                        || doc.contains(" " + entry.pkg + ";"));
            }
        }
    }

    @Test
    public void parsesHeadersPackagesAndNotes() throws IOException {
        List<DebloatList.Category> categories = DebloatList.parse(new StringReader(
                "# intro\n\n## [a] First (on)\ncom.example.one   # a note\n# com.example.skipped\n"
                        + "## [b] Second one (off)\ncom.example.two\n"));
        assertEquals(2, categories.size());
        assertEquals("First", categories.get(0).title);
        assertTrue(categories.get(0).defaultOn);
        assertEquals(1, categories.get(0).entries.size());
        assertEquals("com.example.one", categories.get(0).entries.get(0).pkg);
        assertEquals("a note", categories.get(0).entries.get(0).note);
        assertEquals("Second one", categories.get(1).title);
        assertFalse(categories.get(1).defaultOn);
        assertEquals("com.example.two", categories.get(1).entries.get(0).pkg);
    }

    @Test
    public void refusesALineThatIsNotAPackageName() {
        for (String bad : new String[]{"com.example.one; reboot", "com.example.$(id)", "reboot", "com.example one"}) {
            try {
                DebloatList.parse(new StringReader("## [a] A (on)\n" + bad + "\n"));
                fail("accepted: " + bad);
            } catch (IOException expected) {
            }
        }
    }

    @Test
    public void refusesAPackageBeforeTheFirstCategory() {
        try {
            DebloatList.parse(new StringReader("com.example.one\n"));
            fail();
        } catch (IOException expected) {
        }
    }
}
