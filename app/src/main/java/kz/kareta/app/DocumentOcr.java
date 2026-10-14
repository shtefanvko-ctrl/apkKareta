package kz.kareta.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Local-only Latin OCR for registration documents. The native bridge returns allowlisted vehicle
 * fields, never raw recognized text or document-owner information. No photos are persisted.
 * Cyrillic/Kazakh text coverage is NOT guaranteed by the Latin model.
 */
final class DocumentOcr {
    private static final int MAX_BASE64_CHARS = 2_000_000;
    private static final int MAX_IMAGE_BYTES = 1_500_000;
    private static final int MAX_BITMAP_DIMENSION = 2048;
    private static final Pattern VIN = Pattern.compile("(?<![A-Z0-9])([A-HJ-NPR-Z0-9]{17})(?![A-Z0-9])");
    private static final Pattern PLATE = Pattern.compile("(?<![A-Z0-9])(?:KZ[\\s-]*)?(\\d{3}[\\s-]*[A-Z]{2,3}[\\s-]*\\d{2})(?![A-Z0-9])");
    private static final Pattern YEAR = Pattern.compile("(?<!\\d)(19[5-9]\\d|20\\d{2})(?!\\d)");

    interface Callback {
        void success(JSONObject fields);
        void error(String code);
    }

    static void recognize(String encoded, JSONArray catalog, Callback callback) {
        if (encoded == null || encoded.length() == 0 || encoded.length() > MAX_BASE64_CHARS) {
            callback.error("DOCUMENT_IMAGE_INVALID");
            return;
        }
        Bitmap bitmap;
        try {
            byte[] bytes = Base64.decode(encoded, Base64.NO_WRAP);
            if (bytes.length < 64 || bytes.length > MAX_IMAGE_BYTES) {
                callback.error("DOCUMENT_IMAGE_INVALID");
                return;
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            if (bounds.outWidth < 10 || bounds.outHeight < 10
                    || bounds.outWidth > 10000 || bounds.outHeight > 10000) {
                callback.error("DOCUMENT_IMAGE_INVALID");
                return;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 1;
            while (bounds.outWidth / opts.inSampleSize > MAX_BITMAP_DIMENSION
                    || bounds.outHeight / opts.inSampleSize > MAX_BITMAP_DIMENSION) {
                opts.inSampleSize *= 2;
            }
            bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
            if (bitmap == null) {
                callback.error("DOCUMENT_IMAGE_INVALID");
                return;
            }
        } catch (Throwable invalidImage) {
            callback.error("DOCUMENT_IMAGE_INVALID");
            return;
        }

        final TextRecognizer recognizer =
                TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener(result -> {
                    try {
                        callback.success(parse(result.getText(), catalog));
                    } finally {
                        bitmap.recycle();
                        recognizer.close();
                    }
                })
                .addOnFailureListener(error -> {
                    bitmap.recycle();
                    recognizer.close();
                    callback.error("DOCUMENT_OCR_FAILED");
                });
    }

    private static String unique(Set<String> values) {
        return values.size() == 1 ? values.iterator().next() : "";
    }

    private static boolean hasToken(String haystack, String name) {
        if (name == null || name.isEmpty() || name.length() > 80) return false;
        return Pattern.compile("(?<![\\p{L}\\d])" + Pattern.quote(name.toUpperCase(Locale.ROOT))
                + "(?![\\p{L}\\d])").matcher(haystack).find();
    }

    /** Exposed to package tests only: no raw text is retained or returned. */
    static JSONObject parse(String text, JSONArray catalog) {
        JSONObject fields = new JSONObject();
        String raw = text == null ? "" : text.toUpperCase(Locale.ROOT);
        String[] lines = raw.split("\\r?\\n");
        Set<String> vins = new HashSet<>();
        Set<String> plates = new HashSet<>();
        Set<String> years = new HashSet<>();
        Matcher vinMatch = VIN.matcher(raw);
        while (vinMatch.find()) vins.add(vinMatch.group(1));
        Matcher plateMatch = PLATE.matcher(raw);
        while (plateMatch.find()) plates.add(plateMatch.group(1).replaceAll("[\\s-]+", ""));
        for (int i = 0; i < Math.min(lines.length, 150); i++) {
            String line = lines[i];
            if (!line.contains("YEAR") && !line.contains("ГОД ВЫПУСКА")
                    && !line.contains("ШЫҒАРЫЛҒАН") && !line.contains("ЖЫЛЫ")) continue;
            for (int j = i; j <= Math.min(i + 1, lines.length - 1); j++) {
                Matcher yearMatch = YEAR.matcher(lines[j]);
                while (yearMatch.find()) {
                    int year = Integer.parseInt(yearMatch.group(1));
                    if (year <= java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) + 1) {
                        years.add(yearMatch.group(1));
                    }
                }
            }
        }
        try {
            String vin = unique(vins), plate = unique(plates), year = unique(years);
            if (!vin.isEmpty()) fields.put("vin", vin);
            if (!plate.isEmpty()) fields.put("plateNumber", plate);
            if (!year.isEmpty()) fields.put("year", year);

            List<JSONObject> brands = new ArrayList<>();
            if (catalog != null) {
                for (int i = 0; i < Math.min(catalog.length(), 80); i++) {
                    JSONObject candidate = catalog.optJSONObject(i);
                    if (candidate == null) continue;
                    String name = candidate.optString("name", "");
                    if (hasToken(raw, name)) brands.add(candidate);
                }
            }
            if (brands.size() == 1) {
                JSONObject brand = brands.get(0);
                String brandName = brand.optString("name", "");
                fields.put("brandName", brandName);
                JSONArray models = brand.optJSONArray("models");
                List<String> foundModels = new ArrayList<>();
                if (models != null) {
                    for (int i = 0; i < Math.min(models.length(), 80); i++) {
                        JSONObject model = models.optJSONObject(i);
                        String modelName = model == null ? "" : model.optString("name", "");
                        if (modelName.isEmpty()) continue;
                        for (String line : lines) {
                            if (hasToken(line, modelName)
                                    && (hasToken(line, brandName) || line.contains("MODEL")
                                            || line.contains("МОДЕЛЬ"))) {
                                foundModels.add(modelName);
                                break;
                            }
                        }
                    }
                }
                if (foundModels.size() == 1) fields.put("modelName", foundModels.get(0));
            }
        } catch (JSONException ignored) { }
        return fields;
    }

    private DocumentOcr() {}
}
