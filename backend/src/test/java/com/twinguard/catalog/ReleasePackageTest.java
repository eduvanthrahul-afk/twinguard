package com.twinguard.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.twinguard.feature.Features;
import com.twinguard.gate.GateReport;
import com.twinguard.gate.ReleaseGate;
import com.twinguard.sim.Firmware;
import com.twinguard.testgen.ScenarioSearch;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReleasePackageTest {

    private static ReleasePackage.InvalidPackageException rejected(String json) {
        return assertThrows(ReleasePackage.InvalidPackageException.class, () -> ReleasePackage.parse(json).validated());
    }

    @Test
    void everyFirmwareSettingHasARange() {
        List<String> fields = Arrays.stream(Firmware.class.getFields()).filter(f -> !Modifier.isStatic(f.getModifiers())).map(f -> f.getName()).sorted().toList();
        List<String> bounded = BrakingSettings.ALL.stream().map(BrakingSettings.Bound::key).sorted().toList();
        assertEquals(fields, bounded);
        BrakingSettings.ALL.forEach(b -> {
            double v1 = BrakingSettings.approved(b);
            assertTrue(v1 >= b.min() && v1 <= b.max(), b.key() + " v1 value " + v1 + " is outside its own range");
        });
    }

    @Test
    void brakingPackageBecomesTheSameTargetAsTheBuiltInRelease() {
        ReleasePackage pkg = ReleasePackage.parse("""
                {"format": "twinguard-release/1", "id": "media-copy", "title": "Media copy", "feature": "braking",
                 "changes": {"infotainmentPriority": 0}}""").validated();
        Firmware builtIn = ReleaseCatalog.find("v2.2-media").orElseThrow().firmware();
        assertEquals(List.of(), pkg.firmware().changedFrom(builtIn));
        assertEquals(List.of("infotainmentPriority"), pkg.firmware().changedFrom(Firmware.approved()));
    }

    @Test
    void importedMediaUpdateIsBlockedLikeTheBuiltInOne() {
        ReleaseGate.Target target = ReleasePackage.parse("""
                {"id": "media-copy", "title": "Media copy", "feature": "braking", "changes": {"infotainmentPriority": 0}}""").validated().brakingTarget();
        GateReport report = ReleaseGate.run(target, 600, ScenarioSearch.Strategy.CHANGE_AWARE, 7, null);
        assertEquals(GateReport.Verdict.BLOCK, report.verdict());
    }

    @Test
    void booleansAndDeclaredAllowancesAreRead() {
        ReleasePackage pkg = ReleasePackage.parse("""
                {"id": "x", "title": "X", "feature": "braking", "changes": {"latchFullBrake": false, "ttcPreBrakeS": 2.6},
                 "manifest": {"intent": "Earlier pre-braking", "allowedEarlyS": 0.4, "allowedGapLossM": 5}}""").validated();
        assertFalse(pkg.firmware().latchFullBrake);
        assertEquals(0.4, pkg.changeManifest().allowedEarlyS());
        assertEquals("Earlier pre-braking", pkg.changeManifest().intent());
    }

    @Test
    void featurePackageKeepsItsDeclaredChanges() {
        ReleasePackage pkg = ReleasePackage.parse("""
                {"id": "bms-x", "title": "BMS X", "feature": "battery", "changes": {"coldCurrentLimit": 0.6},
                 "manifest": {"intent": "Less charge", "declaredChanges": ["LOWER_CHARGE"]}}""").validated();
        assertEquals(0.6, pkg.featureRelease().overrides().get("coldCurrentLimit"));
        assertTrue(pkg.featureRelease().declaredChanges().contains("LOWER_CHARGE"));
    }

    @Test
    void reportsEveryProblemAtOnce() {
        ReleasePackage.InvalidPackageException e = rejected("""
                {"id": "bad id!", "feature": "braking", "changes": {"stopMargin": 99, "noSuchSetting": 1, "debounceCycles": 2.5}}""");
        assertEquals(5, e.problems().size(), e.problems().toString());
    }

    @Test
    void rejectsWhatCantBeTested() {
        rejected("{\"id\": \"v2.2-media\", \"title\": \"Clash\", \"feature\": \"braking\", \"changes\": {\"stopMargin\": 1}}");
        rejected("{\"id\": \"same\", \"title\": \"Same as v1\", \"feature\": \"braking\", \"changes\": {\"stopMargin\": 1.2}}");
        rejected("{\"id\": \"x\", \"title\": \"X\", \"feature\": \"toaster\", \"changes\": {\"a\": 1}}");
        rejected("{\"id\": \"x\", \"title\": \"X\", \"feature\": \"braking\", \"changes\": {\"stopMargin\": 1}, \"extra\": 1}");
        rejected("{\"id\": \"x\", \"title\": \"X\", \"feature\": \"braking\", \"changes\": {\"latchFullBrake\": 0}}");
        rejected("{\"id\": \"x\", \"title\": \"X\", \"feature\": \"battery\", \"changes\": {\"coldCurrentLimit\": 0.6}, \"manifest\": {\"intent\": \"y\", \"declaredChanges\": [\"EARLY_BRAKING\"]}}");
        rejected("{\"id\": \"x\", \"title\": \"X\", \"feature\": \"braking\", \"changes\": {\"stopMargin\": 1}, \"manifest\": {\"allowedEarlyS\": 0.5}}");
        rejected("not json");
    }

    @Test
    void everyFeatureHasSettingsToChange() {
        Features.all().forEach(f -> assertFalse(ReleasePackage.settingsFor(f.id()).isEmpty(), f.id()));
    }
}
