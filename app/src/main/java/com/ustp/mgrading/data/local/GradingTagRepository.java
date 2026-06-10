package com.ustp.mgrading.data.local;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.ustp.mgrading.data.ml.DetectionResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class GradingTagRepository {
    private static final int DEFAULT_MATCH_DISTANCE = 10;
    private static final float MIN_LIST_CONFIDENCE = 0.50f;
    private final GradingDbHelper dbHelper;

    public GradingTagRepository(Context context) {
        dbHelper = new GradingDbHelper(context.getApplicationContext());
        migrateTagCodesToTbs();
    }

    private void migrateTagCodesToTbs() {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        db.execSQL("UPDATE " + GradingDbHelper.TABLE_TAGS
                + " SET tag_code='TBS-' || substr(tag_code, 5)"
                + " WHERE tag_code LIKE 'TPH-%'");
    }

    public synchronized GradingTag findMatch(int classId, String fingerprint, List<Long> excludedIds) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        List<GradingTag> candidates = new ArrayList<>();
        try (Cursor cursor = db.query(
                GradingDbHelper.TABLE_TAGS,
                null,
                "class_id=?",
                new String[]{String.valueOf(classId)},
                null,
                null,
                "last_seen_at DESC"
        )) {
            while (cursor.moveToNext()) {
                GradingTag tag = fromCursor(cursor);
                if (excludedIds != null && excludedIds.contains(tag.getId())) {
                    continue;
                }
                candidates.add(tag);
            }
        }

        GradingTag best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (GradingTag candidate : candidates) {
            int distance = hammingDistance(fingerprint, candidate.getFingerprint());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return bestDistance <= DEFAULT_MATCH_DISTANCE ? best : null;
    }

    public synchronized GradingTag insertTag(DetectionResult detection, String imagePath, String cropPath,
                                             String annotatedImagePath, String fingerprint, String sessionId, long now) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("tag_code", "");
        values.put("class_id", detection.getClassId());
        values.put("label", detection.getLabel());
        values.put("confidence", detection.getConfidence());
        values.put("bbox_left", detection.getBox().left);
        values.put("bbox_top", detection.getBox().top);
        values.put("bbox_right", detection.getBox().right);
        values.put("bbox_bottom", detection.getBox().bottom);
        values.put("image_path", imagePath);
        values.put("crop_path", cropPath);
        values.put("annotated_image_path", annotatedImagePath);
        values.put("fingerprint", fingerprint);
        values.put("session_id", sessionId);
        values.put("created_at", now);
        values.put("last_seen_at", now);
        values.put("seen_count", 1);
        long id = db.insertOrThrow(GradingDbHelper.TABLE_TAGS, null, values);
        String tagCode = String.format(Locale.US, "TBS-%06d", id);
        ContentValues update = new ContentValues();
        update.put("tag_code", tagCode);
        db.update(GradingDbHelper.TABLE_TAGS, update, "id=?", new String[]{String.valueOf(id)});
        return getById(id);
    }

    public synchronized GradingTag markSeen(GradingTag tag, DetectionResult detection, String sessionId, long now, boolean incrementCount) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("confidence", Math.max(tag.getConfidence(), detection.getConfidence()));
        values.put("bbox_left", detection.getBox().left);
        values.put("bbox_top", detection.getBox().top);
        values.put("bbox_right", detection.getBox().right);
        values.put("bbox_bottom", detection.getBox().bottom);
        values.put("last_seen_at", now);
        values.put("seen_count", incrementCount ? tag.getSeenCount() + 1 : tag.getSeenCount());
        values.put("session_id", sessionId);
        db.update(GradingDbHelper.TABLE_TAGS, values, "id=?", new String[]{String.valueOf(tag.getId())});
        return getById(tag.getId());
    }

    public synchronized void updateAnnotatedImagePath(List<Long> tagIds, String annotatedImagePath) {
        if (tagIds == null || tagIds.isEmpty() || annotatedImagePath == null || annotatedImagePath.trim().isEmpty()) {
            return;
        }
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("annotated_image_path", annotatedImagePath);
        for (Long tagId : tagIds) {
            if (tagId != null) {
                db.update(GradingDbHelper.TABLE_TAGS, values, "id=?", new String[]{String.valueOf(tagId)});
            }
        }
    }

    public synchronized List<GradingTag> getRecentTags(int limit) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        List<GradingTag> tags = new ArrayList<>();
        try (Cursor cursor = db.query(
                GradingDbHelper.TABLE_TAGS,
                null,
                "confidence>=?",
                new String[]{String.valueOf(MIN_LIST_CONFIDENCE)},
                null,
                null,
                "last_seen_at DESC",
                String.valueOf(limit)
        )) {
            while (cursor.moveToNext()) {
                tags.add(fromCursor(cursor));
            }
        }
        return tags;
    }

    public synchronized List<GradingTag> getAllSavedTags() {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        List<GradingTag> tags = new ArrayList<>();
        try (Cursor cursor = db.query(
                GradingDbHelper.TABLE_TAGS,
                null,
                "confidence>=?",
                new String[]{String.valueOf(MIN_LIST_CONFIDENCE)},
                null,
                null,
                "last_seen_at DESC"
        )) {
            while (cursor.moveToNext()) {
                tags.add(fromCursor(cursor));
            }
        }
        return tags;
    }

    private GradingTag getById(long id) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        try (Cursor cursor = db.query(
                GradingDbHelper.TABLE_TAGS,
                null,
                "id=?",
                new String[]{String.valueOf(id)},
                null,
                null,
                null
        )) {
            if (cursor.moveToFirst()) {
                return fromCursor(cursor);
            }
        }
        throw new IllegalStateException("Tag not found: " + id);
    }

    private GradingTag fromCursor(Cursor cursor) {
        return new GradingTag(
                cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                cursor.getString(cursor.getColumnIndexOrThrow("tag_code")),
                cursor.getInt(cursor.getColumnIndexOrThrow("class_id")),
                cursor.getString(cursor.getColumnIndexOrThrow("label")),
                cursor.getFloat(cursor.getColumnIndexOrThrow("confidence")),
                cursor.getFloat(cursor.getColumnIndexOrThrow("bbox_left")),
                cursor.getFloat(cursor.getColumnIndexOrThrow("bbox_top")),
                cursor.getFloat(cursor.getColumnIndexOrThrow("bbox_right")),
                cursor.getFloat(cursor.getColumnIndexOrThrow("bbox_bottom")),
                cursor.getString(cursor.getColumnIndexOrThrow("image_path")),
                cursor.getString(cursor.getColumnIndexOrThrow("crop_path")),
                cursor.getString(cursor.getColumnIndexOrThrow("annotated_image_path")),
                cursor.getString(cursor.getColumnIndexOrThrow("fingerprint")),
                cursor.getString(cursor.getColumnIndexOrThrow("session_id")),
                cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
                cursor.getLong(cursor.getColumnIndexOrThrow("last_seen_at")),
                cursor.getInt(cursor.getColumnIndexOrThrow("seen_count"))
        );
    }

    private int hammingDistance(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return Integer.MAX_VALUE;
        }
        int distance = 0;
        for (int i = 0; i < a.length(); i++) {
            int left = Character.digit(a.charAt(i), 16);
            int right = Character.digit(b.charAt(i), 16);
            if (left < 0 || right < 0) {
                return Integer.MAX_VALUE;
            }
            distance += Integer.bitCount(left ^ right);
        }
        return distance;
    }
}
