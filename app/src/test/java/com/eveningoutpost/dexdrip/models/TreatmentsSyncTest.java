package com.eveningoutpost.dexdrip.models;

import com.eveningoutpost.dexdrip.RobolectricTestWithConfig;
import com.eveningoutpost.dexdrip.utilitymodels.NightscoutTreatments;
import com.eveningoutpost.dexdrip.utilitymodels.NightscoutUploader;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.Expose;

import org.junit.Test;

import java.time.Instant;

import static com.google.common.truth.Truth.assertThat;

public class TreatmentsSyncTest extends RobolectricTestWithConfig {

    public class TreatmentsCompat {
        @Expose
        public long timestamp;
        @Expose
        public String eventType;
        @Expose
        public String enteredBy;
        @Expose
        public String notes;
        @Expose
        public String uuid;
        @Expose
        public double carbs;
        @Expose
        public double insulin;
        @Expose
        public String created_at;
    }

    // if this test fails then compatibility with previous xDrip versions is likely broken
    @Test
    public void syncCompatibilityTest() {
        // :: Create
        long time = Instant.now().getEpochSecond();
        Treatments.create(55, 2, time);

        // :: Read
        Treatments lastTreatment = Treatments.last();
        lastTreatment.notes = "Hello World";

        // :: Verify
        assertThat(lastTreatment.carbs).isEqualTo(55.0);
        assertThat(lastTreatment.insulin).isEqualTo(2.0);
        assertThat(lastTreatment.timestamp).isEqualTo(time);
        assertThat(lastTreatment.enteredBy).startsWith(Treatments.XDRIP_TAG);

        final String json = lastTreatment.toJSON();


        //System.out.println(json);

        assertThat(json).isNotEmpty();
        assertThat(json.length()).isLessThan(256);
        final TreatmentsCompat compat = new GsonBuilder().excludeFieldsWithoutExposeAnnotation().create().fromJson(json, TreatmentsCompat.class);
        assertThat(compat.timestamp).isEqualTo(lastTreatment.timestamp);
        assertThat(compat.enteredBy).isEqualTo(lastTreatment.enteredBy);
        assertThat(compat.notes).isEqualTo(lastTreatment.notes);
        assertThat(compat.uuid).isEqualTo(lastTreatment.uuid);
        assertThat(compat.carbs).isEqualTo(lastTreatment.carbs);
        assertThat(compat.insulin).isEqualTo(lastTreatment.insulin);


    }

    @Test
    public void editingNightscoutCarbNoteMarksTreatmentForUpload() {
        Treatments.delete_all();
        try {
            final String nightscoutId = "0123456789abcdef01234567";
            final Treatments imported = Treatments.create(25, 0, Instant.now().toEpochMilli(), nightscoutId);
            imported.enteredBy = "AndroidAPS " + NightscoutUploader.VIA_NIGHTSCOUT_TAG;
            imported.save();

            final Treatments edited = Treatments.update_note_by_uuid(nightscoutId, "Lunch");

            assertThat(edited.uuid).isEqualTo(nightscoutId);
            assertThat(edited.carbs).isEqualTo(25.0);
            assertThat(edited.notes).isEqualTo("Lunch");
            assertThat(edited.enteredBy).isEqualTo(Treatments.XDRIP_TAG);
            assertThat(Treatments.byuuid(nightscoutId).enteredBy).isEqualTo(Treatments.XDRIP_TAG);
        } finally {
            Treatments.delete_all();
        }
    }

    @Test
    public void pushedTreatmentReplacesNoteWithShorterEdit() {
        Treatments.delete_all();
        try {
            final long time = Instant.now().toEpochMilli();
            final Treatments treatment = Treatments.create(25, 0, time, "shared-treatment");
            treatment.notes = "Lunch with dessert";
            treatment.enteredBy = "AndroidAPS " + NightscoutUploader.VIA_NIGHTSCOUT_TAG;
            treatment.save();

            final String update = "{\"uuid\":\"shared-treatment\",\"timestamp\":" + time
                    + ",\"carbs\":25,\"enteredBy\":\"xdrip\",\"notes\":\"Lunch\"}";
            Treatments.pushTreatmentFromJson(update);

            assertThat(Treatments.byuuid("shared-treatment").notes).isEqualTo("Lunch");
        } finally {
            Treatments.delete_all();
        }
    }

    @Test
    public void pushedTreatmentKeepsLongerNoteForOtherSources() {
        Treatments.delete_all();
        try {
            final long time = Instant.now().toEpochMilli();
            final Treatments treatment = Treatments.create(25, 0, time, "shared-treatment");
            treatment.notes = "Lunch with dessert";
            treatment.save();

            final String update = "{\"uuid\":\"shared-treatment\",\"timestamp\":" + time
                    + ",\"carbs\":25,\"enteredBy\":\"AndroidAPS\",\"notes\":\"Lunch\"}";
            Treatments.pushTreatmentFromJson(update);

            assertThat(Treatments.byuuid("shared-treatment").notes).isEqualTo("Lunch with dessert");
        } finally {
            Treatments.delete_all();
        }
    }

    @Test
    public void nightscoutDownloadReplacesExistingCarbNote() throws Exception {
        Treatments.delete_all();
        BloodTest.cleanup(-100000);
        try {
            final long time = Instant.now().toEpochMilli();
            final String nightscoutId = "0123456789abcdef01234567";
            final Treatments treatment = Treatments.create(25, 0, time, nightscoutId);
            treatment.notes = "Lunch with dessert";
            treatment.enteredBy = "AndroidAPS " + NightscoutUploader.VIA_NIGHTSCOUT_TAG;
            treatment.save();

            final String response = "[{\"_id\":\"" + nightscoutId + "\",\"uuid\":\"" + nightscoutId
                    + "\",\"eventType\":\"Meal Bolus\",\"enteredBy\":\"xdrip\",\"created_at\":\""
                    + DateUtil.toISOString(time) + "\",\"carbs\":25,\"insulin\":0,\"notes\":\"Lunch\"}]";
            NightscoutTreatments.processTreatmentResponse(response);

            assertThat(Treatments.byuuid(nightscoutId).notes).isEqualTo("Lunch");

            final String aapsResponse = response.replace("\"enteredBy\":\"xdrip\"", "\"enteredBy\":\"AndroidAPS\"")
                    .replace("\"notes\":\"Lunch\"", "\"notes\":\"Coffee\"");
            NightscoutTreatments.processTreatmentResponse(aapsResponse);

            assertThat(Treatments.byuuid(nightscoutId).notes).isEqualTo("Lunch \u2192 Coffee");
        } finally {
            Treatments.delete_all();
            BloodTest.cleanup(-100000);
        }
    }
}
