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
    public void nightscoutDownloadRemovesMissingNightscoutTreatment() throws Exception {
        Treatments.delete_all();
        BloodTest.cleanup(-100000);

        try {
            final long time = Instant.now().toEpochMilli();
            final Treatments nightscoutTreatment = Treatments.create(10, 1, time, "ns-deleted");
            nightscoutTreatment.enteredBy = "remote " + NightscoutUploader.VIA_NIGHTSCOUT_TAG;
            nightscoutTreatment.save();

            final Treatments localTreatment = Treatments.create(12, 2, time + 1000, "local-kept");

            final boolean changed = NightscoutTreatments.processTreatmentResponse("[]");

            assertThat(changed).isTrue();
            assertThat(Treatments.byuuid("ns-deleted")).isNull();
            assertThat(Treatments.byuuid(localTreatment.uuid)).isNotNull();
        } finally {
            Treatments.delete_all();
            BloodTest.cleanup(-100000);
        }
    }

    @Test
    public void nightscoutDownloadKeepsReturnedNightscoutTreatment() throws Exception {
        Treatments.delete_all();
        BloodTest.cleanup(-100000);

        try {
            final long time = Instant.now().toEpochMilli();
            final Treatments nightscoutTreatment = Treatments.create(10, 1, time, "ns-kept");
            nightscoutTreatment.enteredBy = "remote " + NightscoutUploader.VIA_NIGHTSCOUT_TAG;
            nightscoutTreatment.save();

            final String response = "[{\"_id\":\"ns-kept\",\"uuid\":\"ns-kept\",\"eventType\":\"Meal Bolus\",\"enteredBy\":\"remote\",\"created_at\":\""
                    + DateUtil.toISOString(time)
                    + "\",\"carbs\":10,\"insulin\":1}]";

            final boolean changed = NightscoutTreatments.processTreatmentResponse(response);

            assertThat(changed).isFalse();
            assertThat(Treatments.byuuid("ns-kept")).isNotNull();
        } finally {
            Treatments.delete_all();
            BloodTest.cleanup(-100000);
        }
    }

    @Test
    public void nightscoutDownloadRemovesMissingNewestNightscoutTreatment() throws Exception {
        Treatments.delete_all();
        BloodTest.cleanup(-100000);

        try {
            final long oldTime = Instant.now().minusSeconds(600).toEpochMilli();
            final long newTime = Instant.now().toEpochMilli();
            final Treatments oldNightscoutTreatment = Treatments.create(10, 1, oldTime, "ns-old-kept");
            oldNightscoutTreatment.enteredBy = "remote " + NightscoutUploader.VIA_NIGHTSCOUT_TAG;
            oldNightscoutTreatment.save();
            final Treatments newNightscoutTreatment = Treatments.create(0, 2, newTime, "ns-new-deleted");
            newNightscoutTreatment.enteredBy = "remote " + NightscoutUploader.VIA_NIGHTSCOUT_TAG;
            newNightscoutTreatment.save();

            final String response = "[{\"_id\":\"ns-old-kept\",\"uuid\":\"ns-old-kept\",\"eventType\":\"Meal Bolus\",\"enteredBy\":\"remote\",\"created_at\":\""
                    + DateUtil.toISOString(oldTime)
                    + "\",\"carbs\":10,\"insulin\":1}]";

            final boolean changed = NightscoutTreatments.processTreatmentResponse(response);

            assertThat(changed).isTrue();
            assertThat(Treatments.byuuid("ns-old-kept")).isNotNull();
            assertThat(Treatments.byuuid("ns-new-deleted")).isNull();
        } finally {
            Treatments.delete_all();
            BloodTest.cleanup(-100000);
        }
    }

    @Test
    public void nightscoutDownloadRemovesMissingNewestBloodTest() throws Exception {
        Treatments.delete_all();
        BloodTest.cleanup(-100000);

        try {
            final long oldTime = Instant.now().minusSeconds(600).toEpochMilli();
            final long newTime = Instant.now().toEpochMilli();
            final BloodTest oldBloodTest = BloodTest.createLocalOnly(oldTime, 101, "remote " + NightscoutUploader.VIA_NIGHTSCOUT_TREATMENTS_TAG, "bt-old-kept");
            final BloodTest newBloodTest = BloodTest.createLocalOnly(newTime, 111, "remote " + NightscoutUploader.VIA_NIGHTSCOUT_TREATMENTS_TAG, "bt-new-deleted");

            final String response = "[{\"_id\":\"bt-old-kept\",\"uuid\":\"bt-old-kept\",\"eventType\":\"BG Check\",\"enteredBy\":\"remote\",\"created_at\":\""
                    + DateUtil.toISOString(oldTime)
                    + "\",\"glucoseType\":\"Finger\",\"glucose\":101,\"units\":\"mg/dl\"}]";

            final boolean changed = NightscoutTreatments.processTreatmentResponse(response);

            assertThat(changed).isTrue();
            assertThat(BloodTest.byUUID(oldBloodTest.uuid)).isNotNull();
            assertThat(BloodTest.byUUID(newBloodTest.uuid)).isNull();
        } finally {
            Treatments.delete_all();
            BloodTest.cleanup(-100000);
        }
    }

    @Test
    public void nightscoutDownloadUpdatesReturnedBloodTest() throws Exception {
        Treatments.delete_all();
        BloodTest.cleanup(-100000);

        try {
            final long time = Instant.now().toEpochMilli();
            final BloodTest bloodTest = BloodTest.createLocalOnly(time, 101, "remote " + NightscoutUploader.VIA_NIGHTSCOUT_TREATMENTS_TAG, "bt-updated");

            final String response = "[{\"_id\":\"bt-updated\",\"uuid\":\"bt-updated\",\"eventType\":\"BG Check\",\"enteredBy\":\"remote\",\"created_at\":\""
                    + DateUtil.toISOString(time)
                    + "\",\"glucoseType\":\"Finger\",\"glucose\":111,\"units\":\"mg/dl\"}]";

            final boolean changed = NightscoutTreatments.processTreatmentResponse(response);

            assertThat(changed).isTrue();
            assertThat(BloodTest.byUUID(bloodTest.uuid).mgdl).isEqualTo(111);
        } finally {
            Treatments.delete_all();
            BloodTest.cleanup(-100000);
        }
    }

    @Test
    public void nightscoutTreatmentDownloadWithoutBloodTestsKeepsEntryBloodTest() throws Exception {
        Treatments.delete_all();
        BloodTest.cleanup(-100000);

        try {
            final long bloodTestTime = Instant.now().toEpochMilli();
            final long treatmentTime = Instant.now().minusSeconds(600).toEpochMilli();
            final BloodTest bloodTest = BloodTest.createLocalOnly(bloodTestTime, 101, "remote " + NightscoutUploader.VIA_NIGHTSCOUT_ENTRIES_TAG, "entry-bt-kept");

            final String response = "[{\"_id\":\"ns-treatment\",\"uuid\":\"ns-treatment\",\"eventType\":\"Meal Bolus\",\"enteredBy\":\"remote\",\"created_at\":\""
                    + DateUtil.toISOString(treatmentTime)
                    + "\",\"carbs\":10,\"insulin\":1}]";

            NightscoutTreatments.processTreatmentResponse(response);

            assertThat(BloodTest.byUUID(bloodTest.uuid)).isNotNull();
        } finally {
            Treatments.delete_all();
            BloodTest.cleanup(-100000);
        }
    }

    @Test
    public void nightscoutTreatmentDownloadWithBloodTestsKeepsEntryBloodTest() throws Exception {
        Treatments.delete_all();
        BloodTest.cleanup(-100000);

        try {
            final long entryBloodTestTime = Instant.now().toEpochMilli();
            final long treatmentBloodTestTime = Instant.now().minusSeconds(600).toEpochMilli();
            final BloodTest entryBloodTest = BloodTest.createLocalOnly(entryBloodTestTime, 85, "Maia xDrip " + NightscoutUploader.VIA_NIGHTSCOUT_ENTRIES_TAG, "entry-bt-kept");
            final BloodTest treatmentBloodTest = BloodTest.createLocalOnly(treatmentBloodTestTime, 101, "remote " + NightscoutUploader.VIA_NIGHTSCOUT_TREATMENTS_TAG, "treatment-bt-kept");

            final String response = "[{\"_id\":\"treatment-bt-kept\",\"uuid\":\"treatment-bt-kept\",\"eventType\":\"BG Check\",\"enteredBy\":\"remote\",\"created_at\":\""
                    + DateUtil.toISOString(treatmentBloodTestTime)
                    + "\",\"glucoseType\":\"Finger\",\"glucose\":101,\"units\":\"mg/dl\"}]";

            NightscoutTreatments.processTreatmentResponse(response);

            assertThat(BloodTest.byUUID(entryBloodTest.uuid)).isNotNull();
            assertThat(BloodTest.byUUID(treatmentBloodTest.uuid)).isNotNull();
        } finally {
            Treatments.delete_all();
            BloodTest.cleanup(-100000);
        }
    }

}
