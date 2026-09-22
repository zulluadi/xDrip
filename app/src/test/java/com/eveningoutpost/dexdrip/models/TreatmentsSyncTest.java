package com.eveningoutpost.dexdrip.models;

import com.eveningoutpost.dexdrip.RobolectricTestWithConfig;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.Expose;

import org.junit.Test;

import java.time.Instant;

import static com.google.common.truth.Truth.assertThat;

public class TreatmentsSyncTest extends RobolectricTestWithConfig {

    @Test
    public void nightscoutIgnoresEmptyNotesWithoutDoses() throws Exception {
        Treatments.delete_all();
        try {
            final String createdAt = DateUtil.toISOString(Instant.now().toEpochMilli());
            final String response = "[{\"_id\":\"empty-note\",\"created_at\":\"" + createdAt
                    + "\",\"carbs\":0,\"insulin\":0,\"notes\":\"\"},"
                    + "{\"_id\":\"blank-note\",\"created_at\":\"" + createdAt
                    + "\",\"carbs\":0,\"insulin\":0,\"notes\":\"   \"}]";

            assertThat(NightscoutTreatments.processTreatmentResponse(response)).isFalse();
            assertThat(NightscoutTreatments.processTreatmentResponse(response)).isFalse();
            assertThat(Treatments.last()).isNull();
            assertThat(org.robolectric.shadows.ShadowLog.getLogsForTag("NightscoutTreatments")
                    .stream().anyMatch(log -> log.msg.startsWith("New Treatment from Nightscout:"))).isFalse();
        } finally {
            Treatments.delete_all();
        }
    }

    @Test
    public void nightscoutPreservesRealNotesAndDosesWithEmptyNotes() throws Exception {
        Treatments.delete_all();
        try {
            final long time = Instant.now().toEpochMilli();
            final String response = "[{\"_id\":\"real-note\",\"created_at\":\"" + DateUtil.toISOString(time)
                    + "\",\"carbs\":0,\"insulin\":0,\"notes\":\"Exercise\"},"
                    + "{\"_id\":\"real-dose\",\"created_at\":\"" + DateUtil.toISOString(time - 600000)
                    + "\",\"carbs\":15,\"insulin\":1,\"notes\":\"\"}]";

            assertThat(NightscoutTreatments.processTreatmentResponse(response)).isTrue();
            assertThat(Treatments.byuuid("real-note").notes).isEqualTo("Exercise");
            assertThat(Treatments.byuuid("real-note").carbs).isEqualTo(0);
            assertThat(Treatments.byuuid("real-note").insulin).isEqualTo(0);
            assertThat(Treatments.byuuid("real-dose").carbs).isEqualTo(15);
            assertThat(Treatments.byuuid("real-dose").insulin).isEqualTo(1);
            assertThat(NightscoutTreatments.processTreatmentResponse(response)).isFalse();
        } finally {
            Treatments.delete_all();
        }
    }

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

}
