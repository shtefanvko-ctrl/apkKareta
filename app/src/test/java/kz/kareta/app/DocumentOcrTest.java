package kz.kareta.app;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

/** Synthetic, non-personal specimens: no real identity or passport documents. */
public final class DocumentOcrTest {
    private JSONArray catalog() throws Exception {
        return new JSONArray()
                .put(new JSONObject().put("name", "Peugeot")
                        .put("models", new JSONArray().put(new JSONObject().put("name", "308"))))
                .put(new JSONObject().put("name", "Toyota")
                        .put("models", new JSONArray().put(new JSONObject().put("name", "Camry"))));
    }

    @Test public void readsOnlyAllowlistedFields() throws Exception {
        JSONObject fields = DocumentOcr.parse(
                "МАРКА / MODEL PEUGEOT 308\nYEAR 2018\nVIN VF3LRHNYWJS123456\n"
                + "REGISTRATION NUMBER KZ 777AAA01\nOWNER NAME PRIVATE PERSON\nIIN 000000000000",
                catalog());
        assertEquals("Peugeot", fields.getString("brandName"));
        assertEquals("308", fields.getString("modelName"));
        assertEquals("2018", fields.getString("year"));
        assertEquals("VF3LRHNYWJS123456", fields.getString("vin"));
        assertEquals("777AAA01", fields.getString("plateNumber"));
        assertFalse(fields.has("ownerName"));
        assertFalse(fields.has("iin"));
        assertFalse(fields.toString().contains("PRIVATE PERSON"));
    }

    @Test public void contradictoryVinIsNotProposed() throws Exception {
        JSONObject fields = DocumentOcr.parse(
                "VIN VF3LRHNYWJS123456\nVIN VF3LRHNYWJS654321",catalog());
        assertFalse(fields.has("vin"));
    }

    @Test public void yearRequiresDocumentLabel() throws Exception {
        JSONObject fields = DocumentOcr.parse(
                "REGISTRATION DATE 2026\nMODEL PEUGEOT 308",catalog());
        assertFalse(fields.has("year"));
    }

    @Test public void modelRequiresOneCatalogBrand() throws Exception {
        JSONObject fields = DocumentOcr.parse("MODEL 308\nVIN VF3LRHNYWJS123456",catalog());
        assertFalse(fields.has("modelName"));
        assertFalse(fields.has("brandName"));
    }

    @Test public void disallowsInvalidVinLetters() throws Exception {
        JSONObject fields = DocumentOcr.parse("VIN ABCDEFGHIJKLMNOPQ",catalog());
        assertFalse(fields.has("vin"));
    }
}
