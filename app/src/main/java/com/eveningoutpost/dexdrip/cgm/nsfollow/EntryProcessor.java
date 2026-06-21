package com.eveningoutpost.dexdrip.cgm.nsfollow;

import com.eveningoutpost.dexdrip.models.BgReading;
import com.eveningoutpost.dexdrip.models.BloodTest;
import com.eveningoutpost.dexdrip.models.Sensor;
import com.eveningoutpost.dexdrip.models.UserError;
import com.eveningoutpost.dexdrip.utilitymodels.Inevitable;
import com.eveningoutpost.dexdrip.utilitymodels.NightscoutUploader;
import com.eveningoutpost.dexdrip.utilitymodels.Unitized;
import com.eveningoutpost.dexdrip.cgm.nsfollow.messages.Entry;

import java.util.List;
import java.util.UUID;

import static com.eveningoutpost.dexdrip.models.BgReading.SPECIAL_FOLLOWER_PLACEHOLDER;

/**
 * jamorham
 *
 * Take a list of Nightscout entries and inject as BgReadings
 */

public class EntryProcessor {

    private static final String TAG = "NightscoutFollowEP";

    static synchronized void processEntries(final List<Entry> entries, final boolean live) {

        if (entries == null) return;

        final Sensor sensor = Sensor.createDefaultIfMissing();

        for (final Entry entry : entries) {
            if (entry != null) {
                UserError.Log.d(TAG, "ENTRY: " + entry.toS());
                UserError.Log.d(TAG, "Glucose value: " + Unitized.unitized_string_static(entry.sgv));

                final long recordTimestamp = entry.getTimeStamp();
                if (recordTimestamp > 0) {
                    if ("mbg".equals(entry.type)) {
                        processBloodTestEntry(entry, recordTimestamp, live);
                        continue;
                    }

                    final BgReading existing = BgReading.getForPreciseTimestamp(recordTimestamp, 10000);
                    if (existing == null) {
                        UserError.Log.d(TAG, "NEW NEW NEW New entry: " + entry.toS());

                        if (live) {
                            final BgReading bg = new BgReading();
                            bg.uuid = UUID.randomUUID().toString();
                            bg.timestamp = recordTimestamp;
                            bg.calculated_value = entry.sgv;
                            bg.raw_data = entry.unfiltered != 0 ? entry.unfiltered : SPECIAL_FOLLOWER_PLACEHOLDER;
                            bg.filtered_data = entry.filtered;
                            bg.noise = entry.noise + "";
                            // TODO need to handle slope??
                            bg.sensor = sensor;
                            bg.sensor_uuid = sensor.uuid;
                            bg.source_info = "Nightscout Follow";
                            bg.save();
                            Inevitable.task("entry-proc-post-pr",500, () -> bg.postProcess(false));
                        }
                    } else {
                       // break; // stop if we have this reading TODO are entries always in order?
                    }
                } else {
                    UserError.Log.e(TAG, "Could not parse a timestamp from: " + entry.toS());
                }

            } else {
                UserError.Log.d(TAG, "Entry is null");
            }
        }

    }

    private static void processBloodTestEntry(final Entry entry, final long recordTimestamp, final boolean live) {
        if (!live || entry.mbg <= 0) return;

        final String uuid = entry.uuid != null ? entry.uuid : entry._id;
        final BloodTest existingByUuid = BloodTest.byUUID(uuid);
        if (existingByUuid != null) {
            markAsNightscoutEntryBloodTest(existingByUuid, entry);
            return;
        }
        final BloodTest existingByTimestamp = BloodTest.getForPreciseTimestamp(recordTimestamp, 10000);
        if (existingByTimestamp != null) {
            if (existingByTimestamp.source != null && existingByTimestamp.source.contains(NightscoutUploader.VIA_NIGHTSCOUT_TAG)) {
                markAsNightscoutEntryBloodTest(existingByTimestamp, entry);
            }
            return;
        }

        final String source = nightscoutEntryBloodTestSource(entry);
        final BloodTest bloodTest = BloodTest.createLocalOnly(recordTimestamp, entry.mbg, source, uuid);
        if (bloodTest != null) {
            UserError.Log.ueh(TAG, "Received new Bloodtest entry from Nightscout: " + Unitized.unitized_string_static(entry.mbg));
        }
    }

    private static String nightscoutEntryBloodTestSource(final Entry entry) {
        return (entry.device != null ? entry.device : "Nightscout") + " " + NightscoutUploader.VIA_NIGHTSCOUT_ENTRIES_TAG;
    }

    private static void markAsNightscoutEntryBloodTest(final BloodTest bloodTest, final Entry entry) {
        final String source = nightscoutEntryBloodTestSource(entry);
        if (bloodTest.source == null || !bloodTest.source.equals(source)) {
            bloodTest.source = source;
            bloodTest.saveit();
        }
    }
}
