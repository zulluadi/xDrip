package com.eveningoutpost.dexdrip.cgm.nsfollow;

import com.eveningoutpost.dexdrip.RobolectricTestWithConfig;
import com.eveningoutpost.dexdrip.cgm.nsfollow.messages.Entry;
import com.eveningoutpost.dexdrip.models.BloodTest;
import com.eveningoutpost.dexdrip.utilitymodels.NightscoutUploader;

import org.junit.Test;

import java.time.Instant;
import java.util.Collections;

import static com.google.common.truth.Truth.assertThat;

public class EntryProcessorTest extends RobolectricTestWithConfig {

    @Test
    public void processEntries_createsBloodTestFromMbgEntry() {
        BloodTest.cleanup(-100000);

        try {
            final long timestamp = Instant.now().toEpochMilli();
            final Entry entry = new Entry();
            entry._id = "5f1234567890abcdef123456";
            entry.type = "mbg";
            entry.date = timestamp;
            entry.mbg = 123;
            entry.device = "Maia xDrip";

            EntryProcessor.processEntries(Collections.singletonList(entry), true);

            final BloodTest bloodTest = BloodTest.byUUID(entry._id);
            assertThat(bloodTest).isNotNull();
            assertThat(bloodTest.mgdl).isEqualTo(123);
            assertThat(bloodTest.timestamp).isEqualTo(timestamp);
            assertThat(bloodTest.source).contains(NightscoutUploader.VIA_NIGHTSCOUT_ENTRIES_TAG);
        } finally {
            BloodTest.cleanup(-100000);
        }
    }

    @Test
    public void processEntries_marksExistingBloodTestAsEntrySourced() {
        BloodTest.cleanup(-100000);

        try {
            final long timestamp = Instant.now().toEpochMilli();
            final Entry entry = new Entry();
            entry._id = "5f1234567890abcdef123456";
            entry.type = "mbg";
            entry.date = timestamp;
            entry.mbg = 123;
            entry.device = "Maia xDrip";

            final BloodTest existing = BloodTest.createLocalOnly(timestamp, 123, "Maia xDrip " + NightscoutUploader.VIA_NIGHTSCOUT_TAG, entry._id);

            EntryProcessor.processEntries(Collections.singletonList(entry), true);

            assertThat(BloodTest.byUUID(existing.uuid).source).contains(NightscoutUploader.VIA_NIGHTSCOUT_ENTRIES_TAG);
        } finally {
            BloodTest.cleanup(-100000);
        }
    }
}
