package com.eveningoutpost.dexdrip.utilitymodels;

import com.eveningoutpost.dexdrip.Home;
import com.eveningoutpost.dexdrip.models.BloodTest;
import com.eveningoutpost.dexdrip.models.DateUtil;
import com.eveningoutpost.dexdrip.models.InsulinInjection;
import com.eveningoutpost.dexdrip.models.JoH;
import com.eveningoutpost.dexdrip.models.Treatments;
import com.eveningoutpost.dexdrip.models.UserError;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.eveningoutpost.dexdrip.models.Treatments.pushTreatmentSyncToWatch;

// jamorham

public class NightscoutTreatments {

    private static final boolean d = false;
    private static final String TAG = "NightscoutTreatments";

    private static final HashSet<String> bad_uuids = new HashSet<>();
    private static final HashSet<String> bad_bloodtest_uuids = new HashSet<>();

    public static boolean processTreatmentResponse(final String response) throws Exception {
        boolean new_data = false;

        final JSONArray jsonArray = new JSONArray(response);
        final Set<String> remoteTreatmentIds = new HashSet<>();
        final Set<String> remoteBloodTestIds = new HashSet<>();
        long minCollectionTimestamp = Long.MAX_VALUE;
        long minTreatmentTimestamp = Long.MAX_VALUE;
        long minBloodTestTimestamp = Long.MAX_VALUE;
        for (int i = 0; i < jsonArray.length(); i++) {
            final JSONObject tr = (JSONObject) jsonArray.get(i);

            final String etype = tr.has("eventType") ? tr.getString("eventType") : "<null>";
            // TODO if we are using upsert then we should favour _id over uuid!?
            final String uuid = (tr.has("uuid") && (tr.getString("uuid") != null)) ? tr.getString("uuid") : UUID.nameUUIDFromBytes(tr.getString("_id").getBytes("UTF-8")).toString();
            final String nightscout_id = (tr.getString("_id") == null) ? uuid : tr.getString("_id");
            if (bad_uuids.contains(nightscout_id)) {
                UserError.Log.d(TAG, "Skipping previously baulked uuid: " + nightscout_id);
                continue;
            }
            if (d)
                UserError.Log.d(TAG, "event: " + etype + "_id: " + nightscout_id + " uuid:" + uuid);

            final long recordTimestamp = getCreatedAtTimestamp(tr);
            if (recordTimestamp > 0) {
                minCollectionTimestamp = Math.min(minCollectionTimestamp, recordTimestamp);
            }

            boolean from_xdrip = false;
            try {
                if (tr.getString("enteredBy").startsWith(Treatments.XDRIP_TAG)) {
                    from_xdrip = true;
                    if (d) UserError.Log.d(TAG, "This record came from xDrip");
                }
            } catch (JSONException e) {
                //
            }

            boolean skip_from_xdrip = Pref.getBooleanDefaultFalse("cloud_storage_api_skip_download_from_xdrip");

            // extract blood test data if present
            try {
                if (!from_xdrip) {
                    if (tr.getString("glucoseType").equals("Finger")) {
                        if (bad_bloodtest_uuids.contains(nightscout_id)) {
                            UserError.Log.d(TAG, "Skipping baulked bloodtest nightscout id: " + nightscout_id);
                            continue;
                        }
                        remoteBloodTestIds.add(uuid);
                        remoteBloodTestIds.add(nightscout_id);
                        minBloodTestTimestamp = Math.min(minBloodTestTimestamp, recordTimestamp);
                        BloodTest existing = BloodTest.byUUID(nightscout_id);
                        if (existing == null) {
                            existing = BloodTest.byUUID(uuid);
                        }
                        final long timestamp = recordTimestamp;
                        double mgdl = JoH.tolerantParseDouble(tr.getString("glucose"), 0d);
                        if (tr.getString("units").equals("mmol"))
                            mgdl = mgdl * Constants.MMOLL_TO_MGDL;
                        if (existing == null) {
                            final BloodTest bt = BloodTest.createLocalOnly(timestamp, mgdl, tr.getString("enteredBy") + " " + NightscoutUploader.VIA_NIGHTSCOUT_TREATMENTS_TAG, nightscout_id);
                            if (bt != null) {
                                new_data = true;
                                UserError.Log.ueh(TAG, "Received new Bloodtest data from Nightscout: " + BgGraphBuilder.unitized_string_with_units_static(mgdl) + " @ " + JoH.dateTimeText(timestamp));
                            } else {
                                UserError.Log.d(TAG, "Error creating bloodtest record: " + mgdl + " mgdl " + tr.toString());
                                bad_bloodtest_uuids.add(nightscout_id);
                            }
                        } else {
                            if (d)
                                UserError.Log.d(TAG, "Already a bloodtest with uuid: " + uuid);
                            final String source = tr.getString("enteredBy") + " " + NightscoutUploader.VIA_NIGHTSCOUT_TREATMENTS_TAG;
                            if ((existing.mgdl != mgdl) || (existing.timestamp != timestamp) || ((existing.state & BloodTest.STATE_VALID) == 0) || (existing.source == null) || (!existing.source.equals(source))) {
                                UserError.Log.ueh(TAG, "Bloodtest changes from Nightscout: " + BgGraphBuilder.unitized_string_with_units_static(mgdl) + " @ " + JoH.dateTimeText(timestamp) + " vs " + BgGraphBuilder.unitized_string_with_units_static(existing.mgdl) + " @ " + JoH.dateTimeText(existing.timestamp));
                                existing.mgdl = mgdl;
                                existing.timestamp = timestamp;
                                existing.source = source;
                                existing.state |= BloodTest.STATE_VALID;
                                existing.saveit();
                                new_data = true;
                            }
                        }
                    } else {
                        if (JoH.quietratelimit("blood-test-type-finger", 2)) {
                            UserError.Log.e(TAG, "Cannot use bloodtest which is not type Finger: " + tr.getString("glucoseType"));
                        }
                    }
                }
            } catch (JSONException e) {
                // Log.d(TAG, "json processing: " + e);
            }

            // extract treatment data if present
            double carbs = 0;
            double insulin = 0;
            String injections = null;
            String notes = null;
            try {
                carbs = tr.getDouble("carbs");
            } catch (JSONException e) {
                //  Log.d(TAG, "json processing: " + e);
            }
            try {
                insulin = tr.getDouble("insulin");
            } catch (JSONException e) {
                // Log.d(TAG, "json processing: " + e);
            }
            try {
                injections = tr.getString("insulinInjections");
            } catch (JSONException e) {
                // Log.d(TAG, "json processing: " + e);
            }
            try {
                notes = tr.getString("notes");
            } catch (JSONException e) {
                // Log.d(TAG, "json processing: " + e);
            }

            if ((notes != null) && ((notes.startsWith("AndroidAPS started") || notes.equals("null") || (notes.equals("Bolus Std")))))
                notes = null;

            if ((carbs > 0) || (insulin > 0) || (notes != null)) {
                remoteTreatmentIds.add(uuid);
                remoteTreatmentIds.add(nightscout_id);
                final long timestamp = recordTimestamp;
                if (timestamp > 0) {
                    minTreatmentTimestamp = Math.min(minTreatmentTimestamp, timestamp);
                    if (d)
                        UserError.Log.d(TAG, "Treatment: Carbs: " + carbs + " Insulin: " + insulin + " timestamp: " + timestamp);
                    Treatments existing = Treatments.byuuid(nightscout_id);
                    if (existing == null)
                        existing = Treatments.byuuid(uuid);
                    if ((existing == null) && !(from_xdrip && skip_from_xdrip)) {
                        // check for close timestamp duplicates perhaps
                        existing = Treatments.byTimestamp(timestamp, 60000);
                        if (!((existing != null) && (JoH.roundDouble(existing.insulin, 2) == JoH.roundDouble(insulin, 2))
                                && (JoH.roundDouble(existing.carbs, 2) == JoH.roundDouble(carbs, 2))
                                && ((existing.notes == null && notes == null) || ((existing.notes != null) && existing.notes.equals(notes != null ? notes : ""))))) {

                            UserError.Log.ueh(TAG, "New Treatment from Nightscout: Carbs: " + carbs + " Insulin: " + insulin + " timestamp: " + JoH.dateTimeText(timestamp) + ((notes != null) ? " Note: " + notes : ""));
                            final Treatments t;
                            if ((carbs > 0) || (insulin > 0)) {
                                t = Treatments.create(carbs, insulin, new ArrayList<InsulinInjection>(), timestamp, nightscout_id);
                               if (t != null) {
                                   if (insulin > 0) t.setInsulinJSON(injections);
                                   if (notes != null) t.notes = notes;
                               }
                            } else {
                                t = Treatments.create_note(notes, timestamp, -1, nightscout_id);
                                if (t == null) {
                                    UserError.Log.d(TAG, "Create note baulked and returned null, so skipping");
                                    bad_uuids.add(nightscout_id);
                                    continue;
                                }
                            }

                            //t.uuid = nightscout_id; // replace with nightscout uuid
                            try {
                                t.enteredBy = tr.getString("enteredBy") + " " + NightscoutUploader.VIA_NIGHTSCOUT_TAG;
                            } catch (JSONException e) {
                                t.enteredBy = NightscoutUploader.VIA_NIGHTSCOUT_TAG;
                            }

                            t.save();
                            // sync again!
                            // pushTreatmentSync(t, false);
                            if (Home.get_show_wear_treatments())
                                pushTreatmentSyncToWatch(t, true);
                            new_data = true;
                        } else {
                            UserError.Log.d(TAG, "Skipping treatment as it appears identical to one we already have: " + JoH.dateTimeText(timestamp) + " " + insulin + " " + carbs + " " + notes);
                        }
                    } else {
                        if (existing != null) {
                            if (d)
                                UserError.Log.d(TAG, "Treatment with uuid: " + uuid + " / " + nightscout_id + " already exists");
                            if (notes == null) notes = "";
                            if (existing.notes == null) existing.notes = "";
                            if ((existing.carbs != carbs) || (existing.insulin != insulin) || ((existing.timestamp / Constants.SECOND_IN_MS) != (timestamp / Constants.SECOND_IN_MS))
                                    || (!existing.notes.contains(notes))) {
                                UserError.Log.ueh(TAG, "Treatment changes from Nightscout: " + carbs + " Insulin: " + insulin + " timestamp: " + JoH.dateTimeText(timestamp) + " " + notes + " " + " vs " + existing.carbs + " " + existing.insulin + " " + JoH.dateTimeText(existing.timestamp) + " " + existing.notes);
                                existing.carbs = carbs;
                                existing.insulin = insulin;
                                if (insulin > 0)
                                    existing.setInsulinJSON(injections);
                                existing.timestamp = timestamp;
                                existing.created_at = DateUtil.toISOString(timestamp);
                                if (existing.notes.length() > 0) {
                                    existing.notes += " \u2192 " + notes;
                                } else {
                                    existing.notes = notes;
                                }
                                existing.save();
                                if (Home.get_show_wear_treatments())
                                    pushTreatmentSyncToWatch(existing, false);
                                new_data = true;
                            }
                        } else {
                            UserError.Log.d(TAG, "Skipping record creation as original source is xDrip");
                        }
                    }
                }
            }
        }
        if (removeMissingNightscoutTreatments(remoteTreatmentIds, minTreatmentTimestamp, minCollectionTimestamp, jsonArray.length() == 0)) {
            new_data = true;
        }
        if (!remoteBloodTestIds.isEmpty() && removeMissingNightscoutBloodTests(remoteBloodTestIds, minBloodTestTimestamp, minCollectionTimestamp, jsonArray.length() == 0)) {
            new_data = true;
        }
        return new_data;
    }

    private static long getCreatedAtTimestamp(final JSONObject tr) {
        try {
            return DateUtil.tolerantFromISODateString(tr.getString("created_at")).getTime();
        } catch (JSONException e) {
            return 0;
        }
    }

    private static long reconcileStartTimestamp(final Set<String> remoteIds, final long minTypeTimestamp, final long minCollectionTimestamp, final boolean emptyResponse) {
        if (!remoteIds.isEmpty()) return minTypeTimestamp;
        return emptyResponse ? 0 : minCollectionTimestamp;
    }

    private static boolean removeMissingNightscoutTreatments(final Set<String> remoteTreatmentIds, final long minTreatmentTimestamp, final long minCollectionTimestamp, final boolean emptyResponse) {
        final long startTimestamp = reconcileStartTimestamp(remoteTreatmentIds, minTreatmentTimestamp, minCollectionTimestamp, emptyResponse);
        if (startTimestamp == Long.MAX_VALUE) return false;
        final List<Treatments> localTreatments;
        localTreatments = Treatments.latestForGraph(Integer.MAX_VALUE, startTimestamp, JoH.tsl() + Constants.DAY_IN_MS);

        boolean removed = false;
        for (final Treatments treatment : localTreatments) {
            if (treatment.uuid == null || treatment.enteredBy == null || !treatment.enteredBy.contains(NightscoutUploader.VIA_NIGHTSCOUT_TAG)) {
                continue;
            }
            if (!remoteTreatmentIds.contains(treatment.uuid)) {
                UserError.Log.ueh(TAG, "Removing Treatment deleted from Nightscout: " + JoH.dateTimeText(treatment.timestamp) + " uuid: " + treatment.uuid);
                Treatments.delete_by_uuid_local_only(treatment.uuid);
                removed = true;
            }
        }
        return removed;
    }

    private static boolean removeMissingNightscoutBloodTests(final Set<String> remoteBloodTestIds, final long minBloodTestTimestamp, final long minCollectionTimestamp, final boolean emptyResponse) {
        final long startTimestamp = reconcileStartTimestamp(remoteBloodTestIds, minBloodTestTimestamp, minCollectionTimestamp, emptyResponse);
        if (startTimestamp == Long.MAX_VALUE) return false;
        final List<BloodTest> localBloodTests = BloodTest.latestForGraph(Integer.MAX_VALUE, startTimestamp, JoH.tsl() + Constants.DAY_IN_MS);

        boolean removed = false;
        for (final BloodTest bloodTest : localBloodTests) {
            if (bloodTest.uuid == null || bloodTest.source == null || !bloodTest.source.contains(NightscoutUploader.VIA_NIGHTSCOUT_TAG)
                    || bloodTest.source.contains(NightscoutUploader.VIA_NIGHTSCOUT_ENTRIES_TAG)) {
                continue;
            }
            if (!remoteBloodTestIds.contains(bloodTest.uuid)) {
                UserError.Log.ueh(TAG, "Removing Bloodtest deleted from Nightscout: " + JoH.dateTimeText(bloodTest.timestamp) + " uuid: " + bloodTest.uuid);
                BloodTest.delete_by_uuid_local_only(bloodTest.uuid);
                removed = true;
            }
        }
        return removed;
    }
}
