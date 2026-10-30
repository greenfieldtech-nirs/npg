package cloud.cloudonix.npg.numberingplan;

import cloud.cloudonix.npg.api.RowJson;
import cloud.cloudonix.npg.audit.XmlAuditService;
import cloud.cloudonix.npg.config.Env;
import cloud.cloudonix.npg.repository.AuditEventRepository;
import cloud.cloudonix.npg.workflow.BusinessRuleException;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * The Israel numbering plan: the original (non-ported) allocation of every number range to an
 * operator, imported from the Ministry of Communications file on data.gov.il and classified with
 * Google libphonenumber.
 */
public class NumberingPlanService {

    /** Official source; configurable (NUMBERING_PLAN_URL) but never taken from an API caller. */
    static final String OFFICIAL_URL = "https://data.gov.il/dataset/df60d73b-aa60-46dc-bb76-f183d6bf055c/resource/"
        + "caa80822-5880-4bbf-808a-06d7f53c4ae5/download/israel_numbering_plan.csv";
    static final int MAX_BYTES = 2 * 1024 * 1024;
    // data.gov.il's CDN refuses requests without a browser-like user agent
    private static final String USER_AGENT = "Mozilla/5.0 (compatible; NPG-NumberingPlan/1.0; +https://github.com/greenfieldtech-nirs/npg)";

    private final Vertx vertx;
    private final Pool pool;
    private final WebClient http;
    private final String sourceUrl;

    public NumberingPlanService(Vertx vertx, Pool pool) {
        this(vertx, pool, Env.get("NUMBERING_PLAN_URL", OFFICIAL_URL));
    }

    NumberingPlanService(Vertx vertx, Pool pool, String sourceUrl) {
        this.vertx = vertx;
        this.pool = pool;
        this.sourceUrl = sourceUrl;
        this.http = WebClient.create(vertx, new WebClientOptions().setConnectTimeout(10_000).setIdleTimeout(30)
            .setMaxRedirects(3).setUserAgent(USER_AGENT));
    }

    // =================================================================== import

    /** Downloads the plan from the official source and imports it. */
    public Future<JsonObject> importOfficial(String actor, String remoteIp) {
        if (!sourceUrl.startsWith("https://") && !Env.isDevMode()) {
            return Future.failedFuture(BusinessRuleException.badRequest("NUMBERING_PLAN_URL must use https"));
        }
        return http.getAbs(sourceUrl).timeout(60_000).putHeader("Accept", "text/csv,*/*").send()
            .recover(err -> Future.failedFuture(new BusinessRuleException(502,
                "Could not reach the numbering plan source: " + err.getMessage())))
            .compose(response -> {
                if (response.statusCode() != 200) {
                    return Future.failedFuture(new BusinessRuleException(502,
                        "The numbering plan source answered HTTP " + response.statusCode()));
                }
                if (response.body() == null || response.body().length() > MAX_BYTES) {
                    return Future.failedFuture(new BusinessRuleException(502, "Unexpected numbering plan file size"));
                }
                return importCsv(response.bodyAsString(StandardCharsets.UTF_8.name()), sourceUrl, actor, remoteIp);
            });
    }

    /** Imports an uploaded copy of the plan CSV (for installations without internet access). */
    public Future<JsonObject> importCsv(String csv, String source, String actor, String remoteIp) {
        if (csv == null || csv.isBlank()) {
            return Future.failedFuture(BusinessRuleException.badRequest("Empty file"));
        }
        if (csv.length() > MAX_BYTES) {
            return Future.failedFuture(BusinessRuleException.badRequest("The file is too large"));
        }
        // parsing and libphonenumber classification are CPU work: keep them off the event loop
        return vertx.executeBlocking(() -> classifyAll(NumberingPlanParser.parse(csv)), false)
            .recover(err -> Future.failedFuture(err instanceof NumberingPlanParser.InvalidPlanException
                ? BusinessRuleException.badRequest(err.getMessage()) : err))
            .compose(plan -> replace(plan, csv, source, actor, remoteIp));
    }

    private record ClassifiedPlan(List<NumberingPlanParser.PlanRange> ranges, List<PhoneNumbers.RangeInfo> info,
                                  List<String> warnings) {
    }

    private static ClassifiedPlan classifyAll(NumberingPlanParser.ParseResult parsed) {
        List<PhoneNumbers.RangeInfo> info = new ArrayList<>();
        for (NumberingPlanParser.PlanRange range : parsed.ranges()) {
            info.add(PhoneNumbers.classify(range.nsnPrefix(), range.nsnLength()));
        }
        return new ClassifiedPlan(parsed.ranges(), info, parsed.warnings());
    }

    /** Replaces the whole plan in one transaction: readers see the old plan or the new one, never a mix. */
    private Future<JsonObject> replace(ClassifiedPlan plan, String csv, String source, String actor, String remoteIp) {
        List<Tuple> rows = new ArrayList<>();
        for (int i = 0; i < plan.ranges().size(); i++) {
            NumberingPlanParser.PlanRange r = plan.ranges().get(i);
            PhoneNumbers.RangeInfo info = plan.info().get(i);
            rows.add(Tuple.from(new Object[]{r.ndc(), r.e164Pattern(), r.nationalPattern(), r.nsnPrefix(), r.nsnLength(),
                r.rangeSize(), r.allocatedTo(), r.notes(), info.numberType(), info.area(), info.exampleInternational(),
                info.exampleNational(), info.valid()}));
        }
        int operators = new HashSet<>(plan.ranges().stream().map(NumberingPlanParser.PlanRange::allocatedTo).toList()).size();
        String sha256 = XmlAuditService.sha256(csv);
        JsonArray warnings = new JsonArray(plan.warnings());
        long rejectedByLibphonenumber = plan.info().stream().filter(i -> !i.valid()).count();
        return pool.withTransaction(tx -> tx.query("DELETE FROM np_numbering_plan").execute()
            .compose(v -> tx.preparedQuery("""
                    INSERT INTO np_numbering_plan (ndc, e164_pattern, national_pattern, nsn_prefix, nsn_length, range_size,
                      allocated_to, notes, number_type, area, example_international, example_national, libphonenumber_valid)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """).executeBatch(rows))
            .compose(v -> tx.preparedQuery("""
                    INSERT INTO np_numbering_plan_imports (source, sha256, range_count, operator_count, warnings_json, imported_by)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """).execute(Tuple.of(source, sha256, rows.size(), operators, warnings.encode(), actor)))
            .compose(v -> new AuditEventRepository(tx).insertAuditEvent("USER", actor, "NUMBERING_PLAN_IMPORTED",
                "NUMBERING_PLAN", sha256, new JsonObject().put("source", source).put("ranges", rows.size())
                    .put("operators", operators).encode(), remoteIp)))
            .map(v -> new JsonObject().put("ranges", rows.size()).put("operators", operators).put("sha256", sha256)
                .put("source", source).put("warnings", warnings).put("rangesNotValidInLibphonenumber", rejectedByLibphonenumber));
    }

    // =================================================================== queries

    /** Ranges, optionally filtered by operator, NDC, number type or a digit search (prefix / pattern). */
    public Future<JsonArray> ranges(String operator, String ndc, String numberType, String search) {
        StringBuilder sql = new StringBuilder("SELECT * FROM np_numbering_plan WHERE 1=1");
        Tuple params = Tuple.tuple();
        if (operator != null && !operator.isBlank()) {
            sql.append(" AND allocated_to = ?");
            params.addValue(operator);
        }
        if (ndc != null && !ndc.isBlank()) {
            sql.append(" AND ndc = ?");
            params.addValue(ndc);
        }
        if (numberType != null && !numberType.isBlank()) {
            sql.append(" AND number_type = ?");
            params.addValue(numberType);
        }
        if (search != null && !search.isBlank()) {
            String nsn = searchDigitsAsNsn(search);
            if (nsn.isEmpty()) {
                sql.append(" AND (allocated_to LIKE ? OR notes LIKE ?)");
                String like = "%" + search.replace("%", "").replace("_", "").trim() + "%";
                params.addValue(like).addValue(like);
            } else {
                // "02-32" lists the ranges under that prefix; a full number finds the range holding it
                sql.append(" AND (nsn_prefix LIKE CONCAT(?, '%') OR ? LIKE CONCAT(nsn_prefix, '%'))");
                params.addValue(nsn).addValue(nsn);
            }
        }
        sql.append(" ORDER BY CAST(ndc AS UNSIGNED), nsn_prefix");
        return pool.preparedQuery(sql.toString()).execute(params).map(NumberingPlanService::toJson);
    }

    /** The digits of a national ("02-32…") or international ("+972 2 32…") search, without trunk or country code. */
    static String searchDigitsAsNsn(String search) {
        String digits = search.replaceAll("\\D", "");
        if ((search.trim().startsWith("+") || digits.length() > 3) && digits.startsWith("972")) {
            digits = digits.substring(3);
        }
        return digits.replaceFirst("^0+", "");
    }

    /** Totals, per-operator and per-NDC aggregates and the latest import. */
    public Future<JsonObject> summary() {
        Future<JsonArray> byOperator = pool.query("""
                SELECT allocated_to AS operator, COUNT(*) AS ranges, SUM(range_size) AS numbers,
                       GROUP_CONCAT(DISTINCT number_type ORDER BY number_type) AS number_types,
                       GROUP_CONCAT(DISTINCT ndc ORDER BY CAST(ndc AS UNSIGNED)) AS ndcs
                FROM np_numbering_plan GROUP BY allocated_to ORDER BY numbers DESC, operator
                """).execute().map(NumberingPlanService::toJson);
        Future<JsonArray> byNdc = pool.query("""
                SELECT ndc, COUNT(*) AS ranges, SUM(range_size) AS numbers, COUNT(DISTINCT allocated_to) AS operators,
                       GROUP_CONCAT(DISTINCT number_type ORDER BY number_type) AS number_types, MAX(area) AS area
                FROM np_numbering_plan GROUP BY ndc ORDER BY CAST(ndc AS UNSIGNED)
                """).execute().map(NumberingPlanService::toJson);
        Future<JsonArray> byType = pool.query("""
                SELECT number_type, COUNT(*) AS ranges, SUM(range_size) AS numbers
                FROM np_numbering_plan GROUP BY number_type ORDER BY numbers DESC
                """).execute().map(NumberingPlanService::toJson);
        Future<JsonObject> totals = pool.query("""
                SELECT COUNT(*) AS ranges, COALESCE(SUM(range_size), 0) AS numbers, COUNT(DISTINCT allocated_to) AS operators,
                       COUNT(DISTINCT ndc) AS ndcs, COALESCE(SUM(NOT libphonenumber_valid), 0) AS not_valid_in_libphonenumber
                FROM np_numbering_plan
                """).execute().map(rows -> toJson(rows).getJsonObject(0));
        Future<JsonObject> lastImport = pool.query("""
                SELECT source, sha256, range_count, operator_count, warnings_json, imported_by, imported_at
                FROM np_numbering_plan_imports ORDER BY id DESC LIMIT 1
                """).execute().map(rows -> rows.size() == 0 ? null : toJson(rows).getJsonObject(0));
        return Future.all(byOperator, byNdc, byType, totals, lastImport).map(all -> new JsonObject()
            .put("totals", totals.result())
            .put("byOperator", byOperator.result())
            .put("byNdc", byNdc.result())
            .put("byType", byType.result())
            .put("lastImport", lastImport.result())
            .put("source", sourceUrl));
    }

    /**
     * Who a number was allocated to (non-ported plan), what libphonenumber makes of it, and whether
     * this gateway has recorded it as ported to someone else.
     */
    public Future<JsonObject> lookup(String input) {
        PhoneNumbers.Parsed parsed = PhoneNumbers.parse(input);
        if (parsed == null) {
            return Future.failedFuture(BusinessRuleException.badRequest("Not an Israeli phone number: " + input));
        }
        JsonObject result = new JsonObject().put("input", input).put("e164", parsed.e164())
            .put("international", parsed.international()).put("national", parsed.national())
            .put("valid", parsed.valid()).put("numberType", parsed.numberType()).put("area", parsed.area());
        return pool.preparedQuery("""
                SELECT * FROM np_numbering_plan WHERE nsn_length = ? AND ? LIKE CONCAT(nsn_prefix, '%')
                ORDER BY CHAR_LENGTH(nsn_prefix) DESC LIMIT 1
                """).execute(Tuple.of(parsed.nsn().length(), parsed.nsn()))
            .compose(rows -> {
                result.put("range", rows.size() == 0 ? null : toJson(rows).getJsonObject(0));
                String nsn = parsed.nsn();
                return pool.preparedQuery("""
                        SELECT n.status, n.last_ported_at, c.operator_code AS current_operator, c.operator_name AS current_operator_name
                        FROM np_numbers n LEFT JOIN np_operators c ON c.id = n.current_operator_id
                        WHERE n.phone_number IN (?, ?, ?) LIMIT 1
                        """).execute(Tuple.of("0" + nsn, "972" + nsn, nsn));
            })
            .map(rows -> result.put("porting", rows.size() == 0 ? null : toJson(rows).getJsonObject(0)));
    }

    private static JsonArray toJson(io.vertx.sqlclient.RowSet<Row> rows) {
        return RowJson.rowsToJsonArray(rows);
    }
}
