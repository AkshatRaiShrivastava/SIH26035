package com.legalmetrology.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class DemoController {
    private static final String STANDARD = "OIML R76-1";
    private static final String RULE_VERSION = "2006-prototype-accuracy";
    private static final BigDecimal MPE_MULTIPLIER = new BigDecimal("0.5");
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public DemoController(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "UP", "service", "legal-metrology-backend", "timestamp", Instant.now());
    }

    @PostMapping("/auth/login")
    public Map<String, Object> login(@Valid @RequestBody LoginRequest request) {
        if (request.email().isBlank() || request.password().isBlank()) {
            throw new IllegalArgumentException("Email and password are required");
        }
        String role = request.email().startsWith("admin") ? "ADMIN" : request.email().startsWith("reviewer") ? "REVIEWER" : "TEST_ENGINEER";
        return Map.of("token", Base64.getUrlEncoder().withoutPadding().encodeToString((request.email() + ":" + role).getBytes(StandardCharsets.UTF_8)), "user", request.email(), "role", role);
    }

    @GetMapping("/manufacturers")
    public List<Map<String, Object>> manufacturers() {
        return jdbc.queryForList("SELECT id, name, created_at AS createdAt FROM manufacturers ORDER BY name");
    }

    @PostMapping("/manufacturers")
    public Map<String, Object> createManufacturer(@Valid @RequestBody ManufacturerRequest request) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO manufacturers(id, name) VALUES (?, ?)", id, request.name());
        audit("CREATE_MANUFACTURER", "MANUFACTURER", id, request.name());
        return jdbc.queryForMap("SELECT id, name, created_at AS createdAt FROM manufacturers WHERE id = ?", id);
    }

    @GetMapping("/instruments")
    public List<Map<String, Object>> instruments() {
        return jdbc.queryForList("SELECT i.id, im.model_number AS model, i.serial_number AS serialNumber, i.accuracy_class AS accuracyClass, i.max_capacity AS maxCapacity, i.verification_scale_interval AS scaleInterval, i.unit, m.name AS manufacturer FROM instruments i JOIN instrument_models im ON im.id = i.instrument_model_id JOIN manufacturers m ON m.id = i.manufacturer_id ORDER BY i.created_at DESC");
    }

    @GetMapping("/instruments/untested")
    public List<Map<String, Object>> untestedInstruments() {
        return jdbc.queryForList("SELECT i.id, im.model_number AS model, i.serial_number AS serialNumber, m.name AS manufacturer, i.accuracy_class AS accuracyClass, i.max_capacity AS maxCapacity, i.verification_scale_interval AS scaleInterval, i.unit FROM instruments i JOIN instrument_models im ON im.id = i.instrument_model_id JOIN manufacturers m ON m.id = i.manufacturer_id WHERE NOT EXISTS (SELECT 1 FROM test_runs t WHERE t.instrument_id = i.id) ORDER BY i.created_at DESC");
    }

    @GetMapping("/test-cases")
    public List<Map<String, Object>> testCases() {
        return List.of(
                Map.of("code", "ACCURACY", "name", "Accuracy / indication error", "supported", true, "standard", STANDARD, "ruleVersion", RULE_VERSION),
                Map.of("code", "REPEATABILITY", "name", "Repeatability", "supported", false, "reason", "Formula and acceptance criteria not yet verified from supplied source"),
                Map.of("code", "ECCENTRICITY", "name", "Eccentricity", "supported", false, "reason", "Formula and acceptance criteria not yet verified from supplied source")
        );
    }

    @PostMapping("/instrument-models")
    public Map<String, Object> createInstrumentModel(@Valid @RequestBody InstrumentModelRequest request) {
        UUID manufacturerId = jdbc.queryForObject("SELECT id FROM manufacturers WHERE name = ?", UUID.class, request.manufacturer());
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO instrument_models(id, manufacturer_id, model_number, description, accuracy_class, max_capacity, min_capacity, verification_scale_interval, unit, type) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", id, manufacturerId, request.model(), request.description(), request.accuracyClass(), request.maxCapacity(), request.minCapacity(), request.scaleInterval(), request.unit(), request.type());
        audit("CREATE_INSTRUMENT_MODEL", "INSTRUMENT_MODEL", id, request.model());
        return jdbc.queryForMap("SELECT id, model_number AS model, description, accuracy_class AS accuracyClass, max_capacity AS maxCapacity, min_capacity AS minCapacity, verification_scale_interval AS scaleInterval, unit, type FROM instrument_models WHERE id = ?", id);
    }

    @PostMapping("/instruments")
    public Map<String, Object> createInstrument(@Valid @RequestBody InstrumentRequest request) {
        UUID id = UUID.randomUUID();
        UUID manufacturerId = jdbc.queryForObject("SELECT id FROM manufacturers WHERE name = ?", UUID.class, request.manufacturer());
        UUID modelId = jdbc.queryForObject("SELECT id FROM instrument_models WHERE model_number = ? AND manufacturer_id = ?", UUID.class, request.model(), manufacturerId);
        jdbc.update("INSERT INTO instruments(id, instrument_model_id, manufacturer_id, serial_number, accuracy_class, max_capacity, min_capacity, verification_scale_interval, unit, type) VALUES (?, ?, ?, ?, ?, ?, 0, ?, ?, 'ELECTRONIC')", id, modelId, manufacturerId, request.serialNumber(), request.accuracyClass(), request.maxCapacity(), request.scaleInterval(), request.unit());
        audit("CREATE_INSTRUMENT", "INSTRUMENT", id, request.model());
        return jdbc.queryForMap("SELECT i.id, im.model_number AS model, i.serial_number AS serialNumber, i.accuracy_class AS accuracyClass, i.max_capacity AS maxCapacity, i.verification_scale_interval AS scaleInterval, i.unit, m.name AS manufacturer FROM instruments i JOIN instrument_models im ON im.id = i.instrument_model_id JOIN manufacturers m ON m.id = i.manufacturer_id WHERE i.id = ?", id);
    }

    @PostMapping("/tests")
    public Map<String, Object> createTest(@Valid @RequestBody TestRequest request) {
        if (!"ACCURACY".equals(request.testType())) throw new IllegalArgumentException("Only ACCURACY is supported in this prototype");
        Map<String, Object> instrument = jdbc.queryForMap("SELECT i.id, i.verification_scale_interval AS scaleInterval FROM instruments i JOIN instrument_models im ON im.id = i.instrument_model_id WHERE im.model_number = ?", request.instrumentModel());
        BigDecimal interval = new BigDecimal(instrument.get("scaleInterval").toString());
        List<ObservationInput> observations = request.observations() == null || request.observations().isEmpty()
            ? List.of(new ObservationInput(request.testLoad(), request.indication(), interval)) : request.observations();
        BigDecimal error = observations.get(0).indication().subtract(observations.get(0).testLoad()).abs();
        BigDecimal mpe = MPE_MULTIPLIER.multiply(interval).setScale(6, RoundingMode.HALF_UP);
        String result = observations.stream().allMatch(observation -> observation.indication().subtract(observation.testLoad()).abs().compareTo(MPE_MULTIPLIER.multiply(observation.scaleInterval())) <= 0) ? "PASS" : "FAIL";
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO test_runs(id, instrument_id, test_type, rule_standard, rule_version, status, result, test_load, indication, error_value, permissible_error) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", id, instrument.get("id"), request.testType(), STANDARD, RULE_VERSION, "SUBMITTED", result, request.testLoad(), request.indication(), error, mpe);
        for (int index = 0; index < observations.size(); index++) {
            ObservationInput observation = observations.get(index);
            BigDecimal observationError = observation.indication().subtract(observation.testLoad()).abs();
            BigDecimal observationMpe = MPE_MULTIPLIER.multiply(observation.scaleInterval()).setScale(6, RoundingMode.HALF_UP);
            jdbc.update("INSERT INTO test_observations(test_run_id, sequence_no, test_load, indication, scale_interval, error_value, permissible_error, result) VALUES (?, ?, ?, ?, ?, ?, ?, ?)", id, index + 1, observation.testLoad(), observation.indication(), observation.scaleInterval(), observationError, observationMpe, observationError.compareTo(observationMpe) <= 0 ? "PASS" : "FAIL");
        }
        audit("CALCULATE_TEST", "TEST_RUN", id, result + ":" + RULE_VERSION);
        return test(id);
    }

    @GetMapping("/tests/{id}")
    public Map<String, Object> test(@PathVariable UUID id) {
        return jdbc.queryForMap("SELECT t.id, im.model_number AS instrumentModel, m.name AS manufacturer, t.test_type AS testType, t.rule_standard AS ruleStandard, t.rule_version AS ruleVersion, t.status, t.result, t.test_load AS testLoad, t.indication, t.error_value AS error, t.permissible_error AS permissibleError, t.created_at AS createdAt FROM test_runs t JOIN instruments i ON i.id = t.instrument_id JOIN instrument_models im ON im.id = i.instrument_model_id JOIN manufacturers m ON m.id = i.manufacturer_id WHERE t.id = ?", id);
    }

    @GetMapping("/tests/{id}/timeline")
    public List<Map<String, Object>> testTimeline(@PathVariable UUID id) {
        return jdbc.queryForList("SELECT sequence_no AS sequence, test_load AS testLoad, indication, scale_interval AS scaleInterval, error_value AS error, permissible_error AS permissibleError, result, created_at AS createdAt FROM test_observations WHERE test_run_id = ? ORDER BY sequence_no", id);
    }

    @GetMapping("/dashboard")
    public Map<String, Object> dashboard() {
        return Map.of("instruments", count("SELECT count(*) FROM instruments"), "tests", count("SELECT count(*) FROM test_runs"), "pass", count("SELECT count(*) FROM test_runs WHERE result = 'PASS'"), "fail", count("SELECT count(*) FROM test_runs WHERE result = 'FAIL'"), "pendingReports", count("SELECT count(*) FROM report_versions WHERE status <> 'FINALIZED'"));
    }

    @PostMapping("/reports/{testId}")
    public Map<String, Object> createReport(@PathVariable UUID testId) {
        Map<String, Object> existing = jdbc.queryForMap("SELECT * FROM test_runs WHERE id = ?", testId);
        Integer version = jdbc.queryForObject("SELECT COALESCE(MAX(version), 0) + 1 FROM report_versions WHERE test_run_id = ?", Integer.class, testId);
        String canonical = canonical(existing, version);
        UUID reportId = UUID.randomUUID();
        jdbc.update("INSERT INTO report_versions(id, test_run_id, version, status, canonical_data) VALUES (?, ?, ?, 'DRAFT', ?)", reportId, testId, version, canonical);
        audit("CREATE_REPORT", "REPORT_VERSION", reportId, canonical);
        return reportRow(reportId);
    }

    @PostMapping("/reports/{reportId}/finalize")
    public Map<String, Object> finalizeReport(@PathVariable UUID reportId) {
        Map<String, Object> current = reportRow(reportId);
        if ("FINALIZED".equals(current.get("status"))) return current;
        String hash = sha256((String) current.get("canonicalData"));
        jdbc.update("UPDATE report_versions SET status = 'FINALIZED', sha256_hash = ?, finalized_at = CURRENT_TIMESTAMP WHERE id = ?", hash, reportId);
        audit("FINALIZE_REPORT", "REPORT_VERSION", reportId, hash);
        return reportRow(reportId);
    }

    @GetMapping("/reports/{reportId}")
    public Map<String, Object> report(@PathVariable UUID reportId) {
        return jdbc.queryForMap("SELECT id, test_run_id AS testId, version, status, canonical_data AS canonicalData, sha256_hash AS sha256Hash, finalized_at AS finalizedAt, created_at AS createdAt FROM report_versions WHERE id = ?", reportId);
    }

    @GetMapping("/reports")
    public List<Map<String, Object>> reports(@RequestParam(required = false) String status, @RequestParam(required = false) String model, @RequestParam(required = false) String result) {
        return jdbc.queryForList("SELECT r.id, r.test_run_id AS testId, r.version, r.status, r.sha256_hash AS sha256Hash, r.created_at AS createdAt, im.model_number AS model, t.result, t.test_type AS testType, m.name AS manufacturer FROM report_versions r JOIN test_runs t ON t.id = r.test_run_id JOIN instruments i ON i.id = t.instrument_id JOIN instrument_models im ON im.id = i.instrument_model_id JOIN manufacturers m ON m.id = i.manufacturer_id WHERE (CAST(? AS VARCHAR) IS NULL OR r.status = CAST(? AS VARCHAR)) AND (CAST(? AS VARCHAR) IS NULL OR im.model_number = CAST(? AS VARCHAR)) AND (CAST(? AS VARCHAR) IS NULL OR t.result = CAST(? AS VARCHAR)) ORDER BY r.created_at DESC", status, status, model, model, result, result);
    }

    @GetMapping("/reports/{reportId}/verify")
    public Map<String, Object> verifyReport(@PathVariable UUID reportId) {
        Map<String, Object> report = report(reportId);
        String actual = sha256((String) report.get("canonicalData"));
        boolean valid = "FINALIZED".equals(report.get("status")) && actual.equals(report.get("sha256Hash"));
        Map<String, Object> test = jdbc.queryForMap("SELECT t.id, im.model_number AS instrumentModel, m.name AS manufacturer, t.test_type AS testType, t.rule_standard AS ruleStandard, t.rule_version AS ruleVersion, t.result, t.status, t.created_at AS createdAt FROM report_versions r JOIN test_runs t ON t.id = r.test_run_id JOIN instruments i ON i.id = t.instrument_id JOIN instrument_models im ON im.id = i.instrument_model_id JOIN manufacturers m ON m.id = i.manufacturer_id WHERE r.id = ?", reportId);
        List<Map<String, Object>> timeline = jdbc.queryForList("SELECT sequence_no AS sequence, test_load AS testLoad, indication, scale_interval AS scaleInterval, error_value AS error, permissible_error AS permissibleError, result, created_at AS createdAt FROM test_observations WHERE test_run_id = ? ORDER BY sequence_no", test.get("id"));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("valid", valid);
        response.put("state", valid ? "VERIFIED" : "TAMPERED");
        response.put("reportId", reportId);
        response.put("version", report.get("version"));
        response.put("storedHash", Objects.toString(report.get("sha256Hash"), ""));
        response.put("calculatedHash", actual);
        response.put("test", test);
        response.put("timeline", timeline);
        return response;
    }

    @GetMapping("/reports/{reportId}/qr")
    public ResponseEntity<byte[]> qr(@PathVariable UUID reportId) {
        Map<String, Object> report = report(reportId);
        String hash = Objects.toString(report.get("sha256Hash"), "");
        if (!"FINALIZED".equals(report.get("status")) || hash.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        String verificationBase = System.getenv().getOrDefault("PUBLIC_VERIFICATION_URL", "http://localhost:5173/verification").replaceAll("/+\\z", "");
        String verificationUrl = verificationBase + "/" + reportId;
        String payload = verificationUrl;
        try {
            Map<EncodeHintType, Object> hints = Map.of(EncodeHintType.MARGIN, 2);
            BitMatrix matrix = new MultiFormatWriter().encode(payload, BarcodeFormat.QR_CODE, 360, 360, hints);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix, "PNG", output);
            return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(output.toByteArray());
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to generate report QR code", exception);
        }
    }

    @GetMapping(value = "/reports/{reportId}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> pdf(@PathVariable UUID reportId) {
        Map<String, Object> report = report(reportId);
        String text = "LEGAL METROLOGY REPORT\nReport: " + reportId + "\nVersion: " + report.get("version") + "\nStatus: " + report.get("status") + "\nSHA-256: " + Objects.toString(report.get("sha256Hash"), "DRAFT");
        byte[] body = minimalPdf(text);
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("report-" + reportId + ".pdf").build().toString()).body(body);
    }

    @GetMapping(value = "/reports/{reportId}/docx", produces = "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
    public ResponseEntity<byte[]> docx(@PathVariable UUID reportId) {
        Map<String, Object> report = report(reportId);
        byte[] body = minimalDocx("LEGAL METROLOGY REPORT\nReport: " + reportId + "\nVersion: " + report.get("version") + "\nStatus: " + report.get("status") + "\nSHA-256: " + Objects.toString(report.get("sha256Hash"), "DRAFT"));
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("report-" + reportId + ".docx").build().toString()).body(body);
    }

    @GetMapping("/audit/verify")
    public Map<String, Object> verifyAudit() {
        List<Map<String, Object>> entries = jdbc.queryForList("SELECT id, to_char(occurred_at, 'YYYY-MM-DD HH24:MI:SS.US') AS occurred_at, actor, action, entity_type, entity_id, payload, previous_hash, entry_hash FROM audit_log ORDER BY occurred_at, id");
        String previous = ""; String broken = null;
        for (Map<String, Object> entry : entries) {
            String expected = sha256(entry.get("occurred_at") + "|" + entry.get("actor") + "|" + entry.get("action") + "|" + entry.get("entity_type") + "|" + entry.get("entity_id") + "|" + entry.get("payload") + "|" + previous);
            if (!expected.equals(entry.get("entry_hash")) || !Objects.equals(Objects.toString(entry.get("previous_hash"), ""), previous)) { broken = Objects.toString(entry.get("id")); break; }
            previous = expected;
        }
        return Map.of("valid", broken == null, "entriesChecked", entries.size(), "firstBrokenEntry", Objects.toString(broken, ""));
    }

    @GetMapping("/audit")
    public List<Map<String, Object>> audit() {
        return jdbc.queryForList("SELECT id, occurred_at AS occurredAt, actor, action, entity_type AS entityType, entity_id AS entityId, previous_hash AS previousHash, entry_hash AS entryHash FROM audit_log ORDER BY occurred_at DESC, id DESC");
    }

    @PostMapping("/tests/{testId}/reverify")
    public Map<String, Object> reverify(@PathVariable UUID testId, @RequestParam(defaultValue = "2006-prototype-accuracy") String ruleVersion) {
        Map<String, Object> original = test(testId);
        BigDecimal error = new BigDecimal(original.get("error").toString());
        BigDecimal permissible = new BigDecimal(original.get("permissibleError").toString());
        return Map.of("testId", testId, "originalResult", original.get("result"), "originalRuleVersion", original.get("ruleVersion"), "reverificationRuleVersion", ruleVersion, "reverifiedResult", error.compareTo(permissible) <= 0 ? "PASS" : "FAIL", "immutableOriginal", true);
    }

    @GetMapping("/analytics")
    public List<Map<String, Object>> analytics() {
        return jdbc.queryForList("SELECT m.name AS manufacturer, im.model_number AS model, t.test_type AS testType, count(*) AS tests, count(*) FILTER (WHERE t.result = 'PASS') AS pass, count(*) FILTER (WHERE t.result = 'FAIL') AS fail FROM test_runs t JOIN instruments i ON i.id = t.instrument_id JOIN instrument_models im ON im.id = i.instrument_model_id JOIN manufacturers m ON m.id = i.manufacturer_id GROUP BY m.name, im.model_number, t.test_type ORDER BY m.name, im.model_number");
    }

    private Map<String, Object> reportRow(UUID id) { return jdbc.queryForMap("SELECT id, test_run_id AS testId, version, status, canonical_data AS canonicalData, sha256_hash AS sha256Hash, finalized_at AS finalizedAt, created_at AS createdAt FROM report_versions WHERE id = ?", id); }
    private long count(String sql) { return jdbc.queryForObject(sql, Long.class); }
    private String canonical(Map<String, Object> data, int version) { try { return mapper.writeValueAsString(new TreeMap<>(Map.of("testId", data.get("id"), "version", version, "testLoad", data.get("test_load"), "indication", data.get("indication"), "result", data.get("result"), "ruleStandard", data.get("rule_standard"), "ruleVersion", data.get("rule_version")))); } catch (JsonProcessingException e) { throw new IllegalStateException(e); } }
    private void audit(String action, String type, UUID id, String payload) { String previous = jdbc.query("SELECT entry_hash FROM audit_log ORDER BY occurred_at DESC, id DESC LIMIT 1", rs -> rs.next() ? rs.getString(1) : ""); String actor = "demo-user"; UUID auditId = UUID.randomUUID(); jdbc.update("INSERT INTO audit_log(id, actor, action, entity_type, entity_id, payload, previous_hash, entry_hash) VALUES (?, ?, ?, ?, ?, ?, ?, ?)", auditId, actor, action, type, id, payload, previous, "PENDING"); String occurred = jdbc.queryForObject("SELECT to_char(occurred_at, 'YYYY-MM-DD HH24:MI:SS.US') FROM audit_log WHERE id = ?", String.class, auditId); String hash = sha256(occurred + "|" + actor + "|" + action + "|" + type + "|" + id + "|" + payload + "|" + previous); jdbc.update("UPDATE audit_log SET entry_hash = ? WHERE id = ?", hash, auditId); }
    private String sha256(String value) { try { byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); StringBuilder result = new StringBuilder(); for (byte b : digest) result.append(String.format("%02x", b)); return result.toString(); } catch (Exception e) { throw new IllegalStateException(e); } }
    private byte[] minimalPdf(String text) { String escaped = text.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)").replace("\n", ") Tj 0 -16 Td ( "); String stream = "BT /F1 12 Tf 50 750 Td (" + escaped + ") Tj ET"; String pdf = "%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n2 0 obj<</Type/Pages/Count 1/Kids[3 0 R]>>endobj\n3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 612 792]/Resources<</Font<</F1 4 0 R>>>>/Contents 5 0 R>>endobj\n4 0 obj<</Type/Font/Subtype/Type1/BaseFont/Helvetica>>endobj\n5 0 obj<</Length " + stream.length() + ">>stream\n" + stream + "\nendstream endobj\ntrailer<</Root 1 0 R>>\n%%EOF"; return pdf.getBytes(StandardCharsets.US_ASCII); }
    private byte[] minimalDocx(String text) { try { ByteArrayOutputStream out = new ByteArrayOutputStream(); try (ZipOutputStream zip = new ZipOutputStream(out)) { entry(zip, "[Content_Types].xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/><Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/></Types>"); entry(zip, "_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>"); entry(zip, "word/_rels/document.xml.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/></Relationships>"); entry(zip, "word/styles.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/></w:style></w:styles>"); String paragraphs = Arrays.stream(text.split("\\n", -1)).map(line -> "<w:p><w:r><w:t xml:space=\"preserve\">" + xml(line) + "</w:t></w:r></w:p>").collect(java.util.stream.Collectors.joining()); entry(zip, "word/document.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>" + paragraphs + "<w:sectPr/></w:body></w:document>"); } return out.toByteArray(); } catch (Exception e) { throw new IllegalStateException(e); } }
    private String xml(String value) { return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;"); }
    private void entry(ZipOutputStream zip, String name, String content) throws Exception { zip.putNextEntry(new ZipEntry(name)); zip.write(content.getBytes(StandardCharsets.UTF_8)); zip.closeEntry(); }

    public record LoginRequest(@NotBlank String email, @NotBlank String password) {}
    public record ManufacturerRequest(@NotBlank String name) {}
    public record InstrumentModelRequest(@NotBlank String manufacturer, @NotBlank String model, String description, @NotBlank String accuracyClass, @NotNull @Positive BigDecimal maxCapacity, @NotNull @Positive BigDecimal minCapacity, @NotNull @Positive BigDecimal scaleInterval, @NotBlank String unit, @NotBlank String type) {}
    public record InstrumentRequest(@NotBlank String manufacturer, @NotBlank String model, @NotBlank String serialNumber, @NotBlank String accuracyClass, @NotNull @Positive BigDecimal maxCapacity, @NotNull @Positive BigDecimal scaleInterval, @NotBlank String unit) {}
    public record TestRequest(@NotBlank String instrumentModel, @NotBlank String testType, @NotNull @Positive BigDecimal testLoad, @NotNull @Positive BigDecimal indication, List<ObservationInput> observations) {}
    public record ObservationInput(@NotNull @Positive BigDecimal testLoad, @NotNull @Positive BigDecimal indication, @NotNull @Positive BigDecimal scaleInterval) {}
}
