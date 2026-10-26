package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class FireCommandsTest {

    private static final String SERVICE = "it.cleanyourfire.home/com.gathin.tvhome.HomeKeyService";

    @Test
    public void disableAndEnableCommands() {
        assertEquals("pm disable-user --user 0 com.amazon.tv.acr", FireCommands.disable("com.amazon.tv.acr"));
        assertEquals("pm enable com.amazon.tv.acr", FireCommands.enable("com.amazon.tv.acr"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void refusesAnythingButAPackageName() {
        FireCommands.disable("com.amazon.tv.acr; reboot");
    }

    @Test
    public void readsTheOutcomeOfPmDisableUser() {
        assertEquals(FireCommands.Outcome.DISABLED,
                FireCommands.disableOutcome("Package com.amazon.tv.acr new state: disabled-user\n"));
        assertEquals(FireCommands.Outcome.PROTECTED, FireCommands.disableOutcome(
                "Exception occurred while executing:\njava.lang.IllegalArgumentException: Cannot disable a protected package: com.amazon.diode\n"));
        assertEquals(FireCommands.Outcome.FAILED, FireCommands.disableOutcome("Error: Unknown package: x\n"));
    }

    @Test
    public void parsesPmListPackages() {
        Set<String> found = FireCommands.packages("package:com.a.b\r\npackage:com.c.d\n\nnoise\n");
        assertEquals(new LinkedHashSet<>(Arrays.asList("com.a.b", "com.c.d")), found);
    }

    @Test
    public void addsTheHomeServiceOnceAndKeepsOthers() {
        assertEquals(SERVICE, FireCommands.withService("null", SERVICE));
        assertEquals(SERVICE, FireCommands.withService("", SERVICE));
        assertEquals("com.other/.Svc:" + SERVICE, FireCommands.withService("com.other/.Svc", SERVICE));
        assertEquals("com.other/.Svc:" + SERVICE, FireCommands.withService("com.other/.Svc:" + SERVICE, SERVICE));
    }

    @Test
    public void removesOnlyTheHomeService() {
        assertEquals("com.other/.Svc", FireCommands.withoutService(SERVICE + ":com.other/.Svc", SERVICE));
        assertEquals("", FireCommands.withoutService(SERVICE, SERVICE));
        assertEquals("", FireCommands.withoutService("null", SERVICE));
    }

    @Test
    public void setsMinFuturityKeepingOtherAlarmConstants() {
        assertEquals("min_futurity=0", FireCommands.withMinFuturity(""));
        assertEquals("min_futurity=0", FireCommands.withMinFuturity("null"));
        assertEquals("allow_while_idle_short_time=5000,min_futurity=0",
                FireCommands.withMinFuturity("allow_while_idle_short_time=5000,min_futurity=5000"));
    }

    @Test
    public void quotesSettingValues() {
        assertEquals("settings put secure enabled_accessibility_services 'a/.B:c/.D'",
                FireCommands.settingsPut("secure", "enabled_accessibility_services", "a/.B:c/.D"));
        assertEquals("settings delete global alarm_manager_constants",
                FireCommands.settingsPut("global", "alarm_manager_constants", ""));
        assertEquals("'it'\\''s'", FireCommands.quote("it's"));
    }

    @Test
    public void restoreRecordRoundTrips() {
        Set<String> packages = new LinkedHashSet<>(Arrays.asList("com.amazon.tv.acr", "com.amazon.hedwig"));
        String text = FireCommands.record(packages);
        assertEquals("com.amazon.tv.acr\ncom.amazon.hedwig\n", text);
        assertEquals(packages, FireCommands.parseRecord(text + "garbage line\n\n"));
    }

    @Test
    public void writesFilesInSmallQuotedChunks() {
        Set<String> lines = new LinkedHashSet<>();
        for (int i = 0; i < 45; i++) lines.add("com.example.p" + i);
        List<String> commands = FireCommands.writeFile("/sdcard/Download/x.txt", lines);
        assertEquals(": > '/sdcard/Download/x.txt'", commands.get(0));
        assertEquals(4, commands.size());
        assertTrue(commands.get(1).startsWith("printf '%s\\n' 'com.example.p0' 'com.example.p1'"));
        assertTrue(commands.get(3).endsWith("'com.example.p44' >> '/sdcard/Download/x.txt'"));
    }
}
